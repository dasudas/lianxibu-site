package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Releases only the old attempt's matching slot; unknown usage and budget remain unknown/used. */
public final class UnknownCallRecoveryService {
    public enum Cause { TIMEOUT, LEASE_EXPIRED, STOPPED_PROCESS }
    public enum State { UNKNOWN_EXTERNAL, CLOSED_UNRESOLVED, ALREADY_TERMINAL }
    public record Attempt(String rootId, String attemptId, String dispatchToken,
                          long slotRevision, long fencing, Instant leaseUntil,
                          Instant startedAt, String ownerEpoch, String rootState,
                          String attemptState, long rootRevision, int callCount) {
        @Override public String toString() { return "Attempt[attemptId=" + attemptId + "]"; }
    }
    public record Result(State state, boolean slotReleased, int callCount,
                         String usageState, String reason) { }
    public record LateEvidence(String providerRequestId, Long promptTokens,
                               Long completionTokens, String resultHash,
                               String note) {
        @Override public String toString() { return "LateEvidence[resultHash=" + resultHash + "]"; }
    }
    public interface Store {
        Result committedRelease(String actionKey, String actionHash);
        Boolean committedLateEvidence(String actionKey, String actionHash);
        Attempt attempt(String attemptId);
        boolean stoppedOwner(String ownerEpoch);
        /** CAS attempt/root state, append reason and release only a slot matching all identity fields. */
        Result releaseUnknown(Attempt value, Cause cause, Instant now, String actionKey,
                              String actionHash);
        /** Requires current unknown root revision; never refunds its call count. */
        Result closeUnresolved(Attempt value, long expectedRevision, String operatorId,
                               String reason, Instant now, String actionKey,
                               String actionHash);
        /** Append evidence to the original attempt only; no result application or slot release. */
        Boolean appendLate(Attempt value, LateEvidence evidence, Instant now,
                           String actionKey, String actionHash);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected() { super("unknown call recovery rejected"); }
    }
    private final SharedWriteExecutor writes;
    private final Store store;
    private final Clock clock;

    public UnknownCallRecoveryService(SharedWriteExecutor writes, Store store, Clock clock) {
        if (writes == null || store == null || clock == null)
            throw new IllegalArgumentException("recovery dependencies required");
        this.writes = writes;
        this.store = store;
        this.clock = clock;
    }

    public Result release(String attemptId, Cause cause, ActionContext action) {
        if (attemptId == null || attemptId.isBlank() || cause == null || action == null)
            throw new Rejected();
        String hash = digest("release", attemptId, cause.name());
        return writes.execute(action, key -> store.committedRelease(key, hash), context -> {
            Attempt value = required(attemptId);
            Instant now = clock.instant();
            if (!"running".equals(value.attemptState())) {
                if ("unknown_external".equals(value.attemptState()))
                    return store.releaseUnknown(value, cause, now, context.actionKey(), hash);
                return new Result(State.ALREADY_TERMINAL, false, value.callCount(),
                        "unknown", "already_terminal");
            }
            boolean allowed = switch (cause) {
                case TIMEOUT -> value.startedAt() != null
                        && !now.isBefore(value.startedAt().plus(Duration.ofSeconds(90)));
                case LEASE_EXPIRED -> value.leaseUntil() != null
                        && !now.isBefore(value.leaseUntil());
                case STOPPED_PROCESS -> value.ownerEpoch() != null
                        && store.stoppedOwner(value.ownerEpoch());
            };
            if (!allowed) throw new Rejected();
            return store.releaseUnknown(value, cause, now, context.actionKey(), hash);
        });
    }

    public Result closeUnresolved(String attemptId, long expectedRootRevision,
                                  String operatorId, String reason, boolean admin,
                                  ActionContext action) {
        if (!admin || attemptId == null || attemptId.isBlank()
                || operatorId == null || operatorId.isBlank() || reason == null
                || reason.isBlank() || reason.length() > 2048 || expectedRootRevision < 0
                || action == null) throw new Rejected();
        String hash = digest("close", attemptId, Long.toString(expectedRootRevision),
                operatorId, reason);
        return writes.execute(action, key -> store.committedRelease(key, hash), context -> {
            Attempt value = required(attemptId);
            if (!"unknown_external".equals(value.attemptState())
                    || !"unknown_external".equals(value.rootState())
                    || value.rootRevision() != expectedRootRevision) throw new Rejected();
            return store.closeUnresolved(value, expectedRootRevision, operatorId,
                    reason, clock.instant(), context.actionKey(), hash);
        });
    }

    public boolean appendLate(String attemptId, LateEvidence evidence,
                              ActionContext action) {
        if (attemptId == null || attemptId.isBlank() || evidence == null || action == null
                || evidence.note() == null || evidence.note().isBlank()
                || evidence.note().length() > 2048
                || evidence.promptTokens() != null && evidence.promptTokens() < 0
                || evidence.completionTokens() != null && evidence.completionTokens() < 0
                || evidence.resultHash() != null
                    && !evidence.resultHash().matches("[0-9a-f]{64}")) throw new Rejected();
        String hash = digest("late", attemptId, evidence.providerRequestId(),
                String.valueOf(evidence.promptTokens()),
                String.valueOf(evidence.completionTokens()),
                evidence.resultHash(), evidence.note());
        return writes.execute(action, key -> store.committedLateEvidence(key, hash), context -> {
            Attempt value = required(attemptId);
            if (!"unknown_external".equals(value.attemptState())) throw new Rejected();
            return store.appendLate(value, evidence, clock.instant(), context.actionKey(), hash);
        });
    }

    private Attempt required(String id) {
        Attempt value = store.attempt(id);
        if (value == null || !id.equals(value.attemptId()) || value.callCount() < 1
                || value.callCount() > 3) throw new Rejected();
        return value;
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
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
