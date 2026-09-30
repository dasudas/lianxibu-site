package com.lianxibu.site.controller.collector;

import com.lianxibu.site.controller.collector.CollectorGateway.Operation;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ManifestControl;
import com.lianxibu.site.domain.protocol.ObservationEnvelope;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.articles.ObservationPayloadPolicy;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Fail-closed collector wire boundary. Runtime services are attached only by B26. */
@RestController
@RequestMapping("/api/collector/v1")
public final class CollectorController {
    private static final int MAX_BODY = 524_288;
    private static final Set<String> CONTROL = Set.of("business", "attempt");
    private static final Set<String> ATTEMPT = Set.of("claim_token", "expected_run_revision");
    private static final Map<Operation, Set<String>> RESPONSE = Map.ofEntries(
            Map.entry(Operation.CONFIG, Set.of("protocol_revision", "sources", "next_cursor", "valid_until")),
            Map.entry(Operation.RUN_CLAIMS, Set.of("runs")),
            Map.entry(Operation.CLAIM_ACTION, controlFields()),
            Map.entry(Operation.RUNS, Set.of("items", "next_cursor")),
            Map.entry(Operation.RUN, Set.of("run_id", "source_id", "run_kind", "business_date", "generation",
                    "window_start", "window_end", "fetch_mode", "collection_reason", "config_version",
                    "run_revision", "delivery_state", "fetch_state", "analysis_state", "coverage",
                    "expected_count", "accepted_count", "excluded_count", "pending_count", "barrier_closed_at",
                    "late_results_available", "result_business_hash", "manifest_hash")),
            Map.entry(Operation.MANIFEST_READ, Set.of("run_id", "parts", "next_cursor")),
            Map.entry(Operation.MANIFEST_WRITE, controlFields()),
            Map.entry(Operation.RUN_RESULT, controlFields()),
            Map.entry(Operation.MEMBER_SEAL, controlFields()),
            Map.entry(Operation.OBSERVATIONS, Set.of("acks")),
            Map.entry(Operation.HEARTBEAT, Set.of("received_at")),
            Map.entry(Operation.PROBE_CLAIMS, Set.of("probe_id", "source_id", "config", "claim_token", "lease_until", "current_lease_valid")),
            Map.entry(Operation.PROBE_RESULT, controlFields()));

    private final ObjectProvider<NodeIdentityService> nodes;
    private final ObjectProvider<CollectorGateway> gateway;

    public CollectorController(ObjectProvider<NodeIdentityService> nodes,
                               ObjectProvider<CollectorGateway> gateway) {
        this.nodes = nodes;
        this.gateway = gateway;
    }

    @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> config(HttpServletRequest request) {
        return dispatch(request, Operation.CONFIG, null, null, null, null);
    }

    @PostMapping(path = "/run-claims", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> claimRuns(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.RUN_CLAIMS, null, null, null, body);
    }

    @PutMapping(path = "/runs/{run_id}/claim", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> claimAction(HttpServletRequest request, @PathVariable("run_id") String runId,
                                         @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.CLAIM_ACTION, runId, null, null, body);
    }

    @GetMapping(path = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runs(HttpServletRequest request) {
        return dispatch(request, Operation.RUNS, null, null, null, null);
    }

    @GetMapping(path = "/runs/{run_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(HttpServletRequest request, @PathVariable("run_id") String runId) {
        return dispatch(request, Operation.RUN, runId, null, null, null);
    }

    @GetMapping(path = "/runs/{run_id}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> manifest(HttpServletRequest request, @PathVariable("run_id") String runId) {
        return dispatch(request, Operation.MANIFEST_READ, runId, null, null, null);
    }

    @PutMapping(path = "/runs/{run_id}/manifest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> writeManifest(HttpServletRequest request, @PathVariable("run_id") String runId,
                                           @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.MANIFEST_WRITE, runId, null, null, body);
    }

