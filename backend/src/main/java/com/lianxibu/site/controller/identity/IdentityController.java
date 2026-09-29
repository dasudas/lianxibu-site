package com.lianxibu.site.controller.identity;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.NodeActionCommand;
import com.lianxibu.site.service.identity.IdentityService.NodeCreateCommand;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Exact HTTP boundary. The service port is intentionally absent until identity use cases are implemented. */
@RestController
public final class IdentityController {
    private static final String SESSION_COOKIE = "lianxibu_session";
    private static final String ANONYMOUS_COOKIE = "lianxibu_anonymous";
    private static final Set<String> PERMISSIONS = Set.of("config:read", "run:read", "run:claim", "run:write",
            "observation:write", "heartbeat:write", "probe:claim", "probe:write");
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<IdentityService> services;

    public IdentityController(ObjectProvider<IdentityService> services) {
        this.services = services;
    }

    @GetMapping(path = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<IdentityDtos.Session>> session(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @CookieValue(name = ANONYMOUS_COOKIE, required = false) String anonymousCookie) {
        return sessionResponse(service().session(sessionCookie, anonymousCookie));
    }

    @PostMapping(path = "/session", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<IdentityDtos.Session>> login(
            @RequestBody(required = false) byte[] body,
            @CookieValue(name = ANONYMOUS_COOKIE, required = false) String anonymousCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin) {
        requireWriteHeaders(csrf, origin);
        JsonNode request = request(body, Set.of("username", "password"));
        var command = new IdentityService.LoginCommand(string(request, "username", 1, 64, false),
                string(request, "password", 1, 1024, false));
        return sessionResponse(service().login(command, anonymousCookie, csrf, origin));
    }

    @DeleteMapping(path = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<IdentityDtos.Session>> logout(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin) {
        Actor actor = authenticate(sessionCookie);
        requireWriteHeaders(csrf, origin);
        return sessionResponse(service().logout(actor, sessionCookie, csrf, origin));
    }

    @GetMapping(path = "/admin/collector-nodes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<IdentityDtos.CollectorNodePage>> nodes(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @RequestParam MultiValueMap<String, String> query) {
        Actor actor = owner(sessionCookie);
        for (String key : query.keySet()) {
            if (!Set.of("page", "page_size").contains(key) || query.get(key).size() != 1) {
                throw protocol("unknown or repeated query parameter");
            }
        }
        long page = pageNumber(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int pageSize = (int) pageNumber(query.getFirst("page_size"), 20, 50);
        var result = service().listNodes(actor, page, pageSize);
        if (result == null || result.items() == null || result.items().size() > 50) {
            throw unavailable();
        }
        var items = result.items().stream().map(IdentityController::node).toList();
        return success(new IdentityDtos.CollectorNodePage(items, result.page(), result.pageSize(), result.total()));
    }

    @PostMapping(path = "/admin/collector-nodes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<IdentityDtos.NodeCredential>> createNode(
            @RequestBody(required = false) byte[] body,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        Actor actor = owner(sessionCookie);
        requireWriteHeaders(csrf, origin);
        idempotencyKey(idempotencyKey);
        JsonNode request = request(body, Set.of("name", "source_ids", "permissions"));
        var command = new NodeCreateCommand(string(request, "name", 1, 100, false),
                strings(request, "source_ids", 1000, 80, true, false),
                strings(request, "permissions", 8, 32, false, true));
        var result = service().createNode(actor, command, idempotencyKey, csrf, origin);
        if (result == null || result.token() == null || result.token().isEmpty()) throw unavailable();
        return success(new IdentityDtos.NodeCredential(node(result.node()), result.token()));
    }

    @PostMapping(path = "/admin/collector-nodes/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IdentityDtos.Envelope<Object>> nodeAction(
            @PathVariable("id") String id,
            @RequestBody(required = false) byte[] body,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        Actor actor = owner(sessionCookie);
        requireWriteHeaders(csrf, origin);
        idempotencyKey(idempotencyKey);
        validIdentity(id, 64);
        JsonNode request = request(body, Set.of("expected_revision", "action", "reason"),
                Set.of("source_ids", "permissions"));
        String action = string(request, "action", 1, 32, false);
        if (!Set.of("rotate_token", "revoke", "set_permissions").contains(action)) {
            throw validation("unknown node action");
        }
        boolean permissionsAction = action.equals("set_permissions");
        if (permissionsAction != request.has("source_ids") || permissionsAction != request.has("permissions")) {
            throw validation("action fields do not match action");
        }
        var command = new NodeActionCommand(integer(request, "expected_revision"), action,
                string(request, "reason", 1, 2048, false),
                permissionsAction ? strings(request, "source_ids", 1000, 80, true, false) : null,
                permissionsAction ? strings(request, "permissions", 8, 32, false, true) : null);
        var result = service().actOnNode(actor, id, command, idempotencyKey, csrf, origin);
        if (result == null || (action.equals("rotate_token") != (result.token() != null))) throw unavailable();
        Object data = result.token() == null ? node(result.node())
                : new IdentityDtos.NodeCredential(node(result.node()), result.token());
        return success(data);
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<IdentityDtos.ErrorEnvelope> failure(BoundaryFailure failure) {
        var body = new IdentityDtos.ErrorEnvelope(new IdentityDtos.ErrorDetail(failure.code, failure.getMessage()),
                new IdentityDtos.Meta(requestId()));
        var response = ResponseEntity.status(failure.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (failure.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(body);
    }

    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<IdentityDtos.ErrorEnvelope> rejected(IdentityService.Rejected rejection) {
        int status = switch (rejection.refusal()) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case REVISION_CONFLICT, IDENTITY_CONFLICT -> 409;
            case RATE_LIMITED -> 429;
            case TEMPORARILY_UNAVAILABLE -> 503;
        };
        String code = rejection.refusal().name().toLowerCase(java.util.Locale.ROOT);
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 429 || status == 503) {
            String retryAfter = rejection.retryAfter();
            response.header(HttpHeaders.RETRY_AFTER,
                    retryAfter != null && retryAfter.matches("[1-9][0-9]{0,5}") ? retryAfter : "1");
        }
        return response.body(new IdentityDtos.ErrorEnvelope(new IdentityDtos.ErrorDetail(code, code),
                new IdentityDtos.Meta(requestId())));
    }

    private IdentityService service() {
        IdentityService service = services.getIfAvailable();
        if (service == null) throw unavailable();
        return service;
    }

    private Actor authenticate(String sessionCookie) {
        if (sessionCookie == null || sessionCookie.isBlank()) throw new BoundaryFailure(401, "unauthenticated", "session required");
        Actor actor = service().authenticate(sessionCookie);
        if (actor == null || actor.userId() == null || actor.userId().isEmpty()) {
            throw new BoundaryFailure(401, "unauthenticated", "session required");
        }
        return actor;
    }

    private Actor owner(String sessionCookie) {
        Actor actor = authenticate(sessionCookie);
        if (!"owner".equals(actor.role())) throw new BoundaryFailure(403, "forbidden", "owner role required");
        return actor;
    }

    private static void requireWriteHeaders(String csrf, String origin) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
    }

    private static void idempotencyKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }

    private static JsonNode request(byte[] body, Set<String> required) {
        return request(body, required, Set.of());
    }

    private static JsonNode request(byte[] body, Set<String> required, Set<String> optional) {
        if (body == null || body.length == 0) throw validation("request body required");
        if (body.length > 262_144) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
        JsonNode node;
        try {
            node = CanonicalJson.parse(body);
        } catch (IllegalArgumentException error) {
            throw protocol("invalid JSON");
        }
        if (!node.isObject()) throw validation("object body required");
        for (var field : node.properties()) {
            if (!required.contains(field.getKey()) && !optional.contains(field.getKey())) {
                throw protocol("unknown request field");
            }
        }
        for (String field : required) if (!node.has(field)) throw validation("required field missing");
        return node;
    }

    private static String string(JsonNode node, String field, int min, int max, boolean identity) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw validation("invalid " + field);
        String text = value.asText();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) throw validation("invalid " + field);
        if (identity) validIdentity(text, max);
        return text;
    }

    private static void validIdentity(String value, int max) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(cp -> cp < 32 || cp == 127)) {
            throw validation("invalid identity");
        }
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0
                || value.longValue() > CanonicalJson.MAX_SAFE_INTEGER) throw validation("invalid " + field);
        return value.longValue();
    }

    private static List<String> strings(JsonNode root, String field, int maxItems, int maxLength,
                                        boolean identity, boolean permission) {
        JsonNode array = root.get(field);
        if (array == null || !array.isArray() || array.size() > maxItems) throw validation("invalid " + field);
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) throw validation("invalid " + field);
            String text = value.asText();
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > maxLength) throw validation("invalid " + field);
            if (identity) validIdentity(text, maxLength);
            if (permission && !PERMISSIONS.contains(text)) throw validation("invalid permission");
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static long pageNumber(String raw, long fallback, long max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw validation("invalid page parameter");
        try {
            long value = Long.parseLong(raw);
            if (value <= max) return value;
        } catch (NumberFormatException ignored) {
            // A number outside the contract range is a validation error.
        }
        throw validation("invalid page parameter");
    }

    private static IdentityDtos.CollectorNode node(IdentityService.CollectorNode value) {
        if (value == null) throw unavailable();
        return new IdentityDtos.CollectorNode(value.id(), value.name(), value.sourceIds(), value.permissions(),
                value.revision(), value.revoked(), value.lastHeartbeatAt() == null ? null
                : MICROS.format(value.lastHeartbeatAt()));
    }

    private ResponseEntity<IdentityDtos.Envelope<IdentityDtos.Session>> sessionResponse(
            IdentityService.SessionOutcome result) {
        if (result == null || result.session() == null || result.session().csrfToken() == null
                || result.session().csrfToken().isEmpty()) throw unavailable();
        var state = result.session();
        if (state.authenticated() != (state.userId() != null && state.role() != null)) throw unavailable();
        var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        cookie(response, SESSION_COOKIE, result.sessionToken(), result.clearSession());
        cookie(response, ANONYMOUS_COOKIE, result.anonymousToken(), result.clearAnonymous());
        return response.body(new IdentityDtos.Envelope<>(new IdentityDtos.Session(state.authenticated(),
                state.userId(), state.role(), state.csrfToken()), new IdentityDtos.Meta(requestId())));
    }

    private static void cookie(ResponseEntity.BodyBuilder response, String name, String value, boolean clear) {
        if (value == null && !clear) return;
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, clear ? "" : value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/");
        if (clear) cookie.maxAge(Duration.ZERO);
        response.header(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    private static <T> ResponseEntity<IdentityDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new IdentityDtos.Envelope<>(data, new IdentityDtos.Meta(requestId())));
    }

    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "identity service unavailable");
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
