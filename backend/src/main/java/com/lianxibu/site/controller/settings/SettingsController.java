package com.lianxibu.site.controller.settings;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.settings.SettingsService;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Owner-only preferences boundary; fixed operational schedule is not writable. */
@RestController
public final class SettingsController {
    private static final String DECIMAL = "-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?";
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<SettingsService> settings;

    public SettingsController(ObjectProvider<IdentityService> identity, ObjectProvider<SettingsService> settings) {
        this.identity = identity; this.settings = settings;
    }

    @GetMapping(path = "/admin/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SettingsDtos.Envelope<SettingsDtos.Preferences>> get(
            @CookieValue(name = "lianxibu_session", required = false) String cookie) {
        Actor actor = owner(cookie);
        return success(preferences(service().get(actor)));
    }

    @PutMapping(path = "/admin/preferences", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SettingsDtos.Envelope<SettingsDtos.Preferences>> write(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie);
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
        JsonNode value = parse(raw);
        fields(value, Set.of("expected_revision", "follow_keywords", "blocked_keywords", "ai_paused",
                "default_package_id", "operational_limits"), Set.of("operational_change_reason"));
        var command = new SettingsService.PreferencesWrite(number(value.get("expected_revision")),
                keywords(value.get("follow_keywords")), keywords(value.get("blocked_keywords")),
                bool(value.get("ai_paused")),
                value.get("default_package_id").isNull() ? null : id(value.get("default_package_id")),
                rules(value.get("operational_limits")), value.has("operational_change_reason")
                        ? text(value.get("operational_change_reason"), 1, 2048) : null);
        return success(preferences(service().write(actor, command, key, csrf, origin)));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<SettingsDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new SettingsDtos.ErrorEnvelope(new SettingsDtos.ErrorDetail(error.code,
                error.getMessage()), new SettingsDtos.Meta(requestId())));
    }
    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<SettingsDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new SettingsDtos.ErrorEnvelope(new SettingsDtos.ErrorDetail(code, code),
                new SettingsDtos.Meta(requestId())));
    }

    @ExceptionHandler(SettingsUpdateService.Rejected.class)
    public ResponseEntity<SettingsDtos.ErrorEnvelope> businessRefusal(SettingsUpdateService.Rejected error) {
        String code = error.getMessage() == null ? "temporarily_unavailable" : error.getMessage();
        int status = switch (code) {
            case "revision_conflict", "identity_conflict" -> 409;
            case "default_package_unavailable" -> 422;
            default -> 503;
        };
        if (!Set.of("revision_conflict", "identity_conflict", "default_package_unavailable",
                "settings_mapping_required", "settings_unavailable").contains(code)) code = "temporarily_unavailable";
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new SettingsDtos.ErrorEnvelope(new SettingsDtos.ErrorDetail(code, code), new SettingsDtos.Meta(requestId())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<SettingsDtos.ErrorEnvelope> invalidCommand(IllegalArgumentException error) {
        return ResponseEntity.unprocessableContent().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SettingsDtos.ErrorEnvelope(new SettingsDtos.ErrorDetail("validation", "invalid preferences"), new SettingsDtos.Meta(requestId())));
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
    private SettingsService service() {
        SettingsService value = settings.getIfAvailable();
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
    private static void fields(JsonNode node, Set<String> required) {
        fields(node, required, Set.of());
    }
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw validation("object required");
        for (var field : node.properties()) if (!required.contains(field.getKey())
                && !optional.contains(field.getKey())) throw protocol("unknown field");
        for (String field : required) if (!node.has(field)) throw validation("required field missing");
    }
    private static List<String> keywords(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 100) throw validation("invalid keywords");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(text(item, 1, 128));
        return List.copyOf(values);
    }
    private static List<SettingsService.OperationalRule> rules(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 20) throw validation("invalid operational limits");
        List<SettingsService.OperationalRule> values = new ArrayList<>();
        Set<String> fields = Set.of("rule_id", "warning_threshold", "critical_threshold",
                "recovery_threshold", "notification_secret_ref", "notification_configured");
        for (JsonNode item : node) {
            fields(item, fields, Set.of("rule_parameters"));
            values.add(new SettingsService.OperationalRule(text(item.get("rule_id"), 1, 64),
                    decimal(item.get("warning_threshold")), decimal(item.get("critical_threshold")),
                    decimal(item.get("recovery_threshold")),
                    item.get("notification_secret_ref").isNull() ? null
                            : text(item.get("notification_secret_ref"), 1, 128),
                    bool(item.get("notification_configured")),
                    item.has("rule_parameters") ? parameters(item.get("rule_parameters")) : null));
        }
        return List.copyOf(values);
    }
    private static Map<String, String> parameters(JsonNode node) {
        if (node == null || !node.isObject() || node.size() > 32) throw validation("invalid rule parameters");
        Map<String, String> result = new LinkedHashMap<>();
        for (var field : node.properties()) {
            String name = field.getKey();
            if (name.length() > 64 || !name.matches("[a-z][a-z0-9_]*")) throw validation("invalid parameter name");
            String value = text(field.getValue(), 1, 30);
            if (!value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")) throw validation("invalid parameter decimal");
            result.put(name, value);
        }
        return Map.copyOf(result);
    }
    private static String decimal(JsonNode node) {
        String value = text(node, 1, 30);
        if (!value.matches(DECIMAL)) throw validation("invalid threshold decimal");
        return value;
    }
    private static String text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw validation("string required");
        String value = node.asText();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) throw validation("string outside contract bounds");
        return value;
    }
    private static String id(JsonNode node) {
        try { return WireValues.id(node, false); }
        catch (IllegalArgumentException error) { throw validation("invalid package identity"); }
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid revision"); }
    }
    private static boolean bool(JsonNode node) {
        if (node == null || !node.isBoolean()) throw validation("boolean required");
        return node.booleanValue();
    }
    private static SettingsDtos.Preferences preferences(SettingsService.Preferences value) {
        if (value == null || value.followKeywords() == null || value.blockedKeywords() == null ||
                value.operationalLimits() == null) throw unavailable();
        return new SettingsDtos.Preferences(value.revision(), value.followKeywords(), value.blockedKeywords(),
                value.aiPaused(), value.defaultPackageId(), "deepseek-flash", "Asia/Shanghai", "00:10",
                "05:00", "01:00", "07:00", value.operationalLimits().stream().map(v ->
                        new SettingsDtos.OperationalRule(v.ruleId(), v.warningThreshold(), v.criticalThreshold(),
                                v.recoveryThreshold(), v.notificationSecretRef(), v.notificationConfigured(), v.ruleParameters()))
                        .toList());
    }
    private static <T> ResponseEntity<SettingsDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SettingsDtos.Envelope<>(data, new SettingsDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "settings service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
