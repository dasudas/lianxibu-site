package com.lianxibu.site.controller.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.sources.SourcesService;
import java.net.URI;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Owner-only source/run contract boundary; no persistence or network work occurs here. */
@RestController
public final class SourcesController {
    private static final Set<String> CATEGORIES = Set.of("news_tech", "agent", "skill", "workflow", "model");
    private static final Set<String> SOURCE_ACTIONS = Set.of("probe", "start_trial", "promote_production", "disable");
    private static final Set<String> ITEM_ACTIONS = Set.of("confirm_new", "link_existing", "exclude",
            "resolve_projection", "revalidate", "seal");
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<SourcesService> sources;

    public SourcesController(ObjectProvider<IdentityService> identity, ObjectProvider<SourcesService> sources) {
        this.identity = identity;
        this.sources = sources;
    }

    @GetMapping(path = "/admin/sources", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.SourcePage>> sources(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(cookie);
        pagingQuery(query, Set.of());
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().listSources(actor, page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new SourcesDtos.SourcePage(result.items().stream().map(SourcesController::source).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @GetMapping(path = "/admin/source-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.RunPage>> runs(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(cookie);
        pagingQuery(query, Set.of("source_id", "business_date"));
        String sourceId = query.getFirst("source_id");
        if (sourceId != null) sourceId = sourceId(sourceId);
        LocalDate date = query.getFirst("business_date") == null ? null : date(query.getFirst("business_date"));
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().listRuns(actor, sourceId, date, page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new SourcesDtos.RunPage(result.items().stream().map(SourcesController::run).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @GetMapping(path = "/admin/source-runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.Run>> run(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String id) {
        Actor actor = owner(cookie);
        return success(run(service().run(actor, id(id))));
    }

    @GetMapping(path = "/admin/source-runs/{id}/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.RunItemPage>> items(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String id,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(cookie);
        pagingQuery(query, Set.of());
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        var result = service().listItems(actor, id(id), page, size);
        if (result == null || result.items() == null || result.items().size() > 50) throw unavailable();
        return success(new SourcesDtos.RunItemPage(result.items().stream().map(SourcesController::item).toList(),
                result.page(), result.pageSize(), result.total()));
    }

    @PostMapping(path = "/admin/sources", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.Source>> createSource(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = body(body, Set.of("id", "name", "category", "config"));
        var command = new SourcesService.SourceCreate(sourceId(value.get("id")),
                text(value.get("name"), 1, 200), category(value.get("category")), config(value.get("config")));
        return success(source(service().createSource(actor, command, key, csrf, origin)));
    }

    @PutMapping(path = "/admin/sources/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.Source>> writeSource(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String sourceId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = body(body, Set.of("expected_revision", "name", "category", "config"));
        var command = new SourcesService.SourceWrite(number(value.get("expected_revision")),
                text(value.get("name"), 1, 200), category(value.get("category")), config(value.get("config")));
        return success(source(service().writeSource(actor, sourceId(sourceId), command, key, csrf, origin)));
    }

    @PostMapping(path = "/admin/sources/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.ActionResult>> sourceAction(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String sourceId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = body(body, Set.of("expected_revision", "action", "reason"));
        String action = text(value.get("action"), 1, 32);
        if (!SOURCE_ACTIONS.contains(action)) throw validation("unknown source action");
        var command = new SourcesService.SourceAction(number(value.get("expected_revision")), action,
                text(value.get("reason"), 1, 2048));
        String exactSourceId = sourceId(sourceId);
        return success(sourceActionResult(service().sourceAction(actor, exactSourceId, command, key, csrf, origin),
                exactSourceId, command));
    }

    @PostMapping(path = "/admin/source-runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.Run>> createRun(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = body(body, Set.of("source_id", "business_date", "window_start", "window_end",
                "run_kind", "config_version", "expected_revision", "reason"));
        String kind = text(value.get("run_kind"), 1, 32);
        if (!Set.of("production_qualification", "historical_backfill").contains(kind)) throw validation("invalid run kind");
        Instant start = instant(value.get("window_start"));
        Instant end = instant(value.get("window_end"));
        if (!end.isAfter(start)) throw validation("run window must increase");
        var command = new SourcesService.RunCreate(sourceId(value.get("source_id")),
                businessDate(value.get("business_date")), start, end, kind,
                number(value.get("config_version")), number(value.get("expected_revision")),
                text(value.get("reason"), 1, 2048));
        return success(run(service().createRun(actor, command, key, csrf, origin)));
    }

    @PostMapping(path = "/admin/source-runs/{id}/items/{item_id}/actions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SourcesDtos.Envelope<SourcesDtos.ActionResult>> itemAction(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String runId, @PathVariable("item_id") String itemId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        Actor actor = owner(cookie);
        writeHeaders(csrf, origin, key);
        JsonNode value = body(body, Set.of("expected_revision", "action", "reason"),
                Set.of("entity_id", "decision", "expected_article_revision", "evidence_refs"));
        String action = text(value.get("action"), 1, 32);
        if (!ITEM_ACTIONS.contains(action)) throw validation("unknown item action");
        boolean link = action.equals("link_existing");
        boolean projection = action.equals("resolve_projection");
        if (value.has("entity_id") != link || value.has("decision") != projection ||
                value.has("expected_article_revision") != projection || value.has("evidence_refs") != projection) {
            throw validation("item action fields do not match action");
        }
        String decision = projection ? text(value.get("decision"), 1, 32) : null;
        if (projection && !Set.of("keep_historical", "promote").contains(decision)) {
            throw validation("invalid projection decision");
        }
        var command = new SourcesService.ItemAction(number(value.get("expected_revision")), action,
                text(value.get("reason"), 1, 2048), link ? id(value.get("entity_id")) : null,
                decision, projection ? number(value.get("expected_article_revision")) : null,
                projection ? ids(value.get("evidence_refs"), 1, 100, false) : null);
        return success(action(service().itemAction(actor, id(runId), id(itemId), command, key, csrf, origin)));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<SourcesDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new SourcesDtos.ErrorEnvelope(new SourcesDtos.ErrorDetail(error.code, error.getMessage()),
                new SourcesDtos.Meta(requestId())));
    }

    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<SourcesDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new SourcesDtos.ErrorEnvelope(new SourcesDtos.ErrorDetail(code, code),
                new SourcesDtos.Meta(requestId())));
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

    private SourcesService service() {
        SourcesService service = sources.getIfAvailable();
        if (service == null) throw unavailable();
        return service;
    }

    private static void writeHeaders(String csrf, String origin, String key) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }

    private static void pagingQuery(MultiValueMap<String, String> query, Set<String> extra) {
        for (String field : query.keySet()) {
            if ((!Set.of("page", "page_size").contains(field) && !extra.contains(field)) || query.get(field).size() != 1) {
                throw protocol("unknown or repeated query parameter");
            }
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

    private static JsonNode body(byte[] raw, Set<String> required) { return body(raw, required, Set.of()); }

    private static JsonNode body(byte[] raw, Set<String> required, Set<String> optional) {
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > 524_288) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
        JsonNode node;
        try { node = CanonicalJson.parse(raw); }
        catch (IllegalArgumentException error) { throw protocol("invalid JSON"); }
        if (!node.isObject()) throw validation("object body required");
        for (var field : node.properties()) {
            if (!required.contains(field.getKey()) && !optional.contains(field.getKey())) throw protocol("unknown field");
        }
        for (String field : required) if (!node.has(field)) throw validation("required field missing");
        return node;
    }

    private static SourcesService.SourceConfig config(JsonNode node) {
        if (node == null || !node.isObject()) throw validation("config object required");
        Set<String> required = Set.of("adapter", "entry_urls", "identity_rule", "window_rule", "parser_version",
                "max_items", "max_response_bytes", "allowed_hosts", "valid_until", "auth_secret_ref");
        for (var field : node.properties()) if (!required.contains(field.getKey())) throw protocol("unknown config field");
        for (String field : required) if (!node.has(field)) throw validation("config field missing");
        List<String> urls = strings(node.get("entry_urls"), 1, 100, 2000, false);
        for (String value : urls) {
            URI url;
            try { url = URI.create(value); }
            catch (IllegalArgumentException error) { throw validation("invalid entry URL"); }
            if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null ||
                    (url.getPort() != -1 && url.getPort() != 443) || url.getFragment() != null) {
                throw validation("entry URL must use default-port HTTPS");
            }
        }
        String secret = node.get("auth_secret_ref").isNull() ? null : text(node.get("auth_secret_ref"), 1, 128);
        return new SourcesService.SourceConfig(text(node.get("adapter"), 1, 64), urls,
                text(node.get("identity_rule"), 1, 128), text(node.get("window_rule"), 1, 128),
                text(node.get("parser_version"), 1, 64), bounded(node.get("max_items"), 1, 100_000),
                bounded(node.get("max_response_bytes"), 1, 10_485_760),
                strings(node.get("allowed_hosts"), 1, 100, 253, false), instant(node.get("valid_until")), secret);
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
        catch (IllegalArgumentException error) { throw validation("invalid path identity"); }
    }
    private static String sourceId(JsonNode node) { return sourceId(text(node, 1, 80)); }
    private static String sourceId(String value) {
        try { return WireValues.id(value, true); }
        catch (IllegalArgumentException error) { throw validation("invalid source identity"); }
    }
    private static String category(JsonNode node) {
        String value = text(node, 1, 32);
        if (!CATEGORIES.contains(value)) throw validation("invalid source category");
        return value;
    }
    private static long number(JsonNode node) { return bounded(node, 0, CanonicalJson.MAX_SAFE_INTEGER); }
    private static long bounded(JsonNode node, long min, long max) {
        long value;
        try { value = WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid integer"); }
        if (value < min || value > max) throw validation("integer outside contract bounds");
        return value;
    }
    private static LocalDate businessDate(JsonNode node) {
        try { return WireValues.businessDate(node); }
        catch (IllegalArgumentException error) { throw validation("invalid business date"); }
    }
    private static LocalDate date(String raw) {
        if (raw == null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw validation("invalid business date");
        }
        try { return LocalDate.parse(raw); }
        catch (java.time.DateTimeException error) { throw validation("invalid business date"); }
    }
    private static Instant instant(JsonNode node) {
        try { return WireValues.utcMicroseconds(node); }
        catch (IllegalArgumentException error) { throw validation("invalid UTC microsecond time"); }
    }
    private static List<String> ids(JsonNode node, int min, int max, boolean source) {
        List<String> values = strings(node, min, max, source ? 80 : 64, true);
        for (String value : values) if (source) sourceId(value); else id(value);
        return values;
    }
    private static List<String> strings(JsonNode node, int min, int max, int maxLength, boolean nonControl) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) throw validation("invalid string array");
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            String value = text(element, 1, maxLength);
            if (nonControl && value.codePoints().anyMatch(Character::isISOControl)) throw validation("control in identity");
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static SourcesDtos.SourceConfig config(SourcesService.SourceConfig value) {
        if (value == null) return null;
        return new SourcesDtos.SourceConfig(value.adapter(), value.entryUrls(), value.identityRule(),
                value.windowRule(), value.parserVersion(), value.maxItems(), value.maxResponseBytes(),
                value.allowedHosts(), time(value.validUntil()), value.authSecretRef());
    }
    private static SourcesDtos.Source source(SourcesService.Source value) {
        if (value == null) throw unavailable();
        return new SourcesDtos.Source(value.id(), value.name(), value.category(), value.revision(),
                value.configRevision(), value.activationStage(), config(value.draftConfig()),
                config(value.effectiveConfig()), time(value.configValidUntil()));
    }
    private static SourcesDtos.MissingInput missing(SourcesService.MissingInput value) {
        return new SourcesDtos.MissingInput(value.kind(), value.id(), date(value.businessDate()), value.reason());
    }
    private static SourcesDtos.Coverage coverage(SourcesService.Coverage value) {
        if (value == null) throw unavailable();
        return new SourcesDtos.Coverage(value.state(), value.missingInputs().stream().map(SourcesController::missing).toList(),
                value.missingDates().stream().map(SourcesController::date).toList(),
                value.noUpdateDates().stream().map(SourcesController::date).toList());
    }
    private static SourcesDtos.Run run(SourcesService.Run value) {
        if (value == null) throw unavailable();
        return new SourcesDtos.Run(value.runId(), value.sourceId(), value.runKind(), date(value.businessDate()),
                value.generation(), time(value.windowStart()), time(value.windowEnd()), value.fetchMode(),
                value.collectionReason(), value.configVersion(), value.runRevision(), value.deliveryState(),
                value.fetchState(), value.analysisState(), coverage(value.coverage()), value.expectedCount(),
                value.acceptedCount(), value.excludedCount(), value.pendingCount(), time(value.barrierClosedAt()),
                value.lateResultsAvailable(), value.resultBusinessHash(), value.manifestHash());
    }
    private static SourcesDtos.RunItem item(SourcesService.RunItem value) {
        if (value == null) throw unavailable();
        return new SourcesDtos.RunItem(value.id(), value.runId(), value.memberId(), value.sourceItemId(),
                value.category(), value.entityId(), value.revision(), value.terminalState(), value.state(),
                value.isNew(), value.highValue(), value.projectionDecision(), value.failureReason());
    }
    private static SourcesDtos.ResourceRef ref(SourcesService.ResourceRef value) {
        if (value == null) throw unavailable();
        return new SourcesDtos.ResourceRef(value.kind(), value.id(), value.packageId());
    }
    private static SourcesDtos.ActionResult sourceActionResult(SourcesService.ActionResult value,
                                                               String sourceId, SourcesService.SourceAction command) {
        try {
            if (value == null || value.resultResourceRef() == null || value.affected() == null
                    || !value.affected().isEmpty() || value.cutoff() != null || value.coverage() != null
                    || value.nextCursor() != null || value.resultRevision() < 0
                    || value.resultRevision() > CanonicalJson.MAX_SAFE_INTEGER) throw unavailable();
            WireValues.id(value.operationRef(), false);
            var root = value.resultResourceRef();
            if (root.packageId() != null) throw unavailable();
            if ("probe".equals(command.action())) {
                if (!"source_run".equals(root.kind()) || value.generation() == null
                        || value.generation() < 1 || value.generation() > CanonicalJson.MAX_SAFE_INTEGER) {
                    throw unavailable();
                }
                WireValues.id(root.id(), false);
            } else {
                WireValues.id(root.id(), true);
                long expected = command.expectedRevision();
                boolean revisionMatches = value.resultRevision() == expected + 1
                        || "disable".equals(command.action()) && value.resultRevision() == expected;
                if (!"source".equals(root.kind()) || !sourceId.equals(root.id())
                        || value.generation() != null || !revisionMatches) throw unavailable();
            }
            return action(value);
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
    }

    private static SourcesDtos.ActionResult action(SourcesService.ActionResult value) {
        if (value == null || value.affected() == null) throw unavailable();
        return new SourcesDtos.ActionResult(value.operationRef(), ref(value.resultResourceRef()),
                value.resultRevision(), value.generation(), time(value.cutoff()),
                value.coverage() == null ? null : coverage(value.coverage()),
                value.affected().stream().map(SourcesController::ref).toList(), value.nextCursor());
    }
    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }
    private static <T> ResponseEntity<SourcesDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SourcesDtos.Envelope<>(data, new SourcesDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "source service unavailable");
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
