package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcJobReads;
import com.lianxibu.site.repository.jobs.JdbcJobReads.Row;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Read only classification and safe operational summaries of current job/receipt rows. */
public final class JobReadService {
    public enum Kind { GENERATION_INTENT, LOGICAL_TASK, AI_ATTEMPT, SNAPSHOT_PART, DISPOSITION, UNKNOWN }
    public record Usage(Long inputTokens, Long outputTokens, String cost) { }
    public record Summary(String id, Kind kind, String state, long revision, String taskType,
                          int priority, long callCount, Long maxAttempts, Usage usage,
                          List<String> waitingReasons, boolean workerCandidate) { }
    public record ReceiptSummary(String id, String kind, String originalStatus,
                                 String currentStatus, boolean expired) { }
    public record SlotSummary(String state, long revision, String occupiedJobId) { }
    public record Page(List<Summary> items, long page, int pageSize, long total) { }
    private final JdbcJobReads store;
    private final TransactionTemplate reads;
    private final Clock clock;

    public JobReadService(JdbcJobReads store, PlatformTransactionManager tx, Clock clock) {
        if (store == null || tx == null || clock == null) {
            throw new IllegalArgumentException("job read dependencies required");
        }
        this.store = store;
        this.clock = clock;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Optional<Summary> job(String id) {
        key(id);
        return reads.execute(status -> Optional.ofNullable(store.job(id)).map(this::summary));
    }

    public Page jobs(String recordKind, long page, int size) {
        if (recordKind != null && classify(recordKind) == Kind.UNKNOWN) {
            throw new IllegalArgumentException("unknown job kind filter");
        }
        return reads.execute(status -> {
            var rows = store.jobs(recordKind, page, size);
            return new Page(rows.items().stream().map(this::summary).toList(), page, size, rows.total());
        });
    }

    public Optional<ReceiptSummary> receipt(String id) {
        key(id);
        return reads.execute(status -> Optional.ofNullable(store.receipt(id)).map(row ->
                new ReceiptSummary(row.id(), receiptKind(row.receiptKind()), row.originalStatus(),
                        row.currentStatus(), row.expiresAt() != null
                                && !row.expiresAt().isAfter(clock.instant()))));
    }

    public Optional<SlotSummary> slot() {
        return reads.execute(status -> Optional.ofNullable(store.slot()).map(row ->
                new SlotSummary(row.state(), row.revision(), row.occupiedJobId())));
    }

    private Summary summary(Row row) {
        Kind kind = classify(row.recordKind());
        boolean runnable = kind == Kind.LOGICAL_TASK && "queued".equals(row.state())
                && row.eligibleAt() != null && !row.eligibleAt().isAfter(clock.instant())
                && row.priority() >= 1 && row.priority() <= 14;
        List<String> waiting = Set.of("waiting_input", "waiting_retry", "blocked_configuration")
                .contains(row.state()) ? List.of(row.state()) : List.of();
        return new Summary(row.id(), kind, row.state(), row.revision(),
                kind == Kind.UNKNOWN ? null : row.taskType(), row.priority(), row.callCount(),
                row.maxAttempts(), kind == Kind.AI_ATTEMPT ? usage(row) : null,
                waiting, runnable);
    }

    private static Usage usage(Row row) {
        if (row.usageJson() == null && row.costDecimal() == null) return null;
        Long input = null, output = null;
        if (row.usageJson() != null) {
            JsonNode value;
            try {value=CanonicalJson.parse(row.usageJson());}
            catch(IllegalArgumentException error){throw new IllegalStateException("usage summary unavailable",error);}
            if (!value.isObject()) throw new IllegalStateException("usage summary unavailable");
            input = token(value,"input_tokens","prompt_tokens");
            output = token(value,"output_tokens","completion_tokens");
        }
        return new Usage(input, output, row.costDecimal());
    }

    private static Long nonnegative(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try{return com.lianxibu.site.domain.protocol.WireValues.nonnegativeInteger(value);}
        catch(IllegalArgumentException error){throw new IllegalStateException("usage summary unavailable",error);}
    }
    private static Long token(JsonNode value,String wire,String provider){Long a=nonnegative(value.get(wire)),b=nonnegative(value.get(provider));if(a!=null&&b!=null&&!a.equals(b))throw new IllegalStateException("conflicting usage summary");return a!=null?a:b;}

    public static Kind classify(String value) {
        if (value == null) return Kind.UNKNOWN;
        return switch (value) {
            case "generation_intent" -> Kind.GENERATION_INTENT;
            case "logical_task" -> Kind.LOGICAL_TASK;
            case "ai_attempt" -> Kind.AI_ATTEMPT;
            case "snapshot_part" -> Kind.SNAPSHOT_PART;
            case "disposition" -> Kind.DISPOSITION;
            default -> Kind.UNKNOWN;
        };
    }

    private static String receiptKind(String value) {
        return value != null && Set.of("collector_observation", "collector_control", "admin_action")
                .contains(value) ? value : "unknown";
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("invalid job key");
        }
    }
}
