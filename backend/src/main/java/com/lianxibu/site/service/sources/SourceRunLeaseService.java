package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.RunActionReceipt;
import com.lianxibu.site.domain.sources.RunActionReceipt.Store;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore.Row;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Creates frozen source windows and fences each node claim/control action. */
public final class SourceRunLeaseService {
    public interface NodeAuthorizer {
        /** Production adapter invokes NodeIdentityService.authorizeBearer on every call and replay. */
        Principal authorize(String bearer, String permission, String sourceId);
    }
    public record Scheduled(String runId, String sourceId, String runKind, LocalDate businessDate,
                            long generation) { }
    public record Claim(String runId, String sourceId, String runKind, LocalDate businessDate,
                        long generation, Instant windowStart, Instant windowEnd,
                        String configSnapshotJson, String claimToken, Instant leaseUntil,
                        boolean currentLeaseValid, long runRevision) {
        @Override public String toString() {
            return "Claim[runId=" + runId + ",currentLeaseValid=" + currentLeaseValid + "]";
        }
    }
    public record LeaseOutcome(String runId, String operation, long resultRevision,
                               Instant originalLeaseUntil, boolean currentLeaseValid) { }
    public static final class LeaseRejected extends RuntimeException {
        public LeaseRejected() { super("source run or lease identity rejected"); }
    }
    public static final class CapacityGuarded extends RuntimeException {
        public CapacityGuarded() { super("capacity_guard"); }
    }

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> BACKFILL_KINDS = Set.of("historical_backfill", "production_qualification");
    private final JdbcSourceRunStore runs;
    private final JdbcSourceAdmission sources;
    private final SharedWriteExecutor writes;
    private final RunLeaseTokenCodec tokens;
    private final NodeAuthorizer nodes;
    private final Store receipts;
    private final Clock clock;
    private final java.util.function.Predicate<String> newRunAllowed;

    public SourceRunLeaseService(JdbcSourceRunStore runs, JdbcSourceAdmission sources,
                                 SharedWriteExecutor writes, RunLeaseTokenCodec tokens,
                                 NodeAuthorizer nodes, Store receipts, Clock clock) {
        this(runs,sources,writes,tokens,nodes,receipts,clock,ignored -> false);
    }

    public SourceRunLeaseService(JdbcSourceRunStore runs, JdbcSourceAdmission sources,
                                 SharedWriteExecutor writes, RunLeaseTokenCodec tokens,
                                 NodeAuthorizer nodes, Store receipts, Clock clock,
                                 java.util.function.Predicate<String> newRunAllowed) {
        if (runs == null || sources == null || writes == null || tokens == null
                || nodes == null || receipts == null || clock == null || newRunAllowed == null) {
            throw new IllegalArgumentException("source run dependencies required");
        }
        this.runs = runs;
        this.sources = sources;
        this.writes = writes;
        this.tokens = tokens;
        this.nodes = nodes;
        this.receipts = receipts;
        this.clock = clock;
        this.newRunAllowed = newRunAllowed;
    }

    public SourceRunLeaseService(JdbcSourceRunStore runs, JdbcSourceAdmission sources,
                                 SharedWriteExecutor writes, RunLeaseTokenCodec tokens,
                                 NodeIdentityService nodes, Store receipts, Clock clock) {
        this(runs, sources, writes, tokens, nodes::authorizeBearer, receipts, clock);
    }

    public Scheduled scheduleDaily(String sourceId, ActionContext action) {
        Instant now = clock.instant();
        LocalDateTime local = LocalDateTime.ofInstant(now, SHANGHAI);
        if (local.toLocalTime().isBefore(LocalTime.of(0, 10))) throw new LeaseRejected();
        LocalDate day = local.toLocalDate().minusDays(1);
        Instant start = day.atStartOfDay(SHANGHAI).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        return schedule(sourceId, null, day, start, end, "daily_delta", null, action);
    }

