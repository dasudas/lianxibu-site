package com.lianxibu.site.controller.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Public published reads and owner-only content commands. No service bean means no invented success. */
@RestController
public final class ContentController {
    private static final Set<String> KINDS = Set.of("report", "agent", "skill", "workflow", "model");
    private static final Set<String> INTERPRETATION_KINDS = Set.of("agent", "skill", "workflow", "model");
    private static final Set<String> REPORT_TYPES = Set.of("daily", "weekly", "monthly");
    private static final Set<String> CONTENT_ACTIONS = Set.of("approve", "reject", "publish", "withdraw",
            "supplement_generate");
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<ContentService> content;

    public ContentController(ObjectProvider<IdentityService> identity, ObjectProvider<ContentService> content) {
        this.identity = identity;
        this.content = content;
    }

    @GetMapping(path = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<ContentDtos.Page<Map<String, Object>>>> reports(
            @RequestParam MultiValueMap<String, String> query) {
        query(query, Set.of("type"));
        String type = query.getFirst("type");
        if (type != null && !REPORT_TYPES.contains(type)) throw validation("invalid report type");
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        return success(summaryPage(service().reports(type, page, size), "report"));
    }

    @GetMapping(path = "/reports/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> report(@PathVariable("id") String id) {
        return success(detail(service().report(id(id)), "report"));
    }

    @GetMapping(path = "/interpretations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<ContentDtos.Page<Map<String, Object>>>> interpretations(
            @RequestParam MultiValueMap<String, String> query) {
        query(query, Set.of("kind"));
        String kind = query.getFirst("kind");
        if (kind != null && !INTERPRETATION_KINDS.contains(kind)) throw validation("invalid interpretation kind");
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        return success(summaryPage(service().interpretations(kind, page, size), "interpretation"));
    }

    @GetMapping(path = "/interpretations/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> interpretation(@PathVariable("id") String id) {
        return success(detail(service().interpretation(id(id)), "interpretation"));
    }

    @GetMapping(path = "/admin/content/{kind}/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> adminContent(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String id) {
        Actor actor = owner(cookie);
        return success(detail(service().adminContent(actor, kind(kind), id(id)), kind));
    }

    @PutMapping(path = "/admin/content/{kind}/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> write(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        String contentKind = kind(kind);
        JsonNode value = parse(raw);
        ContentService.ContentWrite command = contentWrite(contentKind, value);
        return success(detail(service().write(actor, contentKind, id(id), command, key, csrf, origin), contentKind));
    }

    @PostMapping(path = "/admin/content/{kind}/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> action(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = parse(raw);
        String action = text(value.get("action"), 1, 32);
        if (!CONTENT_ACTIONS.contains(action)) throw validation("invalid content action");
        fields(value, Set.of("expected_revision", "action", "reason"),
                action.equals("withdraw") ? Set.of("withdrawal_reason") : Set.of());
        String withdrawal = action.equals("withdraw") ? text(value.get("withdrawal_reason"), 1, 64) : null;
        if (withdrawal != null && !Set.of("display_adjustment", "conclusion_error").contains(withdrawal)) {
            throw validation("invalid withdrawal reason");
        }
        var command = new ContentService.ContentAction(number(value.get("expected_revision")), action,
                text(value.get("reason"), 1, 2048), withdrawal);
        return success(actionResult(service().action(actor, kind(kind), id(id), command, key, csrf, origin)));
    }

    @GetMapping(path = "/admin/content/{kind}/{id}/referenced-versions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<ContentDtos.Page<Map<String, Object>>>> referencedVersions(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(cookie);
        query(query, Set.of());
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().referencedVersions(actor, kind(kind), id(id), page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        try {
            return success(new ContentDtos.Page<>(result.items().stream().map(ContentDtos::version).toList(),
                    result.page(), result.pageSize(), result.total()));
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }

    @PostMapping(path = "/admin/content/{kind}/{id}/version-actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> versionAction(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("kind") String kind, @PathVariable("id") String id,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = parse(raw);
        String action = text(value.get("action"), 1, 32);
        if (!Set.of("invalidate_conclusion", "reverse_invalidation").contains(action)) {
            throw validation("invalid version action");
        }
        fields(value, Set.of("expected_validity_revision", "action", "reason", "target"),
                action.equals("reverse_invalidation") ? Set.of("invalidation_id") : Set.of());
        var command = new ContentService.VersionAction(number(value.get("expected_validity_revision")), action,
                text(value.get("reason"), 1, 2048), versionKey(value.get("target")),
                action.equals("reverse_invalidation") ? id(value.get("invalidation_id")) : null);
        if (!command.target().contentKind().equals(kind(kind)) || !command.target().contentId().equals(id(id))) {
            throw new BoundaryFailure(422, "target_mismatch", "version target does not match path");
        }
        return success(actionResult(service().versionAction(actor, command.target().contentKind(),
                command.target().contentId(), command, key, csrf, origin)));
    }

    @PostMapping(path = "/admin/reports/generation-intents", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContentDtos.Envelope<Map<String, Object>>> generationIntent(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        var command = generationIntent(parse(raw));
        try { return success(ContentDtos.job(service().generationIntent(actor, command, key, csrf, origin))); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<ContentDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(error.code,
                error.getMessage()), new ContentDtos.Meta(requestId())));
    }

    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<ContentDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(code, code),
                new ContentDtos.Meta(requestId())));
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
    private ContentService service() {
        ContentService value = content.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
    }
    private static ContentDtos.Page<Map<String, Object>> summaryPage(ContentService.ContentPage result, String expected) {
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        try {
            for (var value : result.items()) validateKind(value.kind(), expected);
            return new ContentDtos.Page<>(result.items().stream().map(ContentDtos::summary).toList(),
                    result.page(), result.pageSize(), result.total());
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static Map<String, Object> detail(ContentService.ContentView value, String expected) {
        if (value == null) throw unavailable();
        try { validateKind(value.kind(), expected); return ContentDtos.detail(value); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static void validateKind(String actual, String expected) {
        if (actual == null || (!actual.equals(expected) &&
                !(expected.equals("interpretation") && INTERPRETATION_KINDS.contains(actual)))) {
            throw new IllegalArgumentException("wrong content kind");
        }
    }
    private static Map<String, Object> actionResult(ContentService.ActionResult value) {
        try { return ContentDtos.action(value); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }

    private static ContentService.ContentWrite contentWrite(String kind, JsonNode value) {
        Set<String> common = Set.of("expected_revision", "title", "sections", "citation_ids");
        Set<String> branch = "report".equals(kind)
                ? Set.of("type", "period_start", "period_end", "timezone", "learning_task")
                : Set.of("category", "entity_id");
        fields(value, common, branch);
        String title = text(value.get("title"), 1, 512);
        List<ContentService.Section> sections = sections(value.get("sections"));
        List<String> citations = ids(value.get("citation_ids"), 0, 1000);
        ContentService.ContentBody body;
        if ("report".equals(kind)) {
            String type = text(value.get("type"), 1, 32);
            if (!REPORT_TYPES.contains(type)) throw validation("invalid report type");
            Instant start = instant(value.get("period_start"));
            Instant end = instant(value.get("period_end"));
            if (!end.isAfter(start)) throw validation("invalid report period");
            if (!"Asia/Shanghai".equals(text(value.get("timezone"), 1, 32))) {
                throw validation("invalid timezone");
            }
            JsonNode task = value.get("learning_task");
            body = new ContentService.ReportBody(title, sections, citations, type, start, end,
                    "Asia/Shanghai", task.isNull() ? null : learningTask(task));
        } else {
            String category = text(value.get("category"), 1, 32);
            if (!INTERPRETATION_KINDS.contains(category) || !category.equals(kind)) {
                throw validation("invalid interpretation category");
            }
            body = new ContentService.InterpretationBody(title, sections, citations,
                    category, id(value.get("entity_id")));
        }
        return new ContentService.ContentWrite(number(value.get("expected_revision")), body);
    }

    private static List<ContentService.Section> sections(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) throw validation("invalid sections");
        List<ContentService.Section> result = new ArrayList<>();
        for (JsonNode section : node) {
            fields(section, Set.of("heading", "text", "citation_ids"), Set.of());
            result.add(new ContentService.Section(text(section.get("heading"), 1, 512),
                    text(section.get("text"), 0, 200_000), ids(section.get("citation_ids"), 0, 1000)));
        }
        return List.copyOf(result);
    }
    private static ContentService.LearningTask learningTask(JsonNode node) {
        fields(node, Set.of("package_id", "object_kind", "object_id", "title", "reason"), Set.of());
        String kind = text(node.get("object_kind"), 1, 32);
        if (!Set.of("node", "question", "project").contains(kind)) throw validation("invalid learning object kind");
        return new ContentService.LearningTask(id(node.get("package_id")), kind, id(node.get("object_id")),
                text(node.get("title"), 1, 512), text(node.get("reason"), 1, 2048));
    }
    private static ContentService.ContentVersionKey versionKey(JsonNode node) {
        fields(node, Set.of("kind", "content_kind", "content_id", "revision", "body_hash", "input_job_id"),
                Set.of());
        if (!"content_version".equals(text(node.get("kind"), 1, 32))) throw validation("invalid version key");
        String kind = kind(text(node.get("content_kind"), 1, 32));
        String hash;
        try { hash = WireValues.sha256(node.get("body_hash")); }
        catch (IllegalArgumentException error) { throw validation("invalid body hash"); }
        return new ContentService.ContentVersionKey("content_version", kind, id(node.get("content_id")),
                number(node.get("revision")), hash,
                node.get("input_job_id").isNull() ? null : id(node.get("input_job_id")));
    }
    private static ContentService.GenerationIntentWrite generationIntent(JsonNode value) {
        if (value == null || !value.isObject()) throw validation("object body required");
        String mode = text(value.get("mode"), 1, 32);
        if (!Set.of("initial", "supplement").contains(mode)) throw validation("invalid generation mode");
        fields(value, Set.of("kind", "period_start", "period_end", "timezone", "mode", "reason"),
                mode.equals("supplement") ? Set.of("intent_revision") : Set.of());
        String kind = text(value.get("kind"), 1, 32);
        if (!REPORT_TYPES.contains(kind)) throw validation("invalid report kind");
        Instant start = instant(value.get("period_start"));
        Instant end = instant(value.get("period_end"));
        if (!end.isAfter(start)) throw validation("invalid report period");
        if (!"Asia/Shanghai".equals(text(value.get("timezone"), 1, 32))) throw validation("invalid timezone");
        return new ContentService.GenerationIntentWrite(kind, start, end, "Asia/Shanghai", mode,
                mode.equals("supplement") ? number(value.get("intent_revision")) : null,
                text(value.get("reason"), 1, 2048));
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
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw validation("object required");
        for (var field : node.properties()) {
            if (!required.contains(field.getKey()) && !optional.contains(field.getKey())) {
                throw protocol("unknown field");
            }
        }
        for (String field : required) if (!node.has(field)) throw validation("required field missing");
        for (String field : optional) if (!node.has(field)) throw validation("action field missing");
    }
    private static List<String> ids(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            throw validation("invalid identity array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(id(item));
        return List.copyOf(result);
    }
    private static String id(JsonNode node) { return id(text(node, 1, 64)); }
    private static String id(String value) {
        try { return WireValues.id(value, false); }
        catch (IllegalArgumentException error) { throw validation("invalid identity"); }
    }
    private static String kind(String value) {
        if (!KINDS.contains(value)) throw validation("invalid content kind");
        return value;
    }
    private static String text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw validation("string required");
        String value = node.asText();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw validation("string outside contract bounds");
        return value;
    }
    private static Instant instant(JsonNode node) {
        try { return WireValues.utcMicroseconds(node); }
        catch (IllegalArgumentException error) { throw validation("invalid UTC microsecond time"); }
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid integer"); }
    }
    private static void query(MultiValueMap<String, String> values, Set<String> extra) {
        for (String field : values.keySet()) {
            if ((!Set.of("page", "page_size").contains(field) && !extra.contains(field)) ||
                    values.get(field).size() != 1) throw protocol("unknown or repeated query parameter");
        }
    }
    private static long positive(String raw, long fallback, long max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw validation("invalid positive integer query");
        try {
            long value = Long.parseLong(raw);
            if (value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw validation("invalid positive integer query");
    }
    private static void writeHeaders(String csrf, String origin, String key) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }
    private static <T> ResponseEntity<ContentDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ContentDtos.Envelope<>(data, new ContentDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "content service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
