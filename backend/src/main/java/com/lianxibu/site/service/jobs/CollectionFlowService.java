package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Joins collector business writes and durable downstream intentions in one short transaction. */
public final class CollectionFlowService {
    public record MemberCandidate(String runId, String memberId, String sourceId,
                                  String evidenceRef, String category,
                                  String terminalState, String projectionDecision,
                                  boolean effectiveValid, LocalDate targetBusinessDate,
                                  String activationState) { }
    public record Accepted(String observationId, String receiptRef, String runId,
                           String deliveryState, List<MemberCandidate> candidates) { }
    public record Finished(String runId, String deliveryState,
                           List<MemberCandidate> candidates) { }
    public record FlowResult(String runId, String deliveryState, int intentionsCreated) { }
    public interface ObservationPort {
        /** Null when this action has no committed observation receipt. */
        Accepted committed(String actionKey);
        /** Must join the caller transaction, including the permanent observation receipt. */
        Accepted acceptJoined(byte[] rawObservation, ActionContext action);
    }
    public interface RunPort {
        /** Null when this action has no committed result receipt. */
        Finished committed(String actionKey);
        /** Must join the caller transaction, including run result and manifest counts. */
        Finished finishJoined(String runId, ActionContext action);
    }
    public interface IntentionPort {
        /** Enforce a unique natural key for the complete member identity; never dispatch here. */
        boolean ensurePending(String runId, String memberId, String sourceId,
                              String evidenceRef, String category,
                              LocalDate targetBusinessDate);
    }
    private static final Set<String> TERMINAL = Set.of("complete", "partial", "no_usable_input", "empty_success");
    private final SharedWriteExecutor writes;
    private final ObservationPort observations;
    private final RunPort runs;
    private final IntentionPort intentions;
    private final Clock clock;

    public CollectionFlowService(SharedWriteExecutor writes, ObservationPort observations,
                                 RunPort runs, IntentionPort intentions, Clock clock) {
        if (writes == null || observations == null || runs == null || intentions == null || clock == null)
            throw new IllegalArgumentException("collection flow dependencies required");
        this.writes = writes;
        this.observations = observations;
        this.runs = runs;
        this.intentions = intentions;
        this.clock = clock;
    }

    public FlowResult accept(byte[] rawObservation, ActionContext action) {
        if (rawObservation == null || rawObservation.length == 0 || action == null)
            throw new IllegalArgumentException("observation action required");
        return writes.execute(action, key -> {
            Accepted old = observations.committed(key);
            return old == null ? null : new FlowResult(old.runId(), old.deliveryState(), 0);
        }, context -> {
            Accepted accepted = observations.acceptJoined(rawObservation, context);
            if (accepted == null || accepted.receiptRef() == null || accepted.observationId() == null)
                throw new IllegalStateException("accepted observation receipt required");
            return activate(accepted.runId(), accepted.deliveryState(), accepted.candidates());
        });
    }

    public FlowResult finish(String runId, ActionContext action) {
        if (runId == null || runId.isBlank() || action == null)
            throw new IllegalArgumentException("run action required");
        return writes.execute(action, key -> {
            Finished old = runs.committed(key);
            return old == null ? null : new FlowResult(old.runId(), old.deliveryState(), 0);
        }, context -> {
            Finished finished = runs.finishJoined(runId, context);
            if (finished == null || !runId.equals(finished.runId()))
                throw new IllegalStateException("run result identity changed");
            return activate(runId, finished.deliveryState(), finished.candidates());
        });
    }

    private FlowResult activate(String runId, String state, List<MemberCandidate> candidates) {
        if (runId == null || runId.isBlank() || state == null || candidates == null)
            throw new IllegalStateException("run outcome incomplete");
        if (!TERMINAL.contains(state)) return new FlowResult(runId, state, 0);
        LocalDate today = LocalDate.now(clock);
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (MemberCandidate member : candidates) {
            if (member == null || !runId.equals(member.runId()) || member.memberId() == null
                    || !seen.add(member.memberId())) throw new IllegalStateException("duplicate run member");
            if (!eligible(member, today)) continue;
            if (intentions.ensurePending(runId, member.memberId(), member.sourceId(),
                    member.evidenceRef(), member.category(), member.targetBusinessDate())) created++;
        }
        return new FlowResult(runId, state, created);
    }

    private static boolean eligible(MemberCandidate member, LocalDate today) {
        if (!"accepted".equals(member.terminalState()) || !member.effectiveValid()
                || "needs_review".equals(member.projectionDecision())
                || member.sourceId() == null || member.evidenceRef() == null
                || member.category() == null) return false;
        if (member.targetBusinessDate() != null && member.targetBusinessDate().isAfter(today)) return false;
        return member.activationState() == null || "pending".equals(member.activationState());
    }
}
