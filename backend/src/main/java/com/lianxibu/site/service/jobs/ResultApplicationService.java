package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates one frozen AI result and atomically applies business writes and downstream intents. */
public final class ResultApplicationService {
    public enum Status { APPLIED, REPLAYED, CONTENT_REVISION_CONFLICT,
        SUPERSEDED, INVALID_EVIDENCE, DISCARDED }
    public record Result(Status status, String resultHash, String contentId,
                         Long contentRevision) { }
    public record Input(String rootId, String attemptId, Stage stage, long generation,
                        String inputHash, long expectedRootRevision,
                        Long expectedContentRevision, Set<String> itemIds,
                        Map<String, Set<String>> candidatesByItem,
                        Set<String> eligibleHighValueItems,
                        Set<String> allowedCitationIds,
                        Set<String> versionEligibleItems,
                        String targetEntityId, String periodStart, String periodEnd) { }
    public record Validated(String kind, String payloadJson, String resultHash,
                            String inputHash) { }
    public record Current(long generation, String inputHash, long rootRevision,
                          Long contentRevision, boolean dependenciesValid,
                          boolean discarded, String appliedResultHash,
                          String retainedResultHash, String activeAttemptId,
                          boolean ownershipValid, String applicationState) { }
    public interface Store {
        Result committed(String actionKey, String actionHash);
        Current current(String rootId);
        void retain(String rootId, String attemptId, Validated result);
        void defer(String rootId, Status reason, String resultHash,
                   String actionKey, String actionHash);
        /** CAS the unique applied marker in the same transaction as the business handler. */
        void markApplied(String rootId, String resultHash, String contentId,
                         Long newContentRevision, String actionKey, String actionHash);
        Validated retained(String rootId);
    }
    public interface Handler {
        /** Joins B04 transaction; CAS before other writes. Null means CAS lost with no writes. */
        Applied apply(Input input, Validated result, Current current, boolean manual);
    }
    public record Applied(String contentId, Long contentRevision) { }
    public static final class Rejected extends RuntimeException {
        public Rejected() { super("AI result failed contract or frozen input validation"); }
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CATEGORIES = Set.of("news_tech", "agent", "skill",
            "workflow", "model");
    private final SharedWriteExecutor writes;
    private final Store store;
    private final Map<String, Handler> handlers;

    public ResultApplicationService(SharedWriteExecutor writes, Store store,
                                    Map<String, Handler> handlers) {
        if (writes == null || store == null || handlers == null
                || !handlers.keySet().containsAll(Set.of("dedup", "value", "analysis",
                        "report", "ad_hoc")))
            throw new IllegalArgumentException("five result handlers required");
        this.writes = writes;
        this.store = store;
        this.handlers = Map.copyOf(handlers);
    }

    public Result applyAutomatic(Input input, String rawResult, ActionContext action) {
        Validated result = validate(input, rawResult);
        if (action == null) throw new Rejected();
        String actionHash = digest("automatic", input.rootId(), input.attemptId(),
                result.resultHash());
        return writes.execute(action, key -> store.committed(key, actionHash), context -> {
            Current current = required(input);
            if (!input.attemptId().equals(current.activeAttemptId())
                    || !current.ownershipValid()) throw new Rejected();
            store.retain(input.rootId(), input.attemptId(), result);
            if (current.appliedResultHash() != null) {
                if (!current.appliedResultHash().equals(result.resultHash())) throw new Rejected();
                return new Result(Status.REPLAYED, result.resultHash(), null,
                        current.contentRevision());
            }
            Status blocked = blocked(input, current);
            if (blocked != null) {
                store.defer(input.rootId(), blocked, result.resultHash(),
                        context.actionKey(), actionHash);
                return new Result(blocked, result.resultHash(), null, current.contentRevision());
            }
            Applied applied = handlers.get(result.kind()).apply(input, result, current, false);
            if (applied == null) {
                store.defer(input.rootId(), Status.CONTENT_REVISION_CONFLICT,
                        result.resultHash(), context.actionKey(), actionHash);
                return new Result(Status.CONTENT_REVISION_CONFLICT, result.resultHash(),
                        null, current.contentRevision());
            }
            store.markApplied(input.rootId(), result.resultHash(), applied.contentId(),
                    applied.contentRevision(), context.actionKey(), actionHash);
            return new Result(Status.APPLIED, result.resultHash(), applied.contentId(),
                    applied.contentRevision());
        });
    }

