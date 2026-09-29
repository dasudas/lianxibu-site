package com.lianxibu.site.controller.jobs;

import com.lianxibu.site.service.jobs.JobsService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closed Job, Alert and ActionResult projections. */
public final class JobsDtos {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private JobsDtos() { }
    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Page<T>(List<T> items, long page, int page_size, long total) { }

    public static Map<String, Object> job(JobsService.Job value) {
        if (value == null || value.coverage() == null || value.attempts() == null || value.waitingReasons() == null) {
            throw new IllegalArgumentException("missing job");
        }
        return map("id", value.id(), "record_kind", value.recordKind(), "revision", value.revision(),
                "task_type", value.taskType(), "priority", value.priority(), "state", value.state(),
                "intent_id", value.intentId(), "intent_revision", value.intentRevision(),
                "content_id", value.contentId(), "report_mode", value.reportMode(),
                "input_state", value.inputState(), "input_snapshot_id", value.inputSnapshotId(),
                "input_cutoff_at", time(value.inputCutoffAt()), "coverage", coverage(value.coverage()),
                "next_eligible_at", time(value.nextEligibleAt()), "call_count", value.callCount(),
                "max_attempts", value.maxAttempts(), "remaining_attempts", value.remainingAttempts(),
                "retry_exhausted", value.retryExhausted(), "slot_state", value.slotState(),
                "slot_revision", value.slotRevision(), "occupied_attempt_id", value.occupiedAttemptId(),
                "external_result_state", value.externalResultState(),
                "slot_released_at", time(value.slotReleasedAt()), "release_reason", value.releaseReason(),
                "attempts", value.attempts().stream().map(JobsDtos::attempt).toList(),
                "waiting_reasons", value.waitingReasons());
    }
    public static Map<String, Object> alert(JobsService.Alert value) {
        return map("alert_id", value.alertId(), "rule_id", value.ruleId(),
                "resource_key", value.resourceKey(), "revision", value.revision(),
                "level", value.level(), "state", value.state(),
                "first_seen_at", time(value.firstSeenAt()), "last_seen_at", time(value.lastSeenAt()),
                "observed_value", value.observedValue(), "threshold", value.threshold(),
                "acknowledged", value.acknowledged(), "next_reminder_at", time(value.nextReminderAt()));
    }
    public static Map<String, Object> action(JobsService.ActionResult value) {
        if (value == null || value.resultResourceRef() == null || value.affected() == null) {
            throw new IllegalArgumentException("missing action result");
        }
        return map("operation_ref", value.operationRef(), "result_resource_ref", ref(value.resultResourceRef()),
                "result_revision", value.resultRevision(), "generation", value.generation(),
                "cutoff", time(value.cutoff()), "coverage", value.coverage() == null ? null : coverage(value.coverage()),
                "affected", value.affected().stream().map(JobsDtos::ref).toList(),
                "next_cursor", value.nextCursor());
    }
    private static Map<String, Object> ref(JobsService.ResourceRef value) {
        return map("kind", value.kind(), "id", value.id(), "package_id", value.packageId());
    }
    private static Map<String, Object> attempt(JobsService.AttemptSummary value) {
        return map("id", value.id(), "attempt_no", value.attemptNo(), "state", value.state(),
                "usage", value.usage() == null ? null : map("input_tokens", value.usage().inputTokens(),
                        "output_tokens", value.usage().outputTokens(), "cost", value.usage().cost()),
                "slot_released_at", time(value.slotReleasedAt()), "release_reason", value.releaseReason());
    }
    private static Map<String, Object> coverage(JobsService.Coverage value) {
        if (value.missingInputs() == null || value.missingDates() == null || value.noUpdateDates() == null) {
            throw new IllegalArgumentException("missing coverage members");
        }
        return map("state", value.state(), "missing_inputs", value.missingInputs().stream()
                .map(v -> map("kind", v.kind(), "id", v.id(), "business_date", date(v.businessDate()),
                        "reason", v.reason())).toList(),
                "missing_dates", value.missingDates().stream().map(JobsDtos::date).toList(),
                "no_update_dates", value.noUpdateDates().stream().map(JobsDtos::date).toList());
    }
    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }
    private static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("uneven wire projection");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
