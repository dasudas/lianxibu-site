package com.lianxibu.site.controller.records;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.RecordsService;
import com.lianxibu.site.service.records.PrivateRecordWriteService;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Per-user record HTTP contract; no persistence or ownership shortcut in this layer. */
@RestController
public final class RecordsController {
    private static final Set<String> KINDS = Set.of("article", "node", "question", "project");
    private static final Set<String> STATUSES = Set.of("not_started", "planned", "learning", "completed");
    private static final Set<String> ASSESSMENTS = Set.of("unassessed", "not_mastered", "partial", "mastered");
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<RecordsService> records;

    public RecordsController(ObjectProvider<IdentityService> identity, ObjectProvider<RecordsService> records) {
        this.identity = identity; this.records = records;
    }

    @GetMapping(path = "/private/records", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecordsDtos.Envelope<RecordsDtos.RecordPage>> list(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = user(cookie);
        for (String field : query.keySet()) {
            if (!Set.of("page", "page_size", "package_id", "kind").contains(field) || query.get(field).size() != 1) {
                throw protocol("unknown or repeated query parameter");
            }
        }
        String packageId = query.getFirst("package_id");
        if (packageId != null) packageId = id(packageId);
        String kind = query.getFirst("kind");
        if (kind != null && !KINDS.contains(kind)) throw validation("invalid record kind");
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().list(actor, packageId, kind, page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new RecordsDtos.RecordPage(result.items().stream().map(RecordsController::record).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @PutMapping(path = "/private/records/{kind}/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecordsDtos.Envelope<RecordsDtos.Record>> write(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String objectId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = user(cookie);
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
        if (!KINDS.contains(kind)) throw validation("invalid record kind");
        String id = id(objectId);
        JsonNode body = parse(raw);
        Set<String> common = Set.of("expected_revision", "status", "self_assessment", "note",
                "artifact_url", "package_id", "object_kind");
        Set<String> branch = switch (kind) {
            case "article" -> Set.of("is_read", "is_favorite", "is_ignored");
            case "question" -> Set.of("latest_answer_text");
            case "project" -> Set.of("latest_submission_url");
            default -> Set.of();
        };
        Set<String> fields = new java.util.HashSet<>(common);
        fields.addAll(branch);
        for (var field : body.properties()) if (!fields.contains(field.getKey())) throw protocol("unknown field");
        for (String field : fields) if (!body.has(field)) throw validation("required field missing");
        if (!kind.equals(text(body.get("object_kind"), 1, 32))) throw validation("object kind does not match path");
        JsonNode packageNode = body.get("package_id");
        String packageId = kind.equals("article") ? null : id(packageNode);
        if (kind.equals("article") && !packageNode.isNull()) throw validation("article package_id must be null");
        String status = text(body.get("status"), 1, 32);
        String assessment = text(body.get("self_assessment"), 1, 32);
        if (!STATUSES.contains(status) || !ASSESSMENTS.contains(assessment)) throw validation("invalid record state");
        var command = new RecordsService.RecordWrite(number(body.get("expected_revision")), status, assessment,
                text(body.get("note"), 0, 10_000), optionalHttps(body.get("artifact_url")), packageId, kind,
                kind.equals("article") ? bool(body.get("is_read")) : null,
                kind.equals("article") ? bool(body.get("is_favorite")) : null,
                kind.equals("article") ? bool(body.get("is_ignored")) : null,
                kind.equals("question") ? text(body.get("latest_answer_text"), 0, 50_000) : null,
                kind.equals("project") ? optionalHttps(body.get("latest_submission_url")) : null);
        return success(record(service().write(actor, kind, id, command, key, csrf, origin)));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<RecordsDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new RecordsDtos.ErrorEnvelope(new RecordsDtos.ErrorDetail(error.code,
                error.getMessage()), new RecordsDtos.Meta(requestId())));
    }
    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<RecordsDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new RecordsDtos.ErrorEnvelope(new RecordsDtos.ErrorDetail(code, code),
                new RecordsDtos.Meta(requestId())));
    }

    @ExceptionHandler(PrivateRecordWriteService.Rejected.class)
    public ResponseEntity<RecordsDtos.ErrorEnvelope> businessRefusal(PrivateRecordWriteService.Rejected error) {
        String code = error.getMessage();
        int status = switch (code == null ? "" : code) {
            case "object_missing" -> 404;
            case "revision_conflict", "identity_conflict" -> 409;
            case "note_too_long", "answer_too_long" -> 422;
            default -> 503;
        };
        if (status == 503) code = "temporarily_unavailable";
        return failure(new BoundaryFailure(status, code, code));
    }

    private Actor user(String cookie) {
        if (cookie == null || cookie.isBlank()) throw new BoundaryFailure(401, "unauthenticated", "session required");
        IdentityService auth = identity.getIfAvailable();
        if (auth == null) throw unavailable();
        Actor actor = auth.authenticate(cookie);
        if (actor == null || actor.userId() == null || actor.userId().isEmpty()) {
            throw new BoundaryFailure(401, "unauthenticated", "session required");
        }
        if (!Set.of("owner", "user").contains(actor.role())) {
            throw new BoundaryFailure(403, "forbidden", "user role required");
        }
        return actor;
    }
    private RecordsService service() {
        RecordsService value = records.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
    }
    private static JsonNode parse(byte[] raw) {
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > 131_072) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
        JsonNode value;
        try { value = CanonicalJson.parse(raw); }
        catch (IllegalArgumentException error) { throw protocol("invalid JSON"); }
        if (!value.isObject()) throw validation("object body required");
        return value;
    }
    private static String text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw validation("string required");
        String value = node.asText();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw validation("string outside contract bounds");
        return value;
    }
    private static String id(JsonNode node) { return id(text(node, 1, 64)); }
    private static String id(String value) {
        try { return WireValues.id(value, false); }
        catch (IllegalArgumentException error) { throw validation("invalid identity"); }
    }
    private static String optionalHttps(JsonNode node) {
        if (node == null) throw validation("URL field required");
        if (node.isNull()) return null;
        String value = text(node, 1, 2000);
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) { throw validation("invalid URL"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
                uri.getFragment() != null) throw validation("URL must be HTTPS");
        return value;
    }
    private static boolean bool(JsonNode node) {
        if (node == null || !node.isBoolean()) throw validation("boolean required");
        return node.booleanValue();
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid integer"); }
    }
    private static long positive(String raw, long fallback, long max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw validation("invalid positive integer query");
        try { long value = Long.parseLong(raw); if (value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw validation("invalid positive integer query");
    }
    private static RecordsDtos.Record record(RecordsService.Record value) {
        if (value == null) throw unavailable();
        return new RecordsDtos.Record(value.packageId(), value.objectKind(), value.objectId(), value.revision(),
                value.status(), value.selfAssessment(), value.note(), value.artifactUrl(),
                value.latestAnswerText(), value.latestSubmissionUrl(), value.confirmedBodyRevision(),
                time(value.updatedAt()), value.isRead(), value.isFavorite(), value.isIgnored());
    }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }
    private static <T> ResponseEntity<RecordsDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RecordsDtos.Envelope<>(data, new RecordsDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "record service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