    public Result applyManual(Input input, String confirmedResultHash,
                              long expectedRootRevision, Long expectedContentRevision,
                              String reason, boolean admin, ActionContext action) {
        if (!admin || reason == null || reason.isBlank() || action == null
                || expectedRootRevision < 0 || !hash(confirmedResultHash)) throw new Rejected();
        String actionHash = digest("manual", input.rootId(), confirmedResultHash,
                Long.toString(expectedRootRevision), String.valueOf(expectedContentRevision),
                reason);
        return writes.execute(action, key -> store.committed(key, actionHash), context -> {
            Validated result = store.retained(input.rootId());
            if (result == null || !confirmedResultHash.equals(result.resultHash()))
                throw new Rejected();
            verifyRetained(input, result);
            Current current = required(input);
            if (current.appliedResultHash() != null || current.discarded()) throw new Rejected();
            if (!Status.CONTENT_REVISION_CONFLICT.name().equals(current.applicationState()))
                throw new Rejected();
            if (current.rootRevision() != expectedRootRevision
                    || !java.util.Objects.equals(current.contentRevision(), expectedContentRevision))
                throw new Rejected();
            if (current.generation() != input.generation()
                    || !current.inputHash().equals(input.inputHash())
                    || !current.dependenciesValid()) throw new Rejected();
            Applied applied = handlers.get(result.kind()).apply(input, result, current, true);
            if (applied == null) throw new Rejected();
            store.markApplied(input.rootId(), result.resultHash(), applied.contentId(),
                    applied.contentRevision(), context.actionKey(), actionHash);
            return new Result(Status.APPLIED, result.resultHash(), applied.contentId(),
                    applied.contentRevision());
        });
    }

    public static Validated validate(Input input, String raw) {
        requireInput(input);
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                throw new Rejected();
            JsonNode root = CanonicalJson.parse(raw);
            keys(root, "schema_version", "input_hash", "payload", "result_hash");
            if (!"1".equals(root.path("schema_version").asText())
                    || !input.inputHash().equals(root.path("input_hash").asText())
                    || !hash(root.path("result_hash").asText())) throw new Rejected();
            JsonNode payload = root.path("payload");
            String kind = expectedKind(input.stage());
            if (!kind.equals(payload.path("kind").asText())) throw new Rejected();
            switch (kind) {
                case "dedup" -> dedup(input, payload);
                case "value" -> value(input, payload);
                case "analysis" -> analysis(input, payload);
                case "report" -> report(input, payload);
                case "ad_hoc" -> adHoc(input, payload);
                default -> throw new Rejected();
            }
            ObjectNode core = JSON.createObjectNode();
            core.put("schema_version", "1");
            core.put("input_hash", input.inputHash());
            core.set("payload", payload);
            String resultHash = hashResult(core);
            if (!resultHash.equals(root.path("result_hash").asText())) throw new Rejected();
            return new Validated(kind, new String(CanonicalJson.bytes(payload),
                    StandardCharsets.UTF_8), resultHash, input.inputHash());
        } catch (RuntimeException invalid) { throw new Rejected(); }
    }