    @PutMapping(path = "/runs/{run_id}/result", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> result(HttpServletRequest request, @PathVariable("run_id") String runId,
                                    @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.RUN_RESULT, runId, null, null, body);
    }

    @PostMapping(path = "/runs/{run_id}/members/{member_id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> seal(HttpServletRequest request, @PathVariable("run_id") String runId,
                                  @PathVariable("member_id") String memberId,
                                  @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.MEMBER_SEAL, runId, memberId, null, body);
    }

    @PostMapping(path = "/observations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> observations(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.OBSERVATIONS, null, null, null, body);
    }

    @PostMapping(path = "/heartbeat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> heartbeat(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.HEARTBEAT, null, null, null, body);
    }

    @PostMapping(path = "/probe-claims", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> claimProbe(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.PROBE_CLAIMS, null, null, null, body);
    }

    @PutMapping(path = "/probes/{probe_id}/result", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> probeResult(HttpServletRequest request, @PathVariable("probe_id") String probeId,
                                         @RequestBody(required = false) byte[] body) {
        return dispatch(request, Operation.PROBE_RESULT, null, null, probeId, body);
    }

    private ResponseEntity<?> dispatch(HttpServletRequest request, Operation operation,
                                       String runId, String memberId, String probeId, byte[] raw) {
        String pathRun = id(runId);
        String pathMember = id(memberId);
        String pathProbe = id(probeId);
        Query query = query(request, operation);
        String key = key(request, operation);
        if (java.util.Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() != 1) {
            throw new BoundaryFailure(401, "unauthenticated");
        }
        String bearer = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        NodeIdentityService identity = nodes.getIfAvailable();
        if (identity == null) throw unavailable();
        Principal principal = identity.authorizeBearer(bearer, permission(operation), query.sourceId());
        if (principal == null) throw unavailable();
        JsonNode body = body(raw, operation, pathRun, pathMember, pathProbe, principal);
        CollectorGateway runtime = gateway.getIfAvailable();
        if (runtime == null) throw unavailable();
        var result = runtime.execute(principal, bearer, operation, new CollectorGateway.Request(pathRun, pathMember,
                pathProbe, query.cursor(), query.limit(), query.sourceId(), request.getHeader(HttpHeaders.IF_NONE_MATCH),
                key, body, raw));
        if (result == null) throw unavailable();
        if (result.status() == 304 && operation == Operation.CONFIG && result.data() == null) {
            return ResponseEntity.status(304).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .eTag(result.etag() == null ? "\"unchanged\"" : result.etag()).build();
        }
        if (result.status() == 204 && operation == Operation.PROBE_CLAIMS && result.data() == null) {
            return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        if (result.status() != 200 || result.data() == null) throw unavailable();
        try { validateResponse(operation, result.data(), pathRun, pathProbe, body); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
        var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (operation == Operation.CONFIG && result.etag() != null) response.eTag(result.etag());
        return response.body(new Envelope(result.data(), new Meta(requestId())));
    }

    private static JsonNode body(byte[] raw, Operation operation, String runId, String memberId,
                                 String probeId, Principal principal) {
        if (Set.of(Operation.CONFIG, Operation.RUNS, Operation.RUN, Operation.MANIFEST_READ).contains(operation)) return null;
        if (raw == null || raw.length == 0) throw validation("request body required");
        if (raw.length > MAX_BODY) throw new BoundaryFailure(413, "payload_too_large");
        JsonNode value;
        try { value = CanonicalJson.parse(raw); }
        catch (IllegalArgumentException invalid) { throw protocol("invalid JSON"); }
        try {
            switch (operation) {
                case RUN_CLAIMS -> {
                    WireValues.object(value, Set.of("source_ids", "required_features", "max_runs"),
                            Set.of("source_ids", "required_features", "max_runs"));
                    scopedSources(value.get("source_ids"), principal);
                    features(value.get("required_features"));
                    long max = WireValues.nonnegativeInteger(value.get("max_runs"));
                    if (max < 1 || max > 10) throw new IllegalArgumentException("max_runs outside bounds");
                }
                case PROBE_CLAIMS -> {
                    WireValues.object(value, Set.of("source_ids", "required_features"),
                            Set.of("source_ids", "required_features"));
                    scopedSources(value.get("source_ids"), principal);
                    features(value.get("required_features"));
                }
                case CLAIM_ACTION -> control(value, runId, null, Set.of("run_id", "action"), false);
                case MANIFEST_WRITE -> ManifestControl.fromJson(value, runId);
                case RUN_RESULT -> control(value, runId, null,
                        Set.of("run_id", "manifest_hash", "part_count", "expected_count", "fetch_outcome", "coverage"), true);
                case MEMBER_SEAL -> control(value, runId, memberId,
                        Set.of("run_id", "member_id", "expected_member_revision", "action", "reason"), true);
                case PROBE_RESULT -> {
                    control(value, null, probeId, Set.of("probe_id", "result"), false);
                    probeResult(value.get("business").get("result"));
                }
                case OBSERVATIONS -> {
                    WireValues.object(value, Set.of("observations"), Set.of("observations"));
                    JsonNode list = value.get("observations");
                    if (!list.isArray() || list.size() < 1 || list.size() > 10) throw new IllegalArgumentException("invalid batch");
                    Set<String> seen = new java.util.HashSet<>();
                    for (JsonNode item : list) {
                        var observation = ObservationEnvelope.fromJson(item);
                        if (!"article".equals(observation.kind())) {
                            ObservationPayloadPolicy.validate(observation.kind(), item.get("payload"));
                        }
                        if (!seen.add(observation.observationId())) throw new IllegalArgumentException("duplicate observation ID");
                        if (!principal.sourceIds().contains(observation.sourceId())) throw new BoundaryFailure(403, "forbidden");
                    }
                }
                case HEARTBEAT -> heartbeat(value);
                default -> throw new IllegalArgumentException("unsupported body operation");
            }
        } catch (BoundaryFailure failure) { throw failure; }
        catch (WireValues.UnknownMember unknown) { throw protocol("unknown collector field"); }
        catch (IllegalArgumentException invalid) {
            if ("schema_not_supported".equals(invalid.getMessage())
                    || "unknown observation kind".equals(invalid.getMessage())) {
                throw new BoundaryFailure(409, "schema_not_supported");
            }
            throw validation("invalid collector body");
        }
        return value;
    }

    private static void control(JsonNode value, String runId, String targetId,
                                Set<String> businessFields, boolean requireRevision) {
        WireValues.object(value, CONTROL, CONTROL);
        JsonNode business = WireValues.object(value.get("business"), businessFields, businessFields);
        if (runId != null && !runId.equals(WireValues.id(business.get("run_id"), false))) {
            throw new IllegalArgumentException("run path mismatch");
        }
        if (targetId != null) {
            String name = runId == null ? "probe_id" : "member_id";
            if (!targetId.equals(WireValues.id(business.get(name), false))) throw new IllegalArgumentException("path mismatch");
        }
        JsonNode attempt = WireValues.object(value.get("attempt"), ATTEMPT, ATTEMPT);
        WireValues.id(attempt.get("claim_token"), false);
        if (attempt.get("expected_run_revision").isNull()) {
            if (requireRevision) throw new IllegalArgumentException("revision required");
        } else WireValues.nonnegativeInteger(attempt.get("expected_run_revision"));
        if (businessFields.contains("part_count")) {
            WireValues.sha256(business.get("manifest_hash"));
            WireValues.nonnegativeInteger(business.get("part_count"));
            WireValues.nonnegativeInteger(business.get("expected_count"));
            if (!Set.of("succeeded", "failed", "partial")
                    .contains(WireValues.text(business.get("fetch_outcome")))) {
                throw new IllegalArgumentException("invalid fetch outcome");
            }
            coverage(business.get("coverage"));
        } else if (businessFields.contains("expected_member_revision")) {
            WireValues.nonnegativeInteger(business.get("expected_member_revision"));
            if (!"seal".equals(WireValues.text(business.get("action"))) ||
                    WireValues.text(business.get("reason")).isBlank()) throw new IllegalArgumentException("invalid seal");
        } else if (businessFields.contains("action")) {
            if (!Set.of("ack", "renew", "release").contains(WireValues.text(business.get("action")))) {
                throw new IllegalArgumentException("invalid claim action");
            }
        } else if (businessFields.contains("result") && !business.get("result").isObject()) {
            throw new IllegalArgumentException("probe result required");
        }
    }

    private static void heartbeat(JsonNode value) {
        Set<String> fields = Set.of("observed_at", "pending_observations", "pending_controls", "quarantined",
                "outbox_bytes", "free_bytes", "oldest_pending_at");
        WireValues.object(value, fields, fields);
        WireValues.utcMicroseconds(value.get("observed_at"));
        for (String field : List.of("pending_observations", "pending_controls", "quarantined", "outbox_bytes", "free_bytes")) {
            WireValues.nonnegativeInteger(value.get(field));
        }
        if (!value.get("oldest_pending_at").isNull()) WireValues.utcMicroseconds(value.get("oldest_pending_at"));
    }

    private static void probeResult(JsonNode value) {
        Set<String> fields = Set.of("reachable", "http_status", "tls_valid", "parser_valid",
                "identity_valid", "checked_at", "error_code", "evidence_refs");
        WireValues.object(value, fields, fields);
        for (String field : List.of("reachable", "tls_valid", "parser_valid", "identity_valid")) {
            if (!value.get(field).isBoolean()) throw new IllegalArgumentException("invalid probe result flag");
        }
        JsonNode status = value.get("http_status");
        if (!status.isNull() && (!status.isIntegralNumber() || status.intValue() < 100 || status.intValue() > 599)) {
            throw new IllegalArgumentException("invalid probe HTTP status");
        }
        WireValues.utcMicroseconds(value.get("checked_at"));
        JsonNode code = value.get("error_code");
        if (!code.isNull() && (!code.isTextual() || !code.asText().matches("[A-Za-z0-9._:-]{1,64}"))) {
            throw new IllegalArgumentException("invalid probe error code");
        }
        JsonNode refs = value.get("evidence_refs");
        if (!refs.isArray() || refs.size() > 32) throw new IllegalArgumentException("invalid probe evidence list");
        for (JsonNode ref : refs) {
            if (!ref.isTextual() || ref.asText().length() > 2000 || !ref.asText().startsWith("https://")) {
                throw new IllegalArgumentException("invalid probe evidence reference");
            }
        }
    }

    private static void validateResponse(Operation operation, JsonNode data, String runId, String probeId, JsonNode requestBody) {
        WireValues.object(data, RESPONSE.get(operation), RESPONSE.get(operation));
        switch (operation) {
            case CLAIM_ACTION, MANIFEST_WRITE, RUN_RESULT, MEMBER_SEAL, PROBE_RESULT -> {
                WireValues.sha256(data.get("business_hash"));
                JsonNode run = data.get("run_id");
                JsonNode probe = data.get("probe_id");
                if (operation == Operation.PROBE_RESULT) {
                    if (!run.isNull() || !probeId.equals(WireValues.id(probe, false))) {
                        throw new IllegalArgumentException("probe receipt target mismatch");
                    }
                } else if (!probe.isNull() || !runId.equals(WireValues.id(run, false))) {
                    throw new IllegalArgumentException("run receipt target mismatch");
                }
                if (!data.get("run_revision").isNull()) WireValues.nonnegativeInteger(data.get("run_revision"));
                if (!data.get("current_lease_valid").isBoolean()) throw new IllegalArgumentException("invalid lease status");
                WireValues.text(data.get("delivery_state"));
                if (!data.get("lease_until").isNull()) WireValues.utcMicroseconds(data.get("lease_until"));
                if (WireValues.text(data.get("receipt_ref")).isBlank()) throw new IllegalArgumentException("empty receipt");
            }
            case OBSERVATIONS -> {
                JsonNode acks = data.get("acks");
                if (!acks.isArray() || acks.size() != requestBody.get("observations").size()) {
                    throw new IllegalArgumentException("incomplete ACK list");
                }
                Map<String,String> expected = new java.util.HashMap<>();
                for (JsonNode observation : requestBody.get("observations")) {
                    expected.put(observation.get("observation_id").asText(), observation.get("member_id").asText());
                }
                Set<String> seen = new java.util.HashSet<>();
                Set<String> fields = Set.of("observation_id", "member_id", "status", "code", "receipt_ref",
                        "accepted_at", "projection_decision", "current_article_revision", "comparison_reason");
                for (JsonNode ack : acks) {
                    WireValues.object(ack, fields, fields);
                    String observationId = WireValues.id(ack.get("observation_id"), false);
                    String memberId = WireValues.id(ack.get("member_id"), false);
                    if (!memberId.equals(expected.get(observationId)) || !seen.add(observationId)) {
                        throw new IllegalArgumentException("ACK identity mismatch");
                    }
                    String status = WireValues.text(ack.get("status"));
                    if (!Set.of("accepted", "duplicate", "rejected", "blocked", "retryable").contains(status)) {
                        throw new IllegalArgumentException("unknown ACK status");
                    }
                    if (Set.of("accepted", "duplicate").contains(status)) {
                        if (WireValues.text(ack.get("receipt_ref")).isBlank()) throw new IllegalArgumentException("empty ACK receipt");
                        WireValues.utcMicroseconds(ack.get("accepted_at"));
                    }
                }
            }
            case HEARTBEAT -> WireValues.utcMicroseconds(data.get("received_at"));
            case CONFIG -> {
                if (!"0.11".equals(WireValues.text(data.get("protocol_revision"))) || !data.get("sources").isArray()) {
                    throw new IllegalArgumentException("invalid collector config");
                }
                WireValues.utcMicroseconds(data.get("valid_until"));
                for (JsonNode source : data.get("sources")) validateFrozenConfig(source);
            }
            case RUN -> {
                validateRun(data);
                if (!runId.equals(WireValues.id(data.get("run_id"), false))) throw new IllegalArgumentException("run target mismatch");
            }
            case MANIFEST_READ -> {
                if (!runId.equals(WireValues.id(data.get("run_id"), false)) || !data.get("parts").isArray()) {
                    throw new IllegalArgumentException("manifest readback mismatch");
                }
                Set<String> partFields = Set.of("run_id","part_no","part_hash","members");
                Set<String> memberFields = Set.of("member_id","source_item_id","observation_id","payload_hash","replaces_observation_id");
                for (JsonNode part : data.get("parts")) {
                    WireValues.object(part,partFields,partFields);
                    if (!runId.equals(WireValues.id(part.get("run_id"),false)) || !part.get("members").isArray()) {
                        throw new IllegalArgumentException("manifest part mismatch");
                    }
                    for (JsonNode member : part.get("members")) WireValues.object(member,memberFields,memberFields);
                }
            }
            case RUN_CLAIMS -> {
                if (!data.get("runs").isArray()) throw new IllegalArgumentException("claim list required");
                Set<String> fields=Set.of("run","config","target_version","claim_token","lease_until","current_lease_valid");
                for (JsonNode claim : data.get("runs")) {
                    WireValues.object(claim,fields,fields);
                    validateRun(claim.get("run"));
                    validateFrozenConfig(claim.get("config"));
                }
            }
            case RUNS -> {
                if (!data.get("items").isArray()) throw new IllegalArgumentException("run page required");
                for (JsonNode run : data.get("items")) validateRun(run);
            }
            case PROBE_CLAIMS -> {
                WireValues.id(data.get("probe_id"), false);
                WireValues.id(data.get("source_id"), true);
                validateFrozenConfig(data.get("config"));
                WireValues.id(data.get("claim_token"), false);
                WireValues.utcMicroseconds(data.get("lease_until"));
                if (!data.get("current_lease_valid").isBoolean()) throw new IllegalArgumentException("invalid lease status");
            }
        }
    }

    private static void validateFrozenConfig(JsonNode frozen) {
        Set<String> fields=Set.of("source_id","config_version","activation_stage","config");
        WireValues.object(frozen,fields,fields);
        WireValues.id(frozen.get("source_id"),true);
        WireValues.nonnegativeInteger(frozen.get("config_version"));
        WireValues.text(frozen.get("activation_stage"));
        Set<String> config=Set.of("adapter","entry_urls","identity_rule","window_rule","parser_version",
                "max_items","max_response_bytes","allowed_hosts","valid_until","auth_secret_ref");
        WireValues.object(frozen.get("config"),config,config);
    }

    private static void validateRun(JsonNode run) {
        Set<String> fields=RESPONSE.get(Operation.RUN);
        WireValues.object(run,fields,fields);
        WireValues.id(run.get("run_id"),false);
        WireValues.id(run.get("source_id"),true);
        coverage(run.get("coverage"));
    }

    private static void coverage(JsonNode raw) {
        Set<String> coverage=Set.of("state","missing_inputs","missing_dates","no_update_dates");
        JsonNode value=WireValues.object(raw,coverage,coverage);
        if (!Set.of("complete","partial","no_input").contains(WireValues.text(value.get("state")))) {
            throw new IllegalArgumentException("invalid coverage state");
        }
        if (!value.get("missing_inputs").isArray() || !value.get("missing_dates").isArray() ||
                !value.get("no_update_dates").isArray()) throw new IllegalArgumentException("invalid coverage");
        Set<String> missing=Set.of("kind","id","business_date","reason");
        for (JsonNode item:value.get("missing_inputs")) WireValues.object(item,missing,missing);
        for (JsonNode item:value.get("missing_dates")) WireValues.text(item);
        for (JsonNode item:value.get("no_update_dates")) WireValues.text(item);
    }

    private static void scopedSources(JsonNode value, Principal principal) {
        if (!value.isArray() || value.size() > 1000) throw new IllegalArgumentException("source list outside bounds");
        for (JsonNode item : value) {
            String source = WireValues.id(item, true);
            if (!principal.sourceIds().contains(source)) throw new BoundaryFailure(403, "forbidden");
        }
    }

    private static void features(JsonNode value) {
        if (!value.isArray() || value.size() > 64) throw new IllegalArgumentException("feature list outside bounds");
        for (JsonNode item : value) WireValues.id(item, false);
    }

    private static String permission(Operation operation) {
        return switch (operation) {
            case CONFIG -> "config:read";
            case RUN_CLAIMS -> "run:claim";
            case RUNS, RUN, MANIFEST_READ -> "run:read";
            case CLAIM_ACTION, MANIFEST_WRITE, RUN_RESULT, MEMBER_SEAL -> "run:write";
            case OBSERVATIONS -> "observation:write";
            case HEARTBEAT -> "heartbeat:write";
            case PROBE_CLAIMS -> "probe:claim";
            case PROBE_RESULT -> "probe:write";
        };
    }

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7 ||
                header.substring(7).isBlank() || header.substring(7).contains(" ")) {
            throw new BoundaryFailure(401, "unauthenticated");
        }
        return header.substring(7);
    }

    private static String key(HttpServletRequest request, Operation operation) {
        boolean required = Set.of(Operation.RUN_CLAIMS, Operation.CLAIM_ACTION, Operation.MANIFEST_WRITE,
                Operation.RUN_RESULT, Operation.MEMBER_SEAL, Operation.PROBE_CLAIMS, Operation.PROBE_RESULT)
                .contains(operation);
        String key = request.getHeader("Idempotency-Key");
        if (java.util.Collections.list(request.getHeaders("Idempotency-Key")).size() > 1) {
            throw protocol("repeated Idempotency-Key");
        }
        if (required && (key == null || !key.matches("[!-~]{1,128}"))) throw validation("valid Idempotency-Key required");
        if (!required && key != null) throw protocol("unexpected Idempotency-Key");
        return key;
    }

    private record Query(String cursor, Integer limit, String sourceId) { }

    private static Query query(HttpServletRequest request, Operation operation) {
        Set<String> allowed = switch (operation) {
            case CONFIG, MANIFEST_READ -> Set.of("cursor");
            case RUNS -> Set.of("cursor", "limit", "source_id");
            default -> Set.of();
        };
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) throw protocol("unknown or repeated query parameter");
        }
        String cursor = request.getParameter("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 512)) throw validation("invalid cursor");
        String source = request.getParameter("source_id");
        if (source != null) {
            try { source = WireValues.id(source, true); }
            catch (IllegalArgumentException invalid) { throw validation("invalid source identity"); }
        }
        String rawLimit = request.getParameter("limit");
        Integer limit = null;
        if (rawLimit != null) {
            if (!rawLimit.matches("[1-9][0-9]*")) throw validation("invalid limit");
            try { limit = Integer.valueOf(rawLimit); }
            catch (NumberFormatException invalid) { throw validation("invalid limit"); }
            if (limit > 50) throw validation("invalid limit");
        }
        return new Query(cursor, limit, source);
    }

    private static String id(String value) {
        if (value == null) return null;
        try { return WireValues.id(value, false); }
        catch (IllegalArgumentException invalid) { throw validation("invalid path identity"); }
    }

    private static Set<String> controlFields() {
        return Set.of("business_hash", "run_id", "probe_id", "run_revision", "delivery_state",
                "current_lease_valid", "lease_until", "receipt_ref");
    }

    private static String requestId() { return UUID.randomUUID().toString(); }
    private static BoundaryFailure protocol(String message) { return new BoundaryFailure(400, "protocol_error"); }
    private static BoundaryFailure validation(String message) { return new BoundaryFailure(422, "validation"); }
    private static BoundaryFailure unavailable() { return new BoundaryFailure(503, "temporarily_unavailable"); }

    @ExceptionHandler(BoundaryFailure.class)
    public ResponseEntity<ErrorEnvelope> failure(BoundaryFailure error) {
        var response = ResponseEntity.status(error.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.status == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(new ErrorEnvelope(new ErrorDetail(error.code, error.code), new Meta(requestId())));
    }

    @ExceptionHandler(CollectorGateway.Refused.class)
    public ResponseEntity<ErrorEnvelope> refused(CollectorGateway.Refused error) {
        return failure(new BoundaryFailure(error.status(), error.code()));
    }

    @ExceptionHandler(IdentityService.Rejected.class)
    public ResponseEntity<ErrorEnvelope> rejected(IdentityService.Rejected error) {
        int status = switch (error.refusal()) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case REVISION_CONFLICT -> 409;
            default -> 403;
        };
        return failure(new BoundaryFailure(status, status == 401 ? "unauthenticated" : "forbidden"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorEnvelope> runtimeFailure(RuntimeException error) {
        return failure(unavailable());
    }

    private static final class BoundaryFailure extends RuntimeException {
        final int status;
        final String code;
        BoundaryFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    }

    public record Meta(String request_id) { }
    public record Envelope(JsonNode data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
}