    /** A probe remains disabled; it never converts trial or old enabled into production. */
    public Scheduled scheduleProbe(String sourceId, ActionContext action) {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, SHANGHAI);
        return schedule(sourceId, "probe", day, now, now.plusSeconds(300),
                "daily_delta", null, action);
    }

    public Scheduled scheduleBackfill(String sourceId, String runKind, String baseRunId,
                                      ActionContext action) {
        if (!BACKFILL_KINDS.contains(runKind) || baseRunId == null || baseRunId.isBlank()) {
            throw new LeaseRejected();
        }
        Row base = runs.byId(baseRunId);
        if (base == null || !sourceId.equals(base.sourceId())) throw new LeaseRejected();
        return schedule(sourceId, runKind, base.businessDate(), base.windowStart(),
                base.windowEnd(), "materialize", baseRunId, action);
    }

    /** Two explicit windows on the same business day retain separate generations. */
    public Scheduled scheduleHistoricalWindow(String sourceId, LocalDate businessDate,
                                              Instant start, Instant end, ActionContext action) {
        if (businessDate == null || start == null || end == null
                || !start.isBefore(end) || !start.isBefore(businessDate.plusDays(1)
                        .atStartOfDay(SHANGHAI).toInstant())
                || start.isBefore(businessDate.atStartOfDay(SHANGHAI).toInstant())
                || end.isAfter(businessDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant())) {
            throw new LeaseRejected();
        }
        return schedule(sourceId, "historical_backfill", businessDate, start, end,
                "materialize", null, action);
    }

    private Scheduled schedule(String sourceId, String requestedKind, LocalDate day,
                               Instant start, Instant end, String fetchMode,
                               String baseRunId, ActionContext action) {
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 80
                || action == null || !start.isBefore(end)) throw new LeaseRejected();
        String requestScope = scope(sourceId, requestedKind, day, start, end, fetchMode, baseRunId);
        return writes.execute(action, key -> {
            RunActionReceipt receipt = receipts.find(key);
            if (receipt == null) return null;
            if (!"schedule".equals(receipt.operation()) || receipt.nodeId() != null
                    || !requestScope.equals(receipt.requestScope())) {
                throw new LeaseRejected();
            }
            Row old = runs.byId(receipt.runId());
            return old == null ? null : scheduled(old);
        }, context -> {
            JdbcSourceAdmission.Row source = sources.locked(sourceId);
            if (source == null) throw new LeaseRejected();
            String kind;
            String config;
            String stage = source.activationStage();
            if ("probe".equals(requestedKind)) {
                if (!"disabled".equals(stage) || source.draftConfigJson() == null) {
                    throw new LeaseRejected();
                }
                kind = "probe";
                config = source.draftConfigJson();
            } else {
                if (!Set.of("trial", "production").contains(stage)
                        || source.effectiveConfigJson() == null
                        || source.configValidUntil() == null
                        || !source.configValidUntil().isAfter(clock.instant())) {
                    throw new LeaseRejected();
                }
                kind = requestedKind == null ? ("trial".equals(stage) ? "trial" : "daily")
                        : requestedKind;
                if (requestedKind != null && !"production".equals(stage)) {
                    throw new LeaseRejected();
                }
                config = source.effectiveConfigJson();
            }
            if (!Instant.parse(CanonicalJson.parse(config).get("valid_until").asText())
                    .isAfter(clock.instant())) throw new LeaseRejected();
            if (baseRunId != null) {
                Row base = runs.locked(baseRunId);
                if (base == null || !sourceId.equals(base.sourceId())
                        || !day.equals(base.businessDate())
                        || !start.equals(base.windowStart()) || !end.equals(base.windowEnd())) {
                    throw new LeaseRejected();
                }
            }
            long generation = requestedKind == null ? 1 : runs.nextGeneration(sourceId, kind, day);
            Row existing = runs.natural(sourceId, kind, day, generation);
            if (existing != null) {
                if (!sameWindow(existing, start, end, config, source.configRevision(), stage)) {
                    throw new LeaseRejected();
                }
                receipts.save(new RunActionReceipt(context.actionKey(), "schedule", requestScope, null,
                        existing.id(), null, existing.revision(), null));
                return scheduled(existing);
            }
            if (!context.preallocatedResourceId().matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new LeaseRejected();
            }
            if (!newRunAllowed.test(sourceId)) throw new CapacityGuarded();
            runs.insert(new JdbcSourceRunStore.NewRun(context.preallocatedResourceId(), sourceId,
                    kind, day, generation, start, end, baseRunId, source.configRevision(),
                    config, stage, fetchMode, "probe".equals(kind) ? "probe"
                    : requestedKind == null ? ("trial".equals(kind) ? "trial" : "daily") : kind,
                    clock.instant()));
            RunActionReceipt receipt = new RunActionReceipt(context.actionKey(), "schedule", requestScope, null,
                    context.preallocatedResourceId(), null, 0, null);
            receipts.save(receipt);
            return new Scheduled(receipt.runId(), sourceId, kind, day, generation);
        });
    }

    public Claim claimNext(String bearer, boolean probe, ActionContext action) {
        String permission = probe ? "probe:claim" : "run:claim";
        String requestScope = probe ? "probe" : "run";
        Principal initial = nodes.authorize(bearer, permission, null);
        return claimNext(bearer, probe, initial.sourceIds(), requestScope, action, initial);
    }

    /** Bound a wire claim request to its explicit source subset and stable action identity. */
    public Claim claimNext(String bearer, boolean probe, List<String> requestedSources,
                           int maxRuns, ActionContext action) {
        String permission = probe ? "probe:claim" : "run:claim";
        Principal initial = nodes.authorize(bearer, permission, null);
        if (requestedSources == null || requestedSources.size() > 1000
                || requestedSources.stream().anyMatch(source -> source == null || !initial.sourceIds().contains(source))
                || Set.copyOf(requestedSources).size() != requestedSources.size()
                || maxRuns < 1 || maxRuns > 10) throw new LeaseRejected();
        List<String> ordered = requestedSources.stream().sorted().toList();
        String requestScope = (probe ? "probe:" : "run:") + claimScope(ordered, maxRuns);
        return claimNext(bearer, probe, ordered, requestScope, action, initial);
    }

    private Claim claimNext(String bearer, boolean probe, List<String> requestedSources,
                            String requestScope, ActionContext action, Principal initial) {
        String permission = probe ? "probe:claim" : "run:claim";
        return writes.execute(action, key -> {
            RunActionReceipt receipt = receipts.find(key);
            if (receipt == null) return null;
            if (!"claim".equals(receipt.operation()) || !requestScope.equals(receipt.requestScope())
                    || !initial.nodeId().equals(receipt.nodeId())) {
                throw new LeaseRejected();
            }
            Row row = runs.byId(receipt.runId());
            if (row == null) throw new LeaseRejected();
            nodes.authorize(bearer, permission, row.sourceId());
            if (!requestedSources.contains(row.sourceId())) throw new LeaseRejected();
            return claimReplay(row, receipt);
        }, context -> {
            Principal node = nodes.authorize(bearer, permission, null);
            if (!node.sourceIds().containsAll(requestedSources)) throw new LeaseRejected();
            Row row = runs.claimable(requestedSources, probe ? "probe" : "run", clock.instant());
            if (row == null) return new Claim(null, null, null, null, 0,
                    null, null, null, null, null, false, 0);
            nodes.authorize(bearer, permission, row.sourceId());
            RunLeaseTokenCodec.Issued issued = tokens.issue(row.id(), node.nodeId());
            Instant leaseUntil = clock.instant().plusSeconds(150);
            if (!runs.claim(row, node.nodeId(), issued.digest(), issued.ciphertext(),
                    issued.keyId(), leaseUntil, clock.instant())) throw new LeaseRejected();
            Row claimed = runs.locked(row.id());
            receipts.save(new RunActionReceipt(context.actionKey(), "claim", requestScope,
                    node.nodeId(), row.id(),
                    issued.digest(), claimed.revision(), leaseUntil));
            return new Claim(row.id(), row.sourceId(), row.runKind(), row.businessDate(),
                    row.generation(), row.windowStart(), row.windowEnd(),
                    row.configSnapshotJson(), issued.token(), leaseUntil, true, claimed.revision());
        });
    }

    private static String claimScope(List<String> sources, int maxRuns) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update((byte) maxRuns);
            for (String source : sources) {
                byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
                hash.update((byte) (bytes.length >>> 8));
                hash.update((byte) bytes.length);
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public LeaseOutcome acknowledge(String bearer, String runId, String claimToken,
                                    ActionContext action) {
        return control(bearer, runId, claimToken, "ack", action);
    }
    public LeaseOutcome renew(String bearer, String runId, String claimToken,
                              ActionContext action) {
        return control(bearer, runId, claimToken, "renew", action);
    }
    public LeaseOutcome release(String bearer, String runId, String claimToken,
                                ActionContext action) {
        return control(bearer, runId, claimToken, "release", action);
    }

    private LeaseOutcome control(String bearer, String runId, String claimToken,
                                 String operation, ActionContext action) {
        if (runId == null || runId.isBlank() || action == null) throw new LeaseRejected();
        Row first = runs.byId(runId);
        if (first == null) throw new LeaseRejected();
        String permission = "probe".equals(first.runKind()) ? "probe:write" : "run:write";
        Principal initial = nodes.authorize(bearer, permission, first.sourceId());
        byte[] tokenHash = RunLeaseTokenCodec.digest(claimToken);
        return writes.execute(action, key -> {
            RunActionReceipt receipt = receipts.find(key);
            if (receipt == null) return null;
            if (!operation.equals(receipt.operation()) || !runId.equals(receipt.runId())
                    || !initial.nodeId().equals(receipt.nodeId())
                    || receipt.claimHash() == null
                    || !MessageDigest.isEqual(tokenHash, receipt.claimHash())) {
                throw new LeaseRejected();
            }
            Row current = runs.byId(runId);
            if (current == null) throw new LeaseRejected();
            nodes.authorize(bearer, permission, current.sourceId());
            return outcome(receipt, current);
        }, context -> {
            Row row = runs.locked(runId);
            if (row == null) throw new LeaseRejected();
            Principal node = nodes.authorize(bearer, permission, row.sourceId());
            if (!node.nodeId().equals(row.claimNodeId()) || row.claimTokenHash() == null
                    || !MessageDigest.isEqual(tokenHash, row.claimTokenHash())
                    || row.leaseUntil() == null || !row.leaseUntil().isAfter(clock.instant())) {
                throw new LeaseRejected();
            }
            Instant originalLease = row.leaseUntil();
            boolean changed;
            if ("ack".equals(operation)) {
                changed = runs.acknowledge(row, node.nodeId(), tokenHash, clock.instant());
            } else if ("renew".equals(operation)) {
                originalLease = clock.instant().plusSeconds(150);
                if (originalLease.isBefore(row.leaseUntil())) originalLease = row.leaseUntil();
                changed = runs.renew(row, node.nodeId(), tokenHash, originalLease, clock.instant());
            } else {
                changed = runs.release(row, node.nodeId(), tokenHash, clock.instant());
            }
            if (!changed) throw new LeaseRejected();
            Row current = runs.locked(runId);
            RunActionReceipt receipt = new RunActionReceipt(context.actionKey(), operation, runId,
                    node.nodeId(), runId,
                    tokenHash, current.revision(), originalLease);
            receipts.save(receipt);
            return outcome(receipt, current);
        });
    }

    private Claim claimReplay(Row row, RunActionReceipt receipt) {
        boolean current = sameClaim(row, receipt) && row.leaseUntil() != null
                && row.leaseUntil().isAfter(clock.instant());
        String token = current ? tokens.reopen(row.id(), receipt.nodeId(), row.claimKeyId(),
                row.claimTokenCiphertext(), receipt.claimHash()) : null;
        return new Claim(row.id(), row.sourceId(), row.runKind(), row.businessDate(),
                row.generation(), row.windowStart(), row.windowEnd(),
                row.configSnapshotJson(), token, receipt.originalLeaseUntil(),
                current && token != null, receipt.resultRevision());
    }

    private LeaseOutcome outcome(RunActionReceipt receipt, Row current) {
        boolean valid = sameClaim(current, receipt) && current.leaseUntil() != null
                && current.leaseUntil().isAfter(clock.instant());
        return new LeaseOutcome(receipt.runId(), receipt.operation(), receipt.resultRevision(),
                receipt.originalLeaseUntil(), valid);
    }

    private static boolean sameClaim(Row row, RunActionReceipt receipt) {
        return row.claimTokenHash() != null && receipt.claimHash() != null
                && receipt.nodeId().equals(row.claimNodeId())
                && MessageDigest.isEqual(receipt.claimHash(), row.claimTokenHash());
    }
    private static boolean sameWindow(Row row, Instant start, Instant end, String config,
                                      long configRevision, String stage) {
        return start.equals(row.windowStart()) && end.equals(row.windowEnd())
                && configRevision == row.configVersion() && stage.equals(row.activationStage())
                && Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(config)),
                    CanonicalJson.bytes(CanonicalJson.parse(row.configSnapshotJson())));
    }
    private static Scheduled scheduled(Row row) {
        return new Scheduled(row.id(), row.sourceId(), row.runKind(), row.businessDate(), row.generation());
    }
    private static String scope(String sourceId, String kind, LocalDate day, Instant start,
                                Instant end, String mode, String baseRunId) {
        return String.join("\u0000", sourceId, String.valueOf(kind), day.toString(),
                start.toString(), end.toString(), mode, String.valueOf(baseRunId));
    }
}
