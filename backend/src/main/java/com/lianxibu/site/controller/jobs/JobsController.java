package com.lianxibu.site.controller.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Owner-only job and alert contract boundary. No worker or database starts here. */
@RestController
public final class JobsController {
    private static final Set<String> RECORD_KINDS = Set.of("generation_intent", "logical_task", "ai_attempt",
            "snapshot_part", "disposition");
    private static final Set<String> SIMPLE_ACTIONS = Set.of("cancel", "recover", "discard_result",
            "acknowledge_alert", "resume_capacity_guard");
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<JobsService> jobs;

    public JobsController(ObjectProvider<IdentityService> identity, ObjectProvider<JobsService> jobs) {
        this.identity = identity; this.jobs = jobs;
    }

    @GetMapping(path = "/admin/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobsDtos.Envelope<JobsDtos.Page<Map<String, Object>>>> list(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(cookie);
        for (String field : query.keySet()) {
            if (!Set.of("page", "page_size", "record_kind", "target_type").contains(field) ||
                    query.get(field).size() != 1) throw protocol("unknown or repeated query parameter");
        }
        String kind = query.getFirst("record_kind");
        if (kind != null && !RECORD_KINDS.contains(kind)) throw validation("invalid record kind");
        String type = query.getFirst("target_type");
        if (type != null) type = string(type, 1, 32);
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().list(actor, kind, type, page, size);
        boolean alert = "disposition".equals(kind) && "ops_rule".equals(type);
        if (alert && result instanceof JobsService.AlertPage alerts && alerts.items() != null &&
                alerts.items().size() <= 50) {
            try { return success(new JobsDtos.Page<>(alerts.items().stream().map(JobsDtos::alert).toList(),
                    alerts.page(), alerts.pageSize(), alerts.total())); }
            catch (IllegalArgumentException error) { throw unavailable(); }
        }
        if (!alert && result instanceof JobsService.JobPage jobs && jobs.items() != null &&
                jobs.items().size() <= 50) {
            try { return success(new JobsDtos.Page<>(jobs.items().stream().map(JobsDtos::job).toList(),
                    jobs.page(), jobs.pageSize(), jobs.total())); }
            catch (IllegalArgumentException error) { throw unavailable(); }
        }
        throw unavailable();
    }

    @GetMapping(path = "/admin/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobsDtos.Envelope<Map<String, Object>>> get(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String id) {
        Actor actor = owner(cookie);
        return success(job(service().get(actor, id(id))));
    }

    @PostMapping(path = "/admin/jobs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobsDtos.Envelope<Map<String, Object>>> create(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        JsonNode value = parse(raw);
        fields(value, Set.of("task_type", "target", "expected_revision", "reason"));
        var command = new JobsService.JobCreate(text(value.get("task_type"), 1, 32),
                ref(value.get("target")), number(value.get("expected_revision")),
                text(value.get("reason"), 1, 2048));
        return success(job(service().create(actor, command, key, csrf, origin)));
    }

