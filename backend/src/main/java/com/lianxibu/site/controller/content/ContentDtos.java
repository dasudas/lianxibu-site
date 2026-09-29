package com.lianxibu.site.controller.content;

import com.lianxibu.site.service.content.ContentService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit, closed wire projections. Null fields are retained where the contract requires them. */
public final class ContentDtos {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private ContentDtos() { }
    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Page<T>(List<T> items, long page, int page_size, long total) { }

    public static Map<String, Object> summary(ContentService.ContentView value) {
        if (value == null || value.coverage() == null || value.blockingReasons() == null) {
            throw new IllegalArgumentException("missing content view");
        }
        Map<String, Object> result = map("id", value.id(), "kind", value.kind(), "title", value.title(),
                "revision", value.revision(), "body_revision", value.bodyRevision(),
                "publication_state", value.publicationState(), "published_at", time(value.publishedAt()),
                "input_snapshot_id", value.inputSnapshotId(), "generation", value.generation(),
                "input_cutoff_at", time(value.inputCutoffAt()), "coverage", coverage(value.coverage()),
                "review_required", value.reviewRequired(), "blocking_reasons", value.blockingReasons(),
                "affected_by_correction", value.affectedByCorrection(),
                "supersedes_job_id", value.supersedesJobId());
        if ("report".equals(value.kind())) {
            if (value.interpretation() != null) throw new IllegalArgumentException("report interpretation fields");
        } else if (List.of("agent", "skill", "workflow", "model").contains(value.kind())) {
            var profile = value.interpretation();
            if (profile == null || profile.metrics() == null || profile.tags() == null) {
                throw new IllegalArgumentException("missing interpretation profile");
            }
            result.putAll(map("name", profile.name(), "provider", profile.provider(), "tags", profile.tags(),
                    "description", profile.description(), "evidence_time", time(profile.evidenceTime()),
                    "metrics", profile.metrics().stream().map(ContentDtos::metric).toList(),
                    "stars", profile.stars(), "stars_growth", profile.starsGrowth()));
        } else throw new IllegalArgumentException("unknown content kind");
        return result;
    }

    public static Map<String, Object> detail(ContentService.ContentView value) {
        Map<String, Object> result = summary(value);
        if (value.body() == null || value.citations() == null) throw new IllegalArgumentException("missing detail");
        if ("report".equals(value.kind()) && !(value.body() instanceof ContentService.ReportBody)) {
            throw new IllegalArgumentException("wrong report body");
        }
        if (!"report".equals(value.kind()) && !(value.body() instanceof ContentService.InterpretationBody)) {
            throw new IllegalArgumentException("wrong interpretation body");
        }
        result.put("body", body(value.body()));
        result.put("citations", value.citations().stream().map(ContentDtos::citation).toList());
        return result;
    }

    public static Map<String, Object> body(ContentService.ContentBody value) {
        Map<String, Object> result = map("title", value instanceof ContentService.ReportBody report
                ? report.title() : ((ContentService.InterpretationBody) value).title());
        List<ContentService.Section> sections;
        List<String> citationIds;
        if (value instanceof ContentService.ReportBody report) {
            sections = report.sections(); citationIds = report.citationIds();
            result.putAll(map("type", report.type(), "period_start", time(report.periodStart()),
                    "period_end", time(report.periodEnd()), "timezone", report.timezone(),
                    "learning_task", report.learningTask() == null ? null : task(report.learningTask())));
        } else {
            var interpretation = (ContentService.InterpretationBody) value;
            sections = interpretation.sections(); citationIds = interpretation.citationIds();
            result.putAll(map("category", interpretation.category(), "entity_id", interpretation.entityId()));
        }
        if (sections == null || citationIds == null) throw new IllegalArgumentException("missing body members");
        result.put("sections", sections.stream().map(ContentDtos::section).toList());
        result.put("citation_ids", citationIds);
        return result;
    }

    public static Map<String, Object> version(ContentService.VersionSummary value) {
        return map("target", key(value.target()), "version_kind", value.versionKind(),
                "validity_revision", value.validityRevision(), "effective_valid", value.effectiveValid());
    }

    public static Map<String, Object> action(ContentService.ActionResult value) {
        if (value == null || value.affected() == null || value.resultResourceRef() == null) {
            throw new IllegalArgumentException("missing action result");
        }
        return map("operation_ref", value.operationRef(), "result_resource_ref", ref(value.resultResourceRef()),
                "result_revision", value.resultRevision(), "generation", value.generation(),
                "cutoff", time(value.cutoff()), "coverage", value.coverage() == null ? null : coverage(value.coverage()),
                "affected", value.affected().stream().map(ContentDtos::ref).toList(),
                "next_cursor", value.nextCursor());
    }

    public static Map<String, Object> job(ContentService.Job value) {
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
                "attempts", value.attempts().stream().map(ContentDtos::attempt).toList(),
                "waiting_reasons", value.waitingReasons());
    }

    private static Map<String, Object> section(ContentService.Section value) {
        return map("heading", value.heading(), "text", value.text(), "citation_ids", value.citationIds());
    }
    private static Map<String, Object> task(ContentService.LearningTask value) {
        return map("package_id", value.packageId(), "object_kind", value.objectKind(),
                "object_id", value.objectId(), "title", value.title(), "reason", value.reason());
    }
    private static Map<String, Object> citation(ContentService.Citation value) {
        return map("id", value.id(), "kind", value.kind(), "title", value.title(),
                "source_url", value.sourceUrl(), "revision", value.revision(), "body_hash", value.bodyHash());
    }
    private static Map<String, Object> metric(ContentService.MetricSummary value) {
        return map("benchmark", value.benchmark(), "benchmark_version", value.benchmarkVersion(),
                "task", value.task(), "score", value.score(), "unit", value.unit(),
                "evaluated_at", time(value.evaluatedAt()), "higher_is_better", value.higherIsBetter(),
                "evaluation_scope", value.evaluationScope(), "provenance", value.provenance());
    }
    private static Map<String, Object> coverage(ContentService.Coverage value) {
        if (value.missingInputs() == null || value.missingDates() == null || value.noUpdateDates() == null) {
            throw new IllegalArgumentException("missing coverage members");
        }
        return map("state", value.state(), "missing_inputs", value.missingInputs().stream()
                .map(v -> map("kind", v.kind(), "id", v.id(), "business_date", date(v.businessDate()),
                        "reason", v.reason())).toList(),
                "missing_dates", value.missingDates().stream().map(ContentDtos::date).toList(),
                "no_update_dates", value.noUpdateDates().stream().map(ContentDtos::date).toList());
    }
    private static Map<String, Object> key(ContentService.ContentVersionKey value) {
        return map("kind", value.kind(), "content_kind", value.contentKind(), "content_id", value.contentId(),
                "revision", value.revision(), "body_hash", value.bodyHash(), "input_job_id", value.inputJobId());
    }
    private static Map<String, Object> ref(ContentService.ResourceRef value) {
        return map("kind", value.kind(), "id", value.id(), "package_id", value.packageId());
    }
    private static Map<String, Object> attempt(ContentService.AttemptSummary value) {
        return map("id", value.id(), "attempt_no", value.attemptNo(), "state", value.state(),
                "usage", value.usage() == null ? null : map("input_tokens", value.usage().inputTokens(),
                        "output_tokens", value.usage().outputTokens(), "cost", value.usage().cost()),
                "slot_released_at", time(value.slotReleasedAt()), "release_reason", value.releaseReason());
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
