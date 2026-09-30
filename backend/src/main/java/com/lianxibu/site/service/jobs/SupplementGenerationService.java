package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit new cutoff and complete logical generation without modifying the old public body. */
public final class SupplementGenerationService {
    public enum RunKind { FULL, DELTA }
    public record Member(String key, String materialHash, String selectionMetadataJson,
                         boolean included, String exclusionReason) { }
    public record Run(String id, String sourceId, String windowId, long generation,
                      RunKind kind, String baseRunId, String activationStage,
                      String state, Instant completedAt, List<Member> members) { }
    public record Entry(String role, String key, String materialHash,
                        String selectionMetadataJson) { }
    public record Command(String reportType, Instant periodStart, Instant periodEnd,
                          String timezone, String contentId, Long expectedContentRevision,
                          String reason) { }
    public record Current(String intentId, long generation, String contentId,
                          Long contentRevision, String businessInputHash,
                          String publicationState) { }
    public record Plan(Command command, Instant cutoff, long generation, String intentId,
                       String contentId, Long contentRevision, List<Entry> entries,
                       List<String> missingWindows, String coverage,
                       String businessInputHash) { }
    public record Result(String intentId, long generation, String contentId,
                         Instant cutoff, String coverage, String businessInputHash,
                         boolean replayed) { }
    public enum ApplyStatus { APPLIED, SUPERSEDED, CONTENT_REVISION_CONFLICT, FAILED }
    public record ApplyResult(ApplyStatus status, String contentId, Long contentRevision) { }
    public interface Store {
        Result committed(String actionKey, String commandHash);
        Current current(Command command);
        /** Include a placeholder for every expected source/window, even if no run exists. */
        List<Run> runs(Command command, Instant cutoff);
        /** Additional frozen daily/analysis inputs for weekly or monthly reports. */
        List<Entry> upstream(Command command, Instant cutoff);
        String settingsJson(Command command);
        String taskDefinitionVersion(String reportType);
        /** Save intent, immutable snapshot and complete request; do not edit current content here. */
        void create(Plan plan, String actionKey, String commandHash);
        ApplyResult completed(String actionKey, String commandHash);
        /** On failure retain result only; success CAS generation + content revision before body write. */
        ApplyResult finish(String intentId, long generation, String contentId,
                           Long expectedContentRevision, boolean succeeded,
                           String actionKey, String commandHash);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SharedWriteExecutor writes;
    private final Store store;
    private final Clock clock;

    public SupplementGenerationService(SharedWriteExecutor writes, Store store, Clock clock) {
        if (writes == null || store == null || clock == null)
            throw new IllegalArgumentException("supplement dependencies required");
        this.writes = writes;
        this.store = store;
        this.clock = clock;
    }

    public Result request(Command command, ActionContext action) {
        validate(command, action);
        String commandHash = commandHash(command);
        Result previous = store.committed(action.actionKey(), commandHash);
        if (previous != null) return replay(previous);
        return writes.execute(action, key -> store.committed(key, commandHash), ignored -> {
            Instant cutoff = clock.instant();
            Current current = store.current(command);
            if (current == null) throw new Rejected("period_intent_missing");
            if (command.contentId() != null && (!command.contentId().equals(current.contentId())
                    || !command.expectedContentRevision().equals(current.contentRevision())))
                throw new Rejected("revision_conflict");
            if (command.contentId() == null && current.contentId() != null)
                throw new Rejected("content_identity_conflict");
            List<Run> runs = store.runs(command, cutoff);
            Selection selection = select(runs, cutoff);
            List<Entry> entries = new ArrayList<>(selection.entries());
            List<Entry> upstream = store.upstream(command, cutoff);
            if (upstream == null) throw new Rejected("upstream_unavailable");
            entries.addAll(upstream);
            String hash = businessHash(command, store.taskDefinitionVersion(command.reportType()),
                    store.settingsJson(command), entries, selection.missing());
            if (hash.equals(current.businessInputHash())) throw new Rejected("input_unchanged");
            long generation = current.generation() + 1;
            if (generation > CanonicalJson.MAX_SAFE_INTEGER) throw new Rejected("generation_exhausted");
            String intentId = action.preallocatedResourceId();
            String coverage = selection.missing().isEmpty() ? "complete" : "partial";
            Plan plan = new Plan(command, cutoff, generation, intentId, current.contentId(),
                    current.contentRevision(), List.copyOf(entries), selection.missing(),
                    coverage, hash);
            store.create(plan, action.actionKey(), commandHash);
            return new Result(intentId, generation, current.contentId(), cutoff, coverage,
                    hash, false);
        });
    }

