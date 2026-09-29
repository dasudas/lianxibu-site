package com.lianxibu.site.controller.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.learning.LearningService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

/** Learning contract boundary: public published reads and owner-only imports/review. */
@RestController
public final class LearningController {
    private static final Set<String> DIFFICULTIES = Set.of("beginner", "intermediate", "advanced");
    private static final Set<String> QUESTION_KINDS = Set.of("concept", "principle", "code", "scenario",
            "system_design");
    private static final Set<String> RESOLVE_KINDS = Set.of("route", "node", "question", "project");
    private static final Set<String> ACTIONS = Set.of("approve", "reject", "publish", "withdraw");
    private final ObjectProvider<IdentityService> identity;
    private final ObjectProvider<LearningService> learning;

    public LearningController(ObjectProvider<IdentityService> identity, ObjectProvider<LearningService> learning) {
        this.identity = identity; this.learning = learning;
    }

    @GetMapping(path = "/learning/packages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> packages(
            @RequestParam MultiValueMap<String, String> query) {
        query(query, Set.of());
        long page = positive(query.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER);
        int size = (int) positive(query.getFirst("page_size"), 20, 50);
        return success(page(service().packages(page, size), LearningDtos::packageSummary));
    }
    @GetMapping(path = "/learning/packages/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> packageSummary(@PathVariable("id") String id) {
        return success(project(service().packageSummary(id(id)), LearningDtos::packageSummary));
    }