    private static void dedup(Input input, JsonNode payload) {
        keys(payload, "kind", "items", "summary");
        text(payload, "summary", 0, 16384);
        JsonNode items = array(payload, "items", 1, 100);
        Set<String> seen = new HashSet<>();
        String expectedCategory = category(input.stage());
        for (JsonNode item : items) {
            keys(item, "item_id", "category", "decision", "candidate_entity_id",
                    "reason", "confidence", "suggested_title", "suggested_summary",
                    "reanalyze_recommended");
            String id = text(item, "item_id", 1, 64);
            if (!seen.add(id) || !expectedCategory.equals(item.path("category").asText()))
                throw new Rejected();
            String decision = item.path("decision").asText();
            if (!Set.of("new", "existing", "needs_review").contains(decision))
                throw new Rejected();
            JsonNode candidate = item.path("candidate_entity_id");
            if ("existing".equals(decision)) {
                if (!candidate.isTextual() || !input.candidatesByItem()
                        .getOrDefault(id, Set.of()).contains(candidate.asText())) throw new Rejected();
            } else if (!candidate.isNull()) throw new Rejected();
            text(item, "reason", 1, 2048);
            if (!item.path("confidence").isTextual() || !item.path("confidence").asText()
                    .matches("0(\\.[0-9]{1,8})?|1(\\.0{1,8})?")) throw new Rejected();
            text(item, "suggested_title", 1, 512);
            if (!item.path("suggested_summary").isNull())
                text(item, "suggested_summary", 0, 16384);
            if (!item.path("reanalyze_recommended").isBoolean()) throw new Rejected();
            if (item.path("reanalyze_recommended").booleanValue()
                    && !input.versionEligibleItems().contains(id)) throw new Rejected();
        }
        if (!seen.equals(input.itemIds())) throw new Rejected();
    }
    private static void value(Input input, JsonNode payload) {
        keys(payload, "kind", "items", "selected_item_ids", "summary");
        text(payload, "summary", 0, 16384);
        JsonNode items = array(payload, "items", 1, 100);
        Map<String, Boolean> high = new HashMap<>();
        for (JsonNode item : items) {
            keys(item, "item_id", "category", "high_value", "reason");
            String id = text(item, "item_id", 1, 64);
            if (high.putIfAbsent(id, item.path("high_value").booleanValue()) != null
                    || !item.path("high_value").isBoolean()
                    || !CATEGORIES.contains(item.path("category").asText())) throw new Rejected();
            text(item, "reason", 1, 2048);
        }
        if (!high.keySet().equals(input.itemIds())) throw new Rejected();
        JsonNode selected = array(payload, "selected_item_ids", 0, 5);
        Set<String> unique = new HashSet<>();
        for (JsonNode id : selected) {
            if (!id.isTextual() || !unique.add(id.asText())
                    || !Boolean.TRUE.equals(high.get(id.asText()))
                    || !input.eligibleHighValueItems().contains(id.asText())) throw new Rejected();
        }
    }
    private static void analysis(Input input, JsonNode payload) {
        keys(payload, "kind", "body");
        JsonNode body = payload.path("body");
        keys(body, "title", "sections", "citation_ids", "category", "entity_id");
        if (!category(input.stage()).equals(body.path("category").asText())
                || !input.targetEntityId().equals(body.path("entity_id").asText()))
            throw new Rejected();
        contentBody(input, body);
    }
    private static void report(Input input, JsonNode payload) {
        keys(payload, "kind", "body");
        JsonNode body = payload.path("body");
        keys(body, "title", "sections", "citation_ids", "type", "period_start",
                "period_end", "timezone", "learning_task");
        String expectedType = switch (input.stage()) {
            case DAILY_REPORT -> "daily";
            case WEEKLY_REPORT -> "weekly";
            case MONTHLY_REPORT -> "monthly";
            default -> throw new Rejected();
        };
        if (!expectedType.equals(body.path("type").asText())
                || !"Asia/Shanghai".equals(body.path("timezone").asText())
                || !input.periodStart().equals(body.path("period_start").asText())
                || !input.periodEnd().equals(body.path("period_end").asText()))
            throw new Rejected();
        if (!body.path("learning_task").isNull()) {
            if (input.stage() != Stage.DAILY_REPORT) throw new Rejected();
            JsonNode learning = body.path("learning_task");
            keys(learning, "package_id", "object_kind", "object_id", "title", "reason");
            text(learning, "package_id", 1, 64);
            if (!Set.of("node", "question", "project")
                    .contains(learning.path("object_kind").asText())) throw new Rejected();
            text(learning, "object_id", 1, 64);
            text(learning, "title", 1, 512);
            text(learning, "reason", 1, 2048);
        }
        contentBody(input, body);
    }
    private static void adHoc(Input input, JsonNode payload) {
        keys(payload, "kind", "title", "sections", "citation_ids");
        contentBody(input, payload);
    }
    private static void contentBody(Input input, JsonNode body) {
        text(body, "title", 1, 512);
        JsonNode sections = array(body, "sections", 0, 100);
        for (JsonNode section : sections) {
            keys(section, "heading", "text", "citation_ids");
            text(section, "heading", 1, 512);
            text(section, "text", 0, 200000);
            citations(input, array(section, "citation_ids", 0, 1000));
        }
        citations(input, array(body, "citation_ids", 0, 1000));
    }
    private static void citations(Input input, JsonNode list) {
        for (JsonNode citation : list)
            if (!citation.isTextual() || !input.allowedCitationIds().contains(citation.asText()))
                throw new Rejected();
    }
    private static String expectedKind(Stage stage) {
        if (stage.dedup()) return "dedup";
        if (stage == Stage.NEWS_VALUE) return "value";
        if (stage.analysis()) return "analysis";
        if (stage.report()) return "report";
        return "ad_hoc";
    }
    private static String category(Stage stage) {
        return switch (stage) {
            case NEWS_DEDUP -> "news_tech";
            case AGENT_DEDUP, AGENT_ANALYSIS -> "agent";
            case SKILL_DEDUP, SKILL_ANALYSIS -> "skill";
            case WORKFLOW_DEDUP, WORKFLOW_ANALYSIS -> "workflow";
            case MODEL_DEDUP, MODEL_ANALYSIS -> "model";
            default -> throw new Rejected();
        };
    }
    private static void keys(JsonNode object, String... required) {
        if (!object.isObject() || object.size() != required.length
                || !object.properties().stream().map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(Set.copyOf(Arrays.asList(required)))) throw new Rejected();
    }
    private static String text(JsonNode object, String key, int minimum, int maximum) {
        JsonNode value = object.path(key);
        if (!value.isTextual() || value.asText().length() < minimum
                || value.asText().length() > maximum) throw new Rejected();
        return value.asText();
    }
    private static JsonNode array(JsonNode object, String key, int minimum, int maximum) {
        JsonNode value = object.path(key);
        if (!value.isArray() || value.size() < minimum || value.size() > maximum)
            throw new Rejected();
        return value;
    }
    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
    private static String hashResult(JsonNode core) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("ai-result-v1\n".getBytes(StandardCharsets.UTF_8));
            sha.update(CanonicalJson.bytes(core));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void verifyRetained(Input input, Validated result) {
        try {
            ObjectNode value = JSON.createObjectNode();
            value.put("schema_version", "1");
            value.put("input_hash", result.inputHash());
            value.set("payload", CanonicalJson.parse(result.payloadJson()));
            value.put("result_hash", result.resultHash());
            Validated checked = validate(input,
                    new String(CanonicalJson.bytes(value), StandardCharsets.UTF_8));
            if (!checked.equals(result)) throw new Rejected();
        } catch (RuntimeException invalid) { throw new Rejected(); }
    }
    private static String digest(String... fields) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                if (field == null) {
                    sha.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    sha.update(bytes);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireInput(Input input) {
        if (input == null || input.stage() == null || input.rootId() == null
                || input.rootId().isBlank() || input.attemptId() == null
                || input.attemptId().isBlank() || !hash(input.inputHash())
                || input.generation() < 0
                || input.generation() > CanonicalJson.MAX_SAFE_INTEGER
                || input.expectedRootRevision() < 0
                || input.itemIds() == null || input.candidatesByItem() == null
                || input.eligibleHighValueItems() == null || input.allowedCitationIds() == null
                || input.versionEligibleItems() == null
                || input.stage().analysis() && input.targetEntityId() == null
                || input.stage().report()
                    && (input.periodStart() == null || input.periodEnd() == null))
            throw new Rejected();
    }
    private Current required(Input input) {
        Current current = store.current(input.rootId());
        if (current == null) throw new Rejected();
        return current;
    }
    private static Status blocked(Input input, Current current) {
        if (current.discarded()) return Status.DISCARDED;
        if (current.generation() != input.generation()
                || !input.inputHash().equals(current.inputHash())) return Status.SUPERSEDED;
        if (!current.dependenciesValid()) return Status.INVALID_EVIDENCE;
        if (current.rootRevision() != input.expectedRootRevision()
                || !java.util.Objects.equals(current.contentRevision(),
                    input.expectedContentRevision())) return Status.CONTENT_REVISION_CONFLICT;
        return null;
    }
}