    public ApplyResult finish(String intentId, long generation, String contentId,
                              Long expectedContentRevision, boolean succeeded,
                              ActionContext action) {
        if (intentId == null || intentId.isBlank() || generation < 1 || action == null)
            throw new IllegalArgumentException("invalid completion");
        String commandHash = completionHash(intentId, generation, contentId,
                expectedContentRevision, succeeded);
        return writes.execute(action, key -> store.completed(key, commandHash), ignored ->
                store.finish(intentId, generation, contentId, expectedContentRevision,
                        succeeded, action.actionKey(), commandHash));
    }

    public static Selection select(List<Run> runs, Instant cutoff) {
        if (runs == null || cutoff == null) throw new Rejected("runs_unavailable");
        Map<String, Run> byId = new HashMap<>();
        Map<String, Run> latest = new HashMap<>();
        Set<String> windows = new HashSet<>();
        for (Run run : runs) {
            if (run == null || run.id() == null || run.id().isBlank()
                    || run.sourceId() == null || run.windowId() == null || run.kind() == null
                    || run.state() == null || run.members() == null
                    || byId.putIfAbsent(run.id(), run) != null) throw new Rejected("invalid_run");
            String window = run.sourceId() + "\u0000" + run.windowId();
            windows.add(window);
            if (!"production".equals(run.activationStage())
                    || !"complete".equals(run.state()) || run.completedAt() == null
                    || run.completedAt().isAfter(cutoff)) continue;
            Run old = latest.get(window);
            if (old == null || run.generation() > old.generation()) latest.put(window, run);
        }
        List<Entry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String window : windows.stream().sorted().toList()) {
            Run chosen = latest.get(window);
            if (chosen == null) { missing.add(window); continue; }
            Map<String, Member> effective = expand(chosen, byId, new HashSet<>(), cutoff);
            for (Member member : effective.values()) {
                if (member.included()) {
                    entries.add(new Entry("source", window + "\u0000" + member.key(),
                            member.materialHash(), member.selectionMetadataJson()));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::role).thenComparing(Entry::key));
        return new Selection(List.copyOf(entries), List.copyOf(missing));
    }
    public record Selection(List<Entry> entries, List<String> missing) { }

    private static Map<String, Member> expand(Run run, Map<String, Run> byId,
                                              Set<String> seen, Instant cutoff) {
        if (!seen.add(run.id())) throw new Rejected("cyclic_run_base");
        Map<String, Member> effective = new HashMap<>();
        if (run.kind() == RunKind.DELTA) {
            Run base = byId.get(run.baseRunId());
            if (base == null || !"production".equals(base.activationStage())
                    || !"complete".equals(base.state())
                    || base.completedAt() == null || base.completedAt().isAfter(cutoff)
                    || !base.sourceId().equals(run.sourceId())
                    || !base.windowId().equals(run.windowId())
                    || base.generation() >= run.generation())
                throw new Rejected("invalid_incremental_base");
            effective.putAll(expand(base, byId, seen, cutoff));
        } else if (run.baseRunId() != null) {
            throw new Rejected("unexpected_full_base");
        }
        Set<String> localKeys = new HashSet<>();
        for (Member member : run.members()) {
            if (member == null || member.key() == null || member.key().isBlank())
                throw new Rejected("invalid_run_member");
            if (!localKeys.add(member.key())) throw new Rejected("duplicate_run_member");
            if (!member.included()) {
                if (member.exclusionReason() == null || member.exclusionReason().isBlank())
                    throw new Rejected("missing_exclusion_reason");
                effective.remove(member.key());
            }
            else {
                if (member.materialHash() == null || !member.materialHash().matches("[0-9a-f]{64}")
                        || member.selectionMetadataJson() == null)
                    throw new Rejected("invalid_run_member");
                effective.put(member.key(), member);
            }
        }
        return effective;
    }

