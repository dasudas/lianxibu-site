package com.lianxibu.site.controller.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.articles.ArticlesService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
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

/** Contract-only HTTP boundary; business authorization and persistence belong to the service. */
@RestController
public final class ArticlesController {
    private static final Set<String> CATEGORIES = Set.of("news_tech", "agent", "skill", "workflow", "model");
    private static final Set<String> RECORD_STATUSES = Set.of("not_started", "planned", "learning", "completed");
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<ArticlesService> articles;

    public ArticlesController(ObjectProvider<IdentityService> identity, ObjectProvider<ArticlesService> articles) {
        this.identity = identity;
        this.articles = articles;
    }

    @GetMapping(path = "/private/articles", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArticlesDtos.Envelope<ArticlesDtos.ArticlePage>> articles(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = authenticated(cookie, false);
        query(query, Set.of("source_id", "q", "status"));
        String sourceId = query.getFirst("source_id");
        if (sourceId != null) sourceId = sourceId(sourceId);
        String search = optionalText(query.getFirst("q"), 256);
        String status = query.getFirst("status");
        if (status != null && !RECORD_STATUSES.contains(status)) throw validation("invalid record status");
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().listArticles(actor, sourceId, search, status, page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new ArticlesDtos.ArticlePage(result.items().stream().map(ArticlesController::article).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @GetMapping(path = "/private/articles/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArticlesDtos.Envelope<ArticlesDtos.Article>> article(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String articleId) {
        Actor actor = authenticated(cookie, false);
        return success(article(service().article(actor, id(articleId))));
    }

    @GetMapping(path = "/admin/entities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArticlesDtos.Envelope<ArticlesDtos.EntityPage>> entities(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = authenticated(cookie, true);
        query(query, Set.of("category", "q"));
        String category = query.getFirst("category");
        if (category != null && !CATEGORIES.contains(category)) throw validation("invalid category");
        String search = optionalText(query.getFirst("q"), 256);
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().listEntities(actor, category, search, page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new ArticlesDtos.EntityPage(result.items().stream().map(ArticlesController::entity).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @GetMapping(path = "/admin/entities/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArticlesDtos.Envelope<ArticlesDtos.Entity>> entity(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String entityId) {
        Actor actor = authenticated(cookie, true);
        return success(entity(service().entity(actor, id(entityId))));
    }

    @PostMapping(path = "/admin/entities/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ArticlesDtos.Envelope<ArticlesDtos.ActionResult>> actOnEntity(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String entityId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = authenticated(cookie, true);
        writeHeaders(csrf, origin, key);
        JsonNode body = object(raw, Set.of("expected_revision", "action", "reason", "assignments"));
        if (!"correct_assignments".equals(text(body.get("action"), 1, 32))) {
            throw validation("invalid entity action");
        }
        var command = new ArticlesService.EntityAction(number(body.get("expected_revision")),
                "correct_assignments", text(body.get("reason"), 1, 2048), assignments(body.get("assignments")));
        return success(action(service().actOnEntity(actor, id(entityId), command, key, csrf, origin)));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<ArticlesDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new ArticlesDtos.ErrorEnvelope(new ArticlesDtos.ErrorDetail(error.code,
                error.getMessage()), new ArticlesDtos.Meta(requestId())));
    }

    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<ArticlesDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new ArticlesDtos.ErrorEnvelope(new ArticlesDtos.ErrorDetail(code, code),
                new ArticlesDtos.Meta(requestId())));
    }

    private Actor authenticated(String cookie, boolean ownerOnly) {
        if (cookie == null || cookie.isBlank()) throw new BoundaryFailure(401, "unauthenticated", "session required");
        IdentityService auth = identity.getIfAvailable();
        if (auth == null) throw unavailable();
        Actor actor = auth.authenticate(cookie);
        if (actor == null || actor.userId() == null || actor.userId().isEmpty()) {
            throw new BoundaryFailure(401, "unauthenticated", "session required");
        }
        if (!Set.of("owner", "user").contains(actor.role()) || (ownerOnly && !"owner".equals(actor.role()))) {
            throw new BoundaryFailure(403, "forbidden", "insufficient role");
        }
        return actor;
    }

    private ArticlesService service() {
        ArticlesService value = articles.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
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

    private static String optionalText(String raw, int max) {
        if (raw == null) return null;
        int length = raw.codePointCount(0, raw.length());
        if (length < 1 || length > max) throw validation("query text outside contract bounds");
        return raw;
    }

    private static void writeHeaders(String csrf, String origin, String key) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }

    private static JsonNode object(byte[] raw, Set<String> fields) {
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > 524_288) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
        JsonNode node;
        try { node = CanonicalJson.parse(raw); }
        catch (IllegalArgumentException error) { throw protocol("invalid JSON"); }
        if (!node.isObject()) throw validation("object body required");
        for (var field : node.properties()) if (!fields.contains(field.getKey())) throw protocol("unknown field");
        for (String field : fields) if (!node.has(field)) throw validation("required field missing");
        return node;
    }

    private static List<ArticlesService.Assignment> assignments(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 1 || node.size() > 100) {
            throw validation("invalid assignments");
        }
        List<ArticlesService.Assignment> result = new ArrayList<>();
        Set<String> fields = Set.of("item_id", "old_entity_id", "new_entity_id", "expected_item_revision");
        for (JsonNode entry : node) {
            if (!entry.isObject()) throw validation("assignment object required");
            for (var field : entry.properties()) if (!fields.contains(field.getKey())) throw protocol("unknown assignment field");
            for (String field : fields) if (!entry.has(field)) throw validation("assignment field missing");
            result.add(new ArticlesService.Assignment(id(entry.get("item_id")), id(entry.get("old_entity_id")),
                    id(entry.get("new_entity_id")), number(entry.get("expected_item_revision"))));
        }
        return List.copyOf(result);
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
    private static String sourceId(String value) {
        try { return WireValues.id(value, true); }
        catch (IllegalArgumentException error) { throw validation("invalid source identity"); }
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid revision"); }
    }

    private static ArticlesDtos.Record record(ArticlesService.Record value) {
        if (value == null) return null;
        return new ArticlesDtos.Record(value.packageId(), value.objectKind(), value.objectId(), value.revision(),
                value.status(), value.selfAssessment(), value.note(), value.artifactUrl(), value.latestAnswerText(),
                value.latestSubmissionUrl(), value.confirmedBodyRevision(), time(value.updatedAt()),
                value.isRead(), value.isFavorite(), value.isIgnored());
    }

    private static ArticlesDtos.Article article(ArticlesService.Article value) {
        if (value == null) throw unavailable();
        return new ArticlesDtos.Article(value.id(), value.title(), value.summary(), value.sourceId(),
                value.sourceName(), value.sourceUrl(), time(value.publishedAt()), time(value.observedAt()),
                value.topic(), value.region(), value.contentStatus(), value.revision(), record(value.record()));
    }

    private static ArticlesDtos.Entity entity(ArticlesService.Entity value) {
        if (value == null) throw unavailable();
        return new ArticlesDtos.Entity(value.id(), value.category(), value.title(), value.summary(),
                time(value.firstRecordedAt()), date(value.firstSeenBusinessDate()), time(value.lastSeenAt()),
                value.occurrenceCount(), value.repeatCount(), value.analysisState(), value.revision());
    }

    private static ArticlesDtos.ResourceRef ref(ArticlesService.ResourceRef value) {
        if (value == null) throw unavailable();
        return new ArticlesDtos.ResourceRef(value.kind(), value.id(), value.packageId());
    }

    private static ArticlesDtos.Coverage coverage(ArticlesService.Coverage value) {
        if (value == null || value.missingInputs() == null || value.missingDates() == null ||
                value.noUpdateDates() == null) throw unavailable();
        return new ArticlesDtos.Coverage(value.state(), value.missingInputs().stream()
                .map(v -> new ArticlesDtos.MissingInput(v.kind(), v.id(), date(v.businessDate()), v.reason()))
                .toList(), value.missingDates().stream().map(ArticlesController::date).toList(),
                value.noUpdateDates().stream().map(ArticlesController::date).toList());
    }

    private static ArticlesDtos.ActionResult action(ArticlesService.ActionResult value) {
        if (value == null || value.affected() == null) throw unavailable();
        return new ArticlesDtos.ActionResult(value.operationRef(), ref(value.resultResourceRef()),
                value.resultRevision(), value.generation(), time(value.cutoff()),
                value.coverage() == null ? null : coverage(value.coverage()),
                value.affected().stream().map(ArticlesController::ref).toList(), value.nextCursor());
    }

    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }
    private static <T> ResponseEntity<ArticlesDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ArticlesDtos.Envelope<>(data, new ArticlesDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "article service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
