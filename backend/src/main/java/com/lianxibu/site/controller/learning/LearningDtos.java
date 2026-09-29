package com.lianxibu.site.controller.learning;

import com.lianxibu.site.service.learning.LearningService;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Closed projections for the learning contract, including published-answer omission. */
public final class LearningDtos {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private LearningDtos() { }
    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Page<T>(java.util.List<T> items, long page, int page_size, long total) { }

    public static Map<String, Object> packageSummary(LearningService.PackageSummary value) {
        return map("id", value.id(), "title", value.title(), "description", value.description(),
                "revision", value.revision(), "state", value.state());
    }
    public static Map<String, Object> route(LearningService.Route value) {
        return map("package_id", value.packageId(), "id", value.id(), "title", value.title(),
                "module_ids", value.moduleIds(), "completed_node_count", value.completedNodeCount(),
                "eligible_node_count", value.eligibleNodeCount(), "completion_rate", value.completionRate());
    }
    public static Map<String, Object> module(LearningService.Module value) {
        return map("package_id", value.packageId(), "id", value.id(), "title", value.title(),
                "route_ids", value.routeIds());
    }
    public static Map<String, Object> node(LearningService.Node value) {
        return map("package_id", value.packageId(), "id", value.id(), "module_id", value.moduleId(),
                "title", value.title(), "scope", value.scope(), "goals", value.goals(),
                "completion_criteria", value.completionCriteria(), "dependencies", value.dependencies(),
                "resources", value.resources().stream().map(v -> map("title", v.title(), "url", v.url())).toList(),
                "revision", value.revision());
    }
    public static Map<String, Object> question(LearningService.Question value) {
        Map<String, Object> result = map("package_id", value.packageId(), "id", value.id(),
                "title", value.title(), "question", value.question(), "difficulty", value.difficulty(),
                "kind", value.kind(), "assessment_points", value.assessmentPoints(),
                "answer_state", value.answerState(), "generation_state", value.generationState(),
                "revision", value.revision());
        if ("published".equals(value.answerState())) {
            if (value.answer() == null) throw new IllegalArgumentException("published answer missing");
            result.put("answer", value.answer());
        } else if (value.answer() != null) {
            throw new IllegalArgumentException("unpublished answer exposed");
        }
        return result;
    }
    public static Map<String, Object> project(LearningService.Project value) {
        return map("package_id", value.packageId(), "id", value.id(), "title", value.title(),
                "description", value.description(), "goals", value.goals(), "requirements", value.requirements(),
                "tasks", value.tasks(), "deliverables", value.deliverables(),
                "acceptance_criteria", value.acceptanceCriteria(), "revision", value.revision());
    }
    public static Map<String, Object> graph(LearningService.Graph value) {
        return map("nodes", value.nodes().stream().map(LearningDtos::node).toList(),
                "edges", value.edges().stream().map(v -> map("from_id", v.fromId(), "to_id", v.toId(),
                        "kind", v.kind())).toList(), "next_cursor", value.nextCursor());
    }
    public static Map<String, Object> resolved(LearningService.ResolvedPackage value) {
        return map("package_id", value.packageId(), "kind", value.kind(), "id", value.id());
    }
    public static Map<String, Object> importResult(LearningService.PackageImportResult value) {
        if (value instanceof LearningService.ImportProgress progress) {
            return map("import_id", progress.importId(), "accepted_part_no", progress.acceptedPartNo(),
                    "part_hash", progress.partHash(), "expires_at", time(progress.expiresAt()));
        }
        if (value instanceof LearningService.PackageSummaryResult summary) return packageSummary(summary.summary());
        throw new IllegalArgumentException("invalid import result");
    }
    public static Map<String, Object> action(LearningService.ActionResult value) {
        if (value == null || value.resultResourceRef() == null || value.affected() == null) {
            throw new IllegalArgumentException("missing action result");
        }
        return map("operation_ref", value.operationRef(), "result_resource_ref", ref(value.resultResourceRef()),
                "result_revision", value.resultRevision(), "generation", value.generation(),
                "cutoff", time(value.cutoff()), "coverage", value.coverage() == null ? null : coverage(value.coverage()),
                "affected", value.affected().stream().map(LearningDtos::ref).toList(),
                "next_cursor", value.nextCursor());
    }
    private static Map<String, Object> ref(LearningService.ResourceRef value) {
        return map("kind", value.kind(), "id", value.id(), "package_id", value.packageId());
    }
    private static Map<String, Object> coverage(LearningService.Coverage value) {
        return map("state", value.state(), "missing_inputs", value.missingInputs().stream()
                .map(v -> map("kind", v.kind(), "id", v.id(), "business_date", v.businessDate(),
                        "reason", v.reason())).toList(), "missing_dates", value.missingDates(),
                "no_update_dates", value.noUpdateDates());
    }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }
    private static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("uneven wire projection");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