    public static String businessHash(Command command, String definitionVersion,
                                      String settingsJson, List<Entry> entries,
                                      List<String> missingWindows) {
        if (definitionVersion == null || definitionVersion.isBlank()
                || settingsJson == null || entries == null || missingWindows == null)
            throw new Rejected("business_input_missing");
        ObjectNode payload = JSON.createObjectNode();
        payload.put("task_type", command.reportType());
        payload.put("task_definition_version", definitionVersion);
        ObjectNode period = JSON.createObjectNode();
        period.put("start", command.periodStart().toString());
        period.put("end", command.periodEnd().toString());
        period.put("timezone", command.timezone());
        payload.set("period", period);
        JsonNode settings = CanonicalJson.parse(settingsJson);
        if (!settings.isObject()) throw new Rejected("invalid_business_settings");
        payload.set("settings", settings);
        ArrayNode members = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        if (entries.stream().anyMatch(e -> e == null || e.role() == null || e.key() == null))
            throw new Rejected("invalid_business_entry");
        for (Entry entry : entries.stream().sorted(Comparator.comparing(Entry::role)
                .thenComparing(Entry::key)).toList()) {
            if (entry == null || entry.role() == null || entry.key() == null
                    || entry.materialHash() == null
                    || !entry.materialHash().matches("[0-9a-f]{64}")
                    || entry.selectionMetadataJson() == null
                    || !seen.add(entry.role() + "\u0000" + entry.key()))
                throw new Rejected("invalid_business_entry");
            ObjectNode member = JSON.createObjectNode();
            member.put("role", entry.role());
            member.put("key", entry.key());
            member.put("material_hash", entry.materialHash());
            member.set("selection_metadata", CanonicalJson.parse(entry.selectionMetadataJson()));
            members.add(member);
        }
        payload.set("entries", members);
        ArrayNode missing = JSON.createArrayNode();
        if (missingWindows.stream().anyMatch(w -> w == null || w.isBlank()))
            throw new Rejected("invalid_missing_window");
        for (String window : missingWindows.stream().sorted().toList()) {
            missing.add(window);
        }
        payload.set("missing_windows", missing);
        return CanonicalJson.hash(Domain.BUSINESS_INPUT, payload);
    }

    private static void validate(Command command, ActionContext action) {
        if (command == null || action == null || !Set.of("daily", "weekly", "monthly")
                .contains(command.reportType()) || command.periodStart() == null
                || command.periodEnd() == null || !command.periodStart().isBefore(command.periodEnd())
                || !"Asia/Shanghai".equals(command.timezone())
                || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > 2048
                || command.contentId() == null && command.expectedContentRevision() != null
                || command.contentId() != null && (command.contentId().isBlank()
                    || command.expectedContentRevision() == null
                    || command.expectedContentRevision() < 1))
            throw new IllegalArgumentException("invalid supplement command");
    }
    private static String commandHash(Command command) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("report_type", command.reportType());
        payload.put("period_start", command.periodStart().toString());
        payload.put("period_end", command.periodEnd().toString());
        payload.put("timezone", command.timezone());
        if (command.contentId() == null) payload.putNull("content_id");
        else payload.put("content_id", command.contentId());
        if (command.expectedContentRevision() == null) payload.putNull("expected_revision");
        else payload.put("expected_revision", command.expectedContentRevision());
        payload.put("reason", command.reason());
        return hashCommand(payload);
    }
    private static String completionHash(String intentId, long generation, String contentId,
                                         Long expectedContentRevision, boolean succeeded) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("intent_id", intentId);
        payload.put("generation", generation);
        if (contentId == null) payload.putNull("content_id");
        else payload.put("content_id", contentId);
        if (expectedContentRevision == null) payload.putNull("expected_revision");
        else payload.put("expected_revision", expectedContentRevision);
        payload.put("succeeded", succeeded);
        return hashCommand(payload);
    }
    private static String hashCommand(ObjectNode payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("supplement-command-v1\n".getBytes(StandardCharsets.UTF_8));
            digest.update(CanonicalJson.bytes(payload));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static Result replay(Result value) {
        return new Result(value.intentId(), value.generation(), value.contentId(), value.cutoff(),
                value.coverage(), value.businessInputHash(), true);
    }
}
