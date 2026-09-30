package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Owner-only job and alert contract port. Business admission and persistence follow later. */
public interface JobsService {
    enum OpsRefusal { REVISION_CONFLICT, RECOVERY_CONDITION_UNMET, IDENTITY_CONFLICT, NOT_FOUND, CONFIGURATION_UNKNOWN }
    final class OpsRejected extends RuntimeException {
        private final OpsRefusal refusal;
        public OpsRejected(OpsRefusal refusal) { super(refusal.name()); this.refusal = refusal; }
        public OpsRefusal refusal() { return refusal; }
    }
    record MissingInput(String kind, String id, LocalDate businessDate, String reason) { }
    record Coverage(String state, List<MissingInput> missingInputs, List<LocalDate> missingDates,
                    List<LocalDate> noUpdateDates) { }
    record Usage(Long inputTokens, Long outputTokens, String cost) { }
    record AttemptSummary(String id, long attemptNo, String state, Usage usage,
                          Instant slotReleasedAt, String releaseReason) { }
    record Job(String id, String recordKind, long revision, String taskType, long priority,
               String state, String intentId, Long intentRevision, String contentId, String reportMode,
               String inputState, String inputSnapshotId, Instant inputCutoffAt, Coverage coverage,
               Instant nextEligibleAt, long callCount, Long maxAttempts, long remainingAttempts,
               boolean retryExhausted, String slotState, long slotRevision, String occupiedAttemptId,
               String externalResultState, Instant slotReleasedAt, String releaseReason,
               List<AttemptSummary> attempts, List<String> waitingReasons) { }
    record Alert(String alertId, String ruleId, String resourceKey, long revision, String level,
                 String state, Instant firstSeenAt, Instant lastSeenAt, String observedValue,
                 String threshold, boolean acknowledged, Instant nextReminderAt) { }
    sealed interface JobOrAlertPage permits JobPage, AlertPage { }
    record JobPage(List<Job> items, long page, int pageSize, long total) implements JobOrAlertPage { }
    record AlertPage(List<Alert> items, long page, int pageSize, long total) implements JobOrAlertPage { }
    record ResourceRef(String kind, String id, String packageId) { }
    record JobCreate(String taskType, ResourceRef target, long expectedRevision, String reason) { }
    record ValidatedResult(String schemaVersion, String inputHash, JsonNode payload, String resultHash) { }
    record JobAction(long expectedRevision, String action, String reason, String attemptId,
                     Long expectedContentRevision, String resultHash, String externalState,
                     List<String> evidenceRefs, ValidatedResult result, Usage usage) { }
    record ActionResult(String operationRef, ResourceRef resultResourceRef, long resultRevision,
                        Long generation, Instant cutoff, Coverage coverage, List<ResourceRef> affected,
                        String nextCursor) { }

    JobOrAlertPage list(Actor actor, String recordKind, String targetType, long page, int pageSize);
    Job get(Actor actor, String id);
    Job create(Actor actor, JobCreate command, String idempotencyKey, String csrfToken, String origin);
    ActionResult action(Actor actor, String id, JobAction command,
                        String idempotencyKey, String csrfToken, String origin);
}