    @PostMapping(path = "/admin/jobs/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobsDtos.Envelope<Map<String, Object>>> action(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String id,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        JsonNode value = parse(raw);
        var command = action(value);
        return success(result(service().action(actor, id(id), command, key, csrf, origin)));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<JobsDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new JobsDtos.ErrorEnvelope(new JobsDtos.ErrorDetail(error.code,
                error.getMessage()), new JobsDtos.Meta(requestId())));
    }
    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<JobsDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
        int status = switch (error.refusal()) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case REVISION_CONFLICT, IDENTITY_CONFLICT -> 409;
            case RATE_LIMITED -> 429;
            case TEMPORARILY_UNAVAILABLE -> 503;
        };
        String code = error.refusal().name().toLowerCase(java.util.Locale.ROOT);
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 429 || status == 503) {
            String retry = error.retryAfter();
            response.header(HttpHeaders.RETRY_AFTER, retry != null && retry.matches("[1-9][0-9]{0,5}") ? retry : "1");
        }
        return response.body(new JobsDtos.ErrorEnvelope(new JobsDtos.ErrorDetail(code, code),
                new JobsDtos.Meta(requestId())));
    }

    private Actor owner(String cookie) {
        if (cookie == null || cookie.isBlank()) throw new BoundaryFailure(401, "unauthenticated", "session required");
        IdentityService auth = identity.getIfAvailable();
        if (auth == null) throw unavailable();
        Actor actor = auth.authenticate(cookie);
        if (actor == null || actor.userId() == null || actor.userId().isEmpty()) {
            throw new BoundaryFailure(401, "unauthenticated", "session required");
        }
        if (!"owner".equals(actor.role())) throw new BoundaryFailure(403, "forbidden", "owner role required");
        return actor;
    }
    private JobsService service() {
        JobsService value = jobs.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
    }

    private static JobsService.JobAction action(JsonNode value) {
        String action = text(value.get("action"), 1, 32);
        Set<String> common = Set.of("expected_revision", "action", "reason");
        Set<String> branch;
        if (SIMPLE_ACTIONS.contains(action)) branch = Set.of();
        else branch = switch (action) {
            case "close_unresolved" -> Set.of("attempt_id");
            case "apply_result" -> Set.of("expected_content_revision", "result_hash");
            case "resolve_external" -> Set.of("attempt_id", "external_state", "evidence_refs", "result", "usage");
            default -> throw validation("invalid job action");
        };
        var required = new java.util.HashSet<>(common); required.addAll(branch);
        fields(value, Set.copyOf(required));
        String attemptId = branch.contains("attempt_id") ? id(value.get("attempt_id")) : null;
        Long contentRevision = action.equals("apply_result") ? number(value.get("expected_content_revision")) : null;
        String hash = action.equals("apply_result") ? sha(value.get("result_hash")) : null;
        String externalState = null;
        List<String> evidence = null;
        JobsService.ValidatedResult result = null;
        JobsService.Usage usage = null;
        if (action.equals("resolve_external")) {
            externalState = text(value.get("external_state"), 1, 32);
            if (!Set.of("succeeded", "failed", "unknown").contains(externalState)) {
                throw validation("invalid external state");
            }
            evidence = ids(value.get("evidence_refs"), 1, 100);
            JsonNode resultNode = value.get("result");
            if (!resultNode.isNull()) result = validatedResult(resultNode);
            if (externalState.equals("succeeded") && result == null) throw validation("successful result required");
            JsonNode usageNode = value.get("usage");
            if (!usageNode.isNull()) usage = usage(usageNode);
        }
        return new JobsService.JobAction(number(value.get("expected_revision")), action,
                text(value.get("reason"), 1, 2048), attemptId, contentRevision, hash,
                externalState, evidence, result, usage);
    }
    private static JobsService.ResourceRef ref(JsonNode value) {
        fields(value, Set.of("kind", "id", "package_id"));
        return new JobsService.ResourceRef(text(value.get("kind"), 1, 32), id(value.get("id")),
                value.get("package_id").isNull() ? null : id(value.get("package_id")));
    }
    private static JobsService.ValidatedResult validatedResult(JsonNode value) {
        fields(value, Set.of("schema_version", "input_hash", "payload", "result_hash"));
        if (!"1".equals(text(value.get("schema_version"), 1, 8))) throw validation("invalid result schema");
        JsonNode payload = value.get("payload");
        if (!payload.isObject()) throw validation("result payload object required");
        String kind = text(payload.get("kind"), 1, 32);
        Set<String> required = switch (kind) {
            case "dedup" -> Set.of("kind", "items", "summary");
            case "value" -> Set.of("kind", "items", "selected_item_ids", "summary");
            case "analysis", "report" -> Set.of("kind", "body");
            case "ad_hoc" -> Set.of("kind", "title", "sections", "citation_ids");
            default -> throw validation("invalid result payload kind");
        };
        fields(payload, required);
        return new JobsService.ValidatedResult("1", sha(value.get("input_hash")), payload.deepCopy(),
                sha(value.get("result_hash")));
    }
    private static JobsService.Usage usage(JsonNode value) {
        fields(value, Set.of("input_tokens", "output_tokens", "cost"));
        String cost = value.get("cost").isNull() ? null : text(value.get("cost"), 1, 30);
        if (cost != null && !cost.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")) {
            throw validation("invalid usage cost");
        }
        return new JobsService.Usage(value.get("input_tokens").isNull() ? null : number(value.get("input_tokens")),
                value.get("output_tokens").isNull() ? null : number(value.get("output_tokens")), cost);
    }
    private static JsonNode parse(byte[] raw) {
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > 524_288) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
        JsonNode value;
        try { value = CanonicalJson.parse(raw); }
        catch (IllegalArgumentException error) { throw protocol("invalid JSON"); }
        if (!value.isObject()) throw validation("object body required");
        return value;
    }
    private static void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject()) throw validation("object required");
        for (var field : node.properties()) if (!required.contains(field.getKey())) throw protocol("unknown field");
        for (String field : required) if (!node.has(field)) throw validation("required field missing");
    }
    private static String text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw validation("string required");
        String value = node.asText();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw validation("string outside contract bounds");
        return value;
    }
    private static String string(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw validation("query text outside contract bounds");
        return value;
    }
    private static String id(JsonNode node) { return id(text(node, 1, 64)); }
    private static String id(String value) {
        try { return WireValues.id(value, false); }
        catch (IllegalArgumentException error) { throw validation("invalid identity"); }
    }
    private static String sha(JsonNode node) {
        try { return WireValues.sha256(node); }
        catch (IllegalArgumentException error) { throw validation("invalid SHA-256"); }
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid integer"); }
    }
    private static List<String> ids(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            throw validation("invalid identity array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(id(item));
        return List.copyOf(result);
    }
    private static long positive(String raw, long fallback, long max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw validation("invalid positive integer query");
        try { long value = Long.parseLong(raw); if (value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw validation("invalid positive integer query");
    }
    private static void writeHeaders(String csrf, String origin, String key) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }
    private static Map<String, Object> job(JobsService.Job value) {
        try { return JobsDtos.job(value); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static Map<String, Object> result(JobsService.ActionResult value) {
        try { return JobsDtos.action(value); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static <T> ResponseEntity<JobsDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new JobsDtos.Envelope<>(data, new JobsDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "job service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