    @GetMapping(path = "/learning/packages/{id}/routes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> routes(
            @PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        return list(id, query, "routes", LearningDtos::route);
    }
    @GetMapping(path = "/learning/packages/{id}/modules", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> modules(
            @PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        return list(id, query, "modules", LearningDtos::module);
    }
    @GetMapping(path = "/learning/packages/{id}/nodes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> nodes(
            @PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        return list(id, query, "nodes", LearningDtos::node);
    }
    @GetMapping(path = "/learning/packages/{id}/questions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> questions(
            @PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        return list(id, query, "questions", LearningDtos::question);
    }
    @GetMapping(path = "/learning/packages/{id}/projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> projects(
            @PathVariable("id") String id, @RequestParam MultiValueMap<String, String> query) {
        return list(id, query, "projects", LearningDtos::project);
    }

    @GetMapping(path = "/learning/packages/{id}/nodes/{node_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> node(@PathVariable("id") String packageId,
            @PathVariable("node_id") String nodeId) {
        return success(project(service().node(id(packageId), id(nodeId)), LearningDtos::node));
    }
    @GetMapping(path = "/learning/packages/{id}/questions/{question_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> question(@PathVariable("id") String packageId,
            @PathVariable("question_id") String questionId) {
        return success(project(service().question(id(packageId), id(questionId)), LearningDtos::question));
    }
    @GetMapping(path = "/learning/packages/{id}/projects/{project_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> project(@PathVariable("id") String packageId,
            @PathVariable("project_id") String projectId) {
        return success(project(service().project(id(packageId), id(projectId)), LearningDtos::project));
    }
    @GetMapping(path = "/learning/packages/{id}/graph", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> graph(@PathVariable("id") String packageId,
            @RequestParam MultiValueMap<String, String> query) {
        query(query, Set.of("module_id", "cursor"), false);
        String moduleId = query.getFirst("module_id");
        if (moduleId != null) moduleId = id(moduleId);
        String cursor = optionalText(query.getFirst("cursor"), 512);
        return success(project(service().graph(id(packageId), moduleId, cursor), LearningDtos::graph));
    }
    @GetMapping(path = "/learning/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> resolve(
            @RequestParam MultiValueMap<String, String> query) {
        query(query, Set.of("kind", "id"), false);
        String kind = query.getFirst("kind");
        String id = query.getFirst("id");
        if (kind == null || !RESOLVE_KINDS.contains(kind) || id == null) throw validation("resolve identity required");
        return success(project(service().resolve(kind, id(id)), LearningDtos::resolved));
    }

    @PostMapping(path = "/admin/learning/packages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> createPackage(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        var command = packageCommand(parse(raw), false);
        return success(project(service().createPackage(actor, command, key, csrf, origin), LearningDtos::importResult));
    }
    @PutMapping(path = "/admin/learning/packages/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> writePackage(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String packageId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        var command = packageCommand(parse(raw), true);
        return success(project(service().writePackage(actor, id(packageId), command, key, csrf, origin),
                LearningDtos::importResult));
    }
    @PostMapping(path = "/admin/learning/packages/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> packageAction(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String packageId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        var command = answerAction(parse(raw));
        return success(project(service().packageAction(actor, id(packageId), command, key, csrf, origin),
                LearningDtos::action));
    }
    @PutMapping(path = "/admin/learning/packages/{id}/questions/{question_id}/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> writeAnswer(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String packageId, @PathVariable("question_id") String questionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        JsonNode value = parse(raw);
        fields(value, Set.of("expected_revision", "answer"));
        var command = new LearningService.AnswerWrite(number(value.get("expected_revision")),
                text(value.get("answer"), 0, 50_000));
        return success(project(service().writeAnswer(actor, id(packageId), id(questionId), command,
                key, csrf, origin), LearningDtos::action));
    }
    @PostMapping(path = "/admin/learning/packages/{id}/questions/{question_id}/answer/actions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LearningDtos.Envelope<Map<String, Object>>> answerAction(
            @CookieValue(name = "lianxibu_session", required = false) String cookie,
            @PathVariable("id") String packageId, @PathVariable("question_id") String questionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] raw) {
        Actor actor = owner(cookie); writeHeaders(csrf, origin, key);
        var command = answerAction(parse(raw));
        return success(project(service().answerAction(actor, id(packageId), id(questionId), command,
                key, csrf, origin), LearningDtos::action));
    }

    private <T> ResponseEntity<LearningDtos.Envelope<LearningDtos.Page<Map<String, Object>>>> list(
            String packageId, MultiValueMap<String, String> values, String type, Function<T, Map<String, Object>> mapper) {
        Set<String> extra = switch (type) {
            case "questions" -> Set.of("route_id", "module_id", "q", "difficulty", "kind", "node_id");
            case "projects" -> Set.of("route_id", "module_id", "q", "node_id");
            default -> Set.of("route_id", "module_id", "q");
        };
        query(values, extra);
        String route = optionalId(values.getFirst("route_id"));
        String module = optionalId(values.getFirst("module_id"));
        String search = optionalText(values.getFirst("q"), 256);
        String difficulty = values.getFirst("difficulty");
        String kind = values.getFirst("kind");
        String node = optionalId(values.getFirst("node_id"));
        if (difficulty != null && !DIFFICULTIES.contains(difficulty)) throw validation("invalid difficulty");
        if (kind != null && !QUESTION_KINDS.contains(kind)) throw validation("invalid question kind");
        var query = new LearningService.Query(route, module, search, difficulty, kind, node,
                positive(values.getFirst("page"), 1, CanonicalJson.MAX_SAFE_INTEGER),
                (int) positive(values.getFirst("page_size"), 20, 50));
        String id = id(packageId);
        @SuppressWarnings("unchecked")
        LearningService.Page<T> result = (LearningService.Page<T>) switch (type) {
            case "routes" -> service().routes(id, query);
            case "modules" -> service().modules(id, query);
            case "nodes" -> service().nodes(id, query);
            case "questions" -> service().questions(id, query);
            case "projects" -> service().projects(id, query);
            default -> throw new IllegalArgumentException("unknown learning collection");
        };
        return success(page(result, mapper));
    }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<LearningDtos.ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new LearningDtos.ErrorEnvelope(new LearningDtos.ErrorDetail(error.code,
                error.getMessage()), new LearningDtos.Meta(requestId())));
    }
    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<LearningDtos.ErrorEnvelope> rejected(IdentityService.Rejected error) {
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
        return response.body(new LearningDtos.ErrorEnvelope(new LearningDtos.ErrorDetail(code, code),
                new LearningDtos.Meta(requestId())));
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
    private LearningService service() {
        LearningService value = learning.getIfAvailable();
        if (value == null) throw unavailable();
        return value;
    }

    private static LearningService.PackageCommand packageCommand(JsonNode node, boolean update) {
        String action = text(node.get("action"), 1, 32);
        Set<String> header = update ? Set.of("expected_revision", "action") : Set.of("action");
        if ("stage".equals(action)) {
            fields(node, union(header, "chunk"));
            return new LearningService.Stage(update ? number(node.get("expected_revision")) : null,
                    chunk(node.get("chunk")));
        }
        if ("commit".equals(action)) {
            fields(node, union(header, "manifest"));
            return new LearningService.Commit(update ? number(node.get("expected_revision")) : null,
                    manifest(node.get("manifest")));
        }
        throw validation("invalid import action");
    }
    private static LearningService.ImportChunk chunk(JsonNode node) {
        fields(node, Set.of("import_id", "part_no", "part_count", "part_hash", "items"));
        long partNo = number(node.get("part_no"));
        long partCount = bounded(node.get("part_count"), 1, 100_000);
        if (partNo >= partCount) throw validation("part number outside manifest");
        JsonNode items = node.get("items");
        if (!items.isArray() || items.size() < 1 || items.size() > 50) throw validation("invalid import items");
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode item : items) { validateItem(item); values.add(item.deepCopy()); }
        return new LearningService.ImportChunk(id(node.get("import_id")), partNo, partCount,
                sha(node.get("part_hash")), List.copyOf(values));
    }
    private static LearningService.ImportFinalize manifest(JsonNode node) {
        fields(node, Set.of("import_id", "title", "description", "part_count", "manifest_hash"));
        return new LearningService.ImportFinalize(id(node.get("import_id")), text(node.get("title"), 1, 200),
                text(node.get("description"), 0, 16_384), bounded(node.get("part_count"), 1, 100_000),
                sha(node.get("manifest_hash")));
    }
    private static LearningService.AnswerAction answerAction(JsonNode node) {
        fields(node, Set.of("expected_revision", "action", "reason"));
        String action = text(node.get("action"), 1, 32);
        if (!ACTIONS.contains(action)) throw validation("invalid review action");
        return new LearningService.AnswerAction(number(node.get("expected_revision")), action,
                text(node.get("reason"), 1, 2048));
    }

    private static void validateItem(JsonNode item) {
        if (item == null || !item.isObject()) throw validation("import item object required");
        if (item.has("from_id")) {
            fields(item, Set.of("from_id", "to_id", "kind"));
            id(item.get("from_id")); id(item.get("to_id"));
            if (!Set.of("prerequisite", "related", "contains").contains(text(item.get("kind"), 1, 32))) {
                throw validation("invalid graph edge kind");
            }
            return;
        }
        Set<String> base = Set.of("package_id", "id", "title");
        Set<String> specific;
        if (item.has("question")) specific = Set.of("question", "difficulty", "kind", "assessment_points",
                "answer_state", "generation_state", "revision");
        else if (item.has("scope")) specific = Set.of("module_id", "scope", "goals", "completion_criteria",
                "dependencies", "resources", "revision");
        else if (item.has("requirements")) specific = Set.of("description", "goals", "requirements", "tasks",
                "deliverables", "acceptance_criteria", "revision");
        else if (item.has("module_ids")) specific = Set.of("module_ids", "completed_node_count",
                "eligible_node_count", "completion_rate");
        else if (item.has("route_ids")) specific = Set.of("route_ids");
        else throw validation("unknown import item kind");
        boolean publishedAnswer = item.has("question") && "published".equals(item.path("answer_state").asText());
        fields(item, union(union(base, specific), publishedAnswer ? "answer" : null));
        id(item.get("package_id")); id(item.get("id")); text(item.get("title"), 1, 200);
        if (item.has("question")) {
            text(item.get("question"), 1, 50_000);
            if (!DIFFICULTIES.contains(text(item.get("difficulty"), 1, 32)) ||
                    !QUESTION_KINDS.contains(text(item.get("kind"), 1, 32))) throw validation("invalid question facet");
            strings(item.get("assessment_points"), 0, 100, 1, 2048);
            String state = text(item.get("answer_state"), 1, 32);
            if (!Set.of("draft", "approved", "published", "withdrawn").contains(state)) {
                throw validation("invalid answer state");
            }
            if (!Set.of("not_generated", "generated", "reviewed", "needs_revision")
                    .contains(text(item.get("generation_state"), 1, 32))) throw validation("invalid generation state");
            number(item.get("revision"));
            if (publishedAnswer) text(item.get("answer"), 0, 50_000);
        } else if (item.has("scope")) {
            id(item.get("module_id")); text(item.get("scope"), 0, 16_384);
            strings(item.get("goals"), 0, 100, 1, 2048);
            strings(item.get("completion_criteria"), 0, 100, 1, 2048);
            ids(item.get("dependencies"), 0, 1000);
            JsonNode resources = item.get("resources");
            if (!resources.isArray() || resources.size() > 100) throw validation("invalid resources");
            for (JsonNode resource : resources) {
                fields(resource, Set.of("title", "url"));
                text(resource.get("title"), 1, 512); https(resource.get("url"));
            }
            number(item.get("revision"));
        } else if (item.has("requirements")) {
            text(item.get("description"), 0, 50_000);
            for (String field : List.of("goals", "requirements", "tasks", "deliverables", "acceptance_criteria")) {
                strings(item.get(field), 0, 100, 1, 2048);
            }
            number(item.get("revision"));
        } else if (item.has("module_ids")) {
            ids(item.get("module_ids"), 0, 1000);
            number(item.get("completed_node_count")); number(item.get("eligible_node_count"));
            JsonNode rate = item.get("completion_rate");
            if (!rate.isNull() && !text(rate, 1, 16).matches("(0(\\.[0-9]{1,8})?|1(\\.0{1,8})?)")) {
                throw validation("invalid completion rate");
            }
        } else ids(item.get("route_ids"), 0, 1000);
    }

    private static void query(MultiValueMap<String, String> values, Set<String> extra) {
        query(values, extra, true);
    }
    private static void query(MultiValueMap<String, String> values, Set<String> extra, boolean paging) {
        for (String field : values.keySet()) {
            if ((!extra.contains(field) && !(paging && Set.of("page", "page_size").contains(field))) ||
                    values.get(field).size() != 1) throw protocol("unknown or repeated query parameter");
        }
    }
    private static long positive(String raw, long fallback, long max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw validation("invalid positive integer query");
        try { long value = Long.parseLong(raw); if (value <= max) return value; }
        catch (NumberFormatException ignored) { }
        throw validation("invalid positive integer query");
    }
    private static String optionalId(String value) { return value == null ? null : id(value); }
    private static String optionalText(String value, int max) {
        if (value == null) return null;
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > max) throw validation("query text outside contract bounds");
        return value;
    }
    private static JsonNode parse(byte[] raw) {
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > 262_144) throw new BoundaryFailure(413, "payload_too_large", "request body too large");
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
    private static Set<String> union(Set<String> source, String extra) {
        if (extra == null) return source;
        var result = new java.util.HashSet<>(source); result.add(extra); return Set.copyOf(result);
    }
    private static Set<String> union(Set<String> first, Set<String> second) {
        var result = new java.util.HashSet<>(first); result.addAll(second); return Set.copyOf(result);
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
    private static String sha(JsonNode node) {
        try { return WireValues.sha256(node); }
        catch (IllegalArgumentException error) { throw validation("invalid SHA-256"); }
    }
    private static String https(JsonNode node) {
        String value = text(node, 1, 2000);
        java.net.URI uri;
        try { uri = java.net.URI.create(value); }
        catch (IllegalArgumentException error) { throw validation("invalid resource URL"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
                uri.getFragment() != null) throw validation("resource URL must be HTTPS");
        return value;
    }
    private static long number(JsonNode node) {
        try { return WireValues.nonnegativeInteger(node); }
        catch (IllegalArgumentException error) { throw validation("invalid integer"); }
    }
    private static long bounded(JsonNode node, long min, long max) {
        long value = number(node);
        if (value < min || value > max) throw validation("integer outside contract bounds");
        return value;
    }
    private static List<String> ids(JsonNode node, int min, int max) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            throw validation("invalid identity array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(id(item));
        return List.copyOf(result);
    }
    private static List<String> strings(JsonNode node, int min, int max, int itemMin, int itemMax) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) {
            throw validation("invalid string array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(text(item, itemMin, itemMax));
        return List.copyOf(result);
    }
    private static void writeHeaders(String csrf, String origin, String key) {
        if (csrf == null || csrf.isBlank() || origin == null || origin.isBlank()) {
            throw new BoundaryFailure(403, "forbidden", "CSRF token and Origin required");
        }
        if (key == null || !key.matches("[!-~]{1,128}")) throw validation("valid Idempotency-Key required");
    }
    private static <T> LearningDtos.Page<Map<String, Object>> page(
            LearningService.Page<T> value, Function<T, Map<String, Object>> mapper) {
        if (value == null || value.items() == null || value.items().size() > 50) throw unavailable();
        try {
            return new LearningDtos.Page<>(value.items().stream().map(mapper).toList(),
                    value.page(), value.pageSize(), value.total());
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static <T> Map<String, Object> project(T value, Function<T, Map<String, Object>> mapper) {
        if (value == null) throw unavailable();
        try { return mapper.apply(value); }
        catch (IllegalArgumentException error) { throw unavailable(); }
    }
    private static <T> ResponseEntity<LearningDtos.Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new LearningDtos.Envelope<>(data, new LearningDtos.Meta(requestId())));
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error", message); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation", message); }
    private static BoundaryFailure unavailable() {
        return new BoundaryFailure(503, "temporarily_unavailable", "learning service unavailable");
    }
    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }
}
