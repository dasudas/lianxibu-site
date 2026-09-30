package com.lianxibu.site.controller.collector;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.domain.protocol.ManifestControl;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import com.lianxibu.site.service.sources.RunManifestService;
import com.lianxibu.site.service.sources.RunLeaseTokenCodec;
import com.lianxibu.site.service.sources.ProbeResultService;
import com.lianxibu.site.service.articles.ObservationIngressService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Database-backed collector adapter. Unsupported operations fail closed until wired. */
public final class JdbcCollectorGateway implements CollectorGateway {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JdbcTemplate jdbc;
    private final NodeIdentityService nodes;
    private final SourceRunLeaseService leases;
    private final RunManifestService manifests;
    private final ObservationIngressService observations;
    private final ProbeResultService probes;
    private final Clock clock;

    public JdbcCollectorGateway(DataSource source, NodeIdentityService nodes,
                                SourceRunLeaseService leases, RunManifestService manifests,
                                ObservationIngressService observations, ProbeResultService probes, Clock clock) {
        if (source == null || nodes == null || leases == null || manifests == null
                || observations == null || probes == null || clock == null)
            throw new IllegalArgumentException("collector runtime dependencies required");
        this.jdbc = new JdbcTemplate(source);
        this.nodes = nodes;
        this.leases = leases;
        this.manifests = manifests;
        this.observations = observations;
        this.probes = probes;
        this.clock = clock;
    }

    @Override public Result execute(Principal principal, String bearer, Operation operation, Request request) {
        if (principal == null || bearer == null || operation == null || request == null)
            throw new Refused(403, "forbidden");
        try {
            return switch (operation) {
                case CONFIG -> config(principal, bearer, request);
                case RUN_CLAIMS -> claimRuns(principal, bearer, request, false);
                case CLAIM_ACTION -> claimAction(principal, bearer, request);
                case RUNS -> runs(principal, bearer, request);
                case RUN -> new Result(200, run(principal, bearer, request.runId(), "run:read"), null);
                case MANIFEST_READ -> manifest(principal, bearer, request);
                case MANIFEST_WRITE -> manifestWrite(principal, bearer, request);
                case RUN_RESULT -> runResult(principal, bearer, request);
                case MEMBER_SEAL -> memberSeal(principal, bearer, request);
                case OBSERVATIONS -> observations(principal, bearer, request);
                case HEARTBEAT -> heartbeat(principal, bearer);
                case PROBE_CLAIMS -> claimRuns(principal, bearer, request, true);
                case PROBE_RESULT -> probeResult(bearer, request);
                default -> throw new Refused(503, "temporarily_unavailable");
            };
        } catch (SourceRunLeaseService.LeaseRejected rejected) {
            throw new Refused(409, "stale_claim");
        } catch (RunManifestService.RunFrozen frozen) {
            throw new Refused(409, "run_frozen");
        } catch (ObservationIngressService.IdentityConflict conflict) {
            throw new Refused(409, "observation_identity_conflict");
        } catch (ProbeResultService.ProbeRejected rejected) {
            throw new Refused(409, "stale_claim");
        }
    }

    private Result probeResult(String bearer, Request request) {
        JsonNode business = request.body().get("business");
        var outcome = probes.submit(bearer, request.probeId(), business,
                request.body().get("attempt"), action(request.idempotencyKey(), request.body()));
        ObjectNode data = control("probe_result", business, null, outcome.probeId(),
                outcome.revision(), outcome.deliveryState(), false,
                outcome.originalLeaseUntil(), outcome.receiptRef());
        if (!outcome.businessHash().equals(data.get("business_hash").asText()))
            throw new Refused(503, "temporarily_unavailable");
        return new Result(200, data, null);
    }

    private Result observations(Principal principal, String bearer, Request request) {
        List<byte[]> frozen;
        try { frozen = FrozenObservationBatch.extract(request.rawBody(), request.body()); }
        catch (IllegalArgumentException invalid) { throw new Refused(422, "validation"); }
        ArrayNode acks = JSON.createArrayNode();
        for (byte[] bytes : frozen) {
            JsonNode wire = CanonicalJson.parse(bytes);
            String source = wire.get("source_id").asText();
            if (!principal.sourceIds().contains(source)) throw new Refused(403, "forbidden");
            var outcome = observations.accept(bearer, bytes,
                    new ActionContext(wire.get("observation_id").asText(), UUID.randomUUID().toString(),
                            CanonicalJson.hash(Domain.OBSERVATION, wire)));
            ObjectNode ack = acks.addObject();
            ack.put("observation_id", outcome.observationId());
            ack.put("member_id", outcome.memberId());
            ack.put("status", outcome.status());
            nullable(ack, "code", outcome.code());
            nullable(ack, "receipt_ref", outcome.receiptRef());
            nullable(ack, "accepted_at", outcome.acceptedAt() == null ? null : time(outcome.acceptedAt()));
            nullable(ack, "projection_decision", outcome.projectionDecision());
            if (outcome.currentArticleRevision() == null) ack.putNull("current_article_revision");
            else ack.put("current_article_revision", outcome.currentArticleRevision());
            ack.putNull("comparison_reason");
        }
        ObjectNode data = JSON.createObjectNode();
        data.set("acks", acks);
        return new Result(200, data, null);
    }

    private Result manifest(Principal principal, String bearer, Request request) {
        run(principal, bearer, request.runId(), "run:read");
        List<JsonNode> all = jdbc.query("SELECT ack_json FROM request_receipts WHERE run_id=?"
                        + " AND control_operation='manifest' AND receipt_kind='collector_control'",
                (rs, ignored) -> CanonicalJson.parse(rs.getString(1)), request.runId());
        all.sort(Comparator.comparingLong(item -> item.get("part_no").longValue()));
        long after = -1;
        if (request.cursor() != null) {
            try { after = Long.parseLong(request.cursor()); }
            catch (NumberFormatException invalid) { throw new Refused(422, "validation"); }
            if (after < 0) throw new Refused(422, "validation");
        }
        ObjectNode data = JSON.createObjectNode();
        data.put("run_id", request.runId());
        ArrayNode parts = data.putArray("parts");
        String next = null;
        int count = 0;
        for (JsonNode part : all) {
            long number = part.get("part_no").longValue();
            if (number <= after) continue;
            if (count == 50) { next = Long.toString(parts.get(count - 1).get("part_no").longValue()); break; }
            parts.add(part);
            count++;
        }
        nullable(data, "next_cursor", next);
        return new Result(200, data, null);
    }

    private Result manifestWrite(Principal principal, String bearer, Request request) {
        var parsed = ManifestControl.fromJson(request.body(), request.runId());
        JsonNode business = parsed.business();
        List<RunManifestService.Member> members = new ArrayList<>();
        for (JsonNode item : business.get("members")) {
            members.add(new RunManifestService.Member(item.get("member_id").asText(),
                    textOrNull(item.get("source_item_id")), item.get("observation_id").asText(),
                    item.get("payload_hash").asText(), textOrNull(item.get("replaces_observation_id"))));
        }
        var outcome = manifests.putPart(bearer, new RunManifestService.Part(request.runId(),
                        parsed.partNo(), parsed.partHash(), members),
                request.body().get("attempt").get("claim_token").asText(),
                action(request.idempotencyKey(), request.body()));
        return manifestControl(principal, bearer, request, "manifest", business,
                outcome, "manifest:" + request.runId() + ":" + parsed.partNo());
    }

    private Result runResult(Principal principal, String bearer, Request request) {
        JsonNode business = request.body().get("business");
        JsonNode attempt = request.body().get("attempt");
        var result = new RunManifestService.Result(request.runId(),
                business.get("manifest_hash").asText(), business.get("part_count").longValue(),
                business.get("expected_count").longValue(), business.get("fetch_outcome").asText(),
                new String(CanonicalJson.bytes(business.get("coverage")), StandardCharsets.UTF_8));
        var outcome = manifests.submitResult(bearer, result, attempt.get("claim_token").asText(),
                attempt.get("expected_run_revision").longValue(), action(request.idempotencyKey(), request.body()));
        return manifestControl(principal, bearer, request, "result", business,
                outcome, "result:" + request.runId());
    }

    private Result memberSeal(Principal principal, String bearer, Request request) {
        JsonNode business = request.body().get("business");
        JsonNode attempt = request.body().get("attempt");
        var outcome = manifests.sealMember(bearer, request.runId(), request.memberId(),
                business.get("expected_member_revision").longValue(),
                attempt.get("expected_run_revision").longValue(), business.get("reason").asText(),
                attempt.get("claim_token").asText(), action(request.idempotencyKey(), request.body()));
        return manifestControl(principal, bearer, request, "member_seal", business,
                outcome, "member_seal:" + businessHash("member_seal", business));
    }

    private Result manifestControl(Principal principal, String bearer, Request request,
                                   String operation, JsonNode business, RunManifestService.Outcome outcome,
                                   String scope) {
        String token = request.body().get("attempt").get("claim_token").asText();
        ObjectNode data = control(operation, business, request.runId(), null,
                outcome.revision(), outcome.deliveryState(), currentLease(principal, request.runId(), token),
                leaseUntil(request.runId()), fixedReceiptRef(principal.nodeId(), scope, request.idempotencyKey()));
        return new Result(200, data, null);
    }

    private String fixedReceiptRef(String nodeId, String scope, String key) {
        List<String> refs = jdbc.query("SELECT id FROM request_receipts WHERE actor_id=? AND scope=?"
                        + " AND receipt_kind='collector_control' AND key_hash=?",
                (rs, ignored) -> rs.getString(1), nodeId, scope, digest(key));
        if (refs.size() == 1) return refs.getFirst();
        refs = jdbc.query("SELECT id FROM request_receipts WHERE scope=? AND receipt_kind='collector_control'",
                (rs, ignored) -> rs.getString(1), scope);
        if (refs.size() != 1) throw new Refused(503, "temporarily_unavailable");
        return refs.getFirst();
    }

    private boolean currentLease(Principal principal, String runId, String claimToken) {
        List<Boolean> values = jdbc.query("SELECT claim_node_id,claim_token_hash,lease_until FROM source_runs WHERE id=?",
                (rs, ignored) -> principal.nodeId().equals(rs.getString(1)) && rs.getBytes(2) != null
                        && MessageDigest.isEqual(rs.getBytes(2), RunLeaseTokenCodec.digest(claimToken))
                        && rs.getTimestamp(3) != null && rs.getTimestamp(3).toInstant().isAfter(clock.instant()), runId);
        return values.size() == 1 && values.getFirst();
    }

    private Instant leaseUntil(String runId) {
        List<Instant> values = jdbc.query("SELECT lease_until FROM source_runs WHERE id=?",
                (rs, ignored) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), runId);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static String textOrNull(JsonNode value) { return value == null || value.isNull() ? null : value.asText(); }

    private Result config(Principal principal, String bearer, Request request) {
        nodes.authorizeBearer(bearer, "config:read", null);
        ObjectNode data = JSON.createObjectNode();
        data.put("protocol_revision", "0.11");
        ArrayNode sources = data.putArray("sources");
        String cursor = request.cursor();
        Instant expiry = clock.instant().plusSeconds(60);
        String next = null;
        if (!principal.sourceIds().isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(principal.sourceIds().size(), "?"));
            List<Object> parameters = new ArrayList<>(principal.sourceIds());
            parameters.add(cursor == null ? "" : cursor);
            parameters.add(Timestamp.from(clock.instant()));
            List<ObjectNode> page = jdbc.query("SELECT id,config_revision,activation_stage,effective_config_json,"
                            + "config_valid_until FROM sources WHERE id IN (" + placeholders + ") AND id>?"
                            + " AND activation_stage IN ('trial','production') AND config_valid_until>?"
                            + " ORDER BY id LIMIT 101", (rs, ignored) -> {
                        nodes.authorizeBearer(bearer, "config:read", rs.getString("id"));
                        return frozen(rs.getString("id"), rs.getLong("config_revision"),
                                rs.getString("activation_stage"), rs.getString("effective_config_json"));
                    }, parameters.toArray());
            int count = Math.min(page.size(), 100);
            for (int index = 0; index < count; index++) {
                ObjectNode item = page.get(index);
                sources.add(item);
                next = item.get("source_id").asText();
                Instant valid = Instant.parse(item.get("config").get("valid_until").asText());
                if (valid.isBefore(expiry)) expiry = valid;
            }
            if (page.size() <= 100) next = null;
        }
        nullable(data, "next_cursor", next);
        data.put("valid_until", time(expiry));
        String etag = "\"" + CanonicalJson.hash(Domain.ACTION, data) + "\"";
        if (etag.equals(request.ifNoneMatch())) return new Result(304, null, etag);
        return new Result(200, data, etag);
    }

    private Result claimRuns(Principal principal, String bearer, Request request, boolean probe) {
        JsonNode body = request.body();
        List<String> sources = new ArrayList<>();
        body.get("source_ids").forEach(item -> sources.add(item.asText()));
        if (body.get("required_features").size() != 0) throw new Refused(422, "unsupported_feature");
        int max = probe ? 1 : body.get("max_runs").intValue();
        var claim = leases.claimNext(bearer, probe, sources, max, action(request.idempotencyKey(), body));
        if (claim.runId() == null) {
            if (probe) return new Result(204, null, null);
            ObjectNode empty = JSON.createObjectNode();
            empty.putArray("runs");
            return new Result(200, empty, null);
        }
        if (!sources.contains(claim.sourceId()) || !principal.sourceIds().contains(claim.sourceId()))
            throw new Refused(503, "temporarily_unavailable");
        ObjectNode config = frozen(claim.sourceId(), runConfigVersion(claim.runId()),
                runActivation(claim.runId()), claim.configSnapshotJson());
        if (probe) {
            ObjectNode data = JSON.createObjectNode();
            data.put("probe_id", claim.runId());
            data.put("source_id", claim.sourceId());
            data.set("config", config);
            nullable(data, "claim_token", claim.claimToken());
            nullable(data, "lease_until", claim.leaseUntil() == null ? null : time(claim.leaseUntil()));
            data.put("current_lease_valid", claim.currentLeaseValid());
            return new Result(200, data, null);
        }
        ObjectNode item = JSON.createObjectNode();
        item.set("run", run(principal, bearer, claim.runId(), probe ? "probe:claim" : "run:claim"));
        item.set("config", config);
        item.set("target_version", targetVersion(claim.runId()));
        nullable(item, "claim_token", claim.claimToken());
        nullable(item, "lease_until", claim.leaseUntil() == null ? null : time(claim.leaseUntil()));
        item.put("current_lease_valid", claim.currentLeaseValid());
        ObjectNode data = JSON.createObjectNode();
        data.putArray("runs").add(item);
        return new Result(200, data, null);
    }

    private Result claimAction(Principal principal, String bearer, Request request) {
        JsonNode business = request.body().get("business");
        String kind = business.get("action").asText();
        String runId = request.runId();
        String token = request.body().get("attempt").get("claim_token").asText();
        ActionContext action = action(request.idempotencyKey(), request.body());
        var outcome = switch (kind) {
            case "ack" -> leases.acknowledge(bearer, runId, token, action);
            case "renew" -> leases.renew(bearer, runId, token, action);
            case "release" -> leases.release(bearer, runId, token, action);
            default -> throw new Refused(422, "validation");
        };
        ObjectNode data = control("claim_" + kind, business, runId, null,
                outcome.resultRevision(), run(principal, bearer, runId, "run:write").get("delivery_state").asText(),
                outcome.currentLeaseValid(), outcome.originalLeaseUntil(),
                receiptRef("source-run", "source-run-v1", request.idempotencyKey()));
        return new Result(200, data, null);
    }

    private Result runs(Principal principal, String bearer, Request request) {
        nodes.authorizeBearer(bearer, "run:read", request.sourceId());
        ObjectNode data = JSON.createObjectNode();
        ArrayNode items = data.putArray("items");
        if (principal.sourceIds().isEmpty()) {
            data.putNull("next_cursor");
            return new Result(200, data, null);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(principal.sourceIds().size(), "?"));
        List<Object> parameters = new ArrayList<>(principal.sourceIds());
        parameters.add(request.cursor() == null ? "" : request.cursor());
        String filter = "";
        if (request.sourceId() != null) {
            filter = " AND source_id=?";
            parameters.add(request.sourceId());
        }
        int limit = request.limit() == null ? 50 : request.limit();
        List<String> ids = jdbc.query("SELECT id FROM source_runs WHERE source_id IN (" + placeholders
                        + ") AND id>?" + filter + " ORDER BY id LIMIT " + (limit + 1),
                (rs, ignored) -> rs.getString(1), parameters.toArray());
        for (int index = 0; index < Math.min(ids.size(), limit); index++) items.add(run(principal, bearer, ids.get(index), "run:read"));
        nullable(data, "next_cursor", ids.size() > limit ? ids.get(limit - 1) : null);
        return new Result(200, data, null);
    }

    private ObjectNode run(Principal principal, String bearer, String runId, String permission) {
        List<ObjectNode> found = jdbc.query("SELECT id,source_id,run_kind,business_date,generation,window_start,"
                        + "window_end,fetch_mode,collection_reason,config_version,revision,delivery_state,"
                        + "fetch_state,analysis_state,coverage_json,expected_count,accepted_count,excluded_count,"
                        + "pending_count,barrier_closed_at,result_json,manifest_hash FROM source_runs WHERE id=?",
                (rs, ignored) -> runRow(principal, bearer, permission, rs), runId);
        if (found.isEmpty()) throw new Refused(404, "not_found");
        return found.getFirst();
    }

    private ObjectNode runRow(Principal principal, String bearer, String permission, ResultSet rs) throws SQLException {
        String source = rs.getString("source_id");
        if (!principal.sourceIds().contains(source)) throw new Refused(403, "forbidden");
        nodes.authorizeBearer(bearer, permission, source);
        ObjectNode run = JSON.createObjectNode();
        run.put("run_id", rs.getString("id"));
        run.put("source_id", source);
        run.put("run_kind", rs.getString("run_kind"));
        run.put("business_date", rs.getDate("business_date").toLocalDate().toString());
        run.put("generation", rs.getLong("generation"));
        run.put("window_start", time(rs.getTimestamp("window_start").toInstant()));
        run.put("window_end", time(rs.getTimestamp("window_end").toInstant()));
        run.put("fetch_mode", rs.getString("fetch_mode"));
        run.put("collection_reason", rs.getString("collection_reason"));
        run.put("config_version", rs.getLong("config_version"));
        run.put("run_revision", rs.getLong("revision"));
        run.put("delivery_state", rs.getString("delivery_state"));
        run.put("fetch_state", rs.getString("fetch_state"));
        run.put("analysis_state", rs.getString("analysis_state"));
        JsonNode coverage = CanonicalJson.parse(rs.getString("coverage_json"));
        if (coverage.isObject() && coverage.isEmpty()) {
            ObjectNode initial = JSON.createObjectNode();
            initial.put("state", "no_input");
            initial.putArray("missing_inputs");
            initial.putArray("missing_dates");
            initial.putArray("no_update_dates");
            coverage = initial;
        }
        run.set("coverage", coverage);
        Number expected = (Number) rs.getObject("expected_count");
        if (expected == null) run.putNull("expected_count"); else run.put("expected_count", expected.longValue());
        run.put("accepted_count", rs.getLong("accepted_count"));
        run.put("excluded_count", rs.getLong("excluded_count"));
        run.put("pending_count", rs.getLong("pending_count"));
        Timestamp closed = rs.getTimestamp("barrier_closed_at");
        nullable(run, "barrier_closed_at", closed == null ? null : time(closed.toInstant()));
        run.put("late_results_available", false);
        String result = rs.getString("result_json");
        nullable(run, "result_business_hash", result == null ? null : businessHash("result", CanonicalJson.parse(result)));
        byte[] manifest = rs.getBytes("manifest_hash");
        nullable(run, "manifest_hash", manifest == null ? null : HexFormat.of().formatHex(manifest));
        return run;
    }

    private Result heartbeat(Principal principal, String bearer) {
        nodes.authorizeBearer(bearer, "heartbeat:write", null);
        ObjectNode data = JSON.createObjectNode();
        data.put("received_at", time(clock.instant()));
        return new Result(200, data, null);
    }

    private long runConfigVersion(String runId) {
        Long value = jdbc.queryForObject("SELECT config_version FROM source_runs WHERE id=?", Long.class, runId);
        if (value == null) throw new Refused(503, "temporarily_unavailable");
        return value;
    }

    private String runActivation(String runId) {
        String value = jdbc.queryForObject("SELECT activation_stage FROM source_runs WHERE id=?", String.class, runId);
        if (value == null) throw new Refused(503, "temporarily_unavailable");
        return value;
    }

    private JsonNode targetVersion(String runId) {
        String value = jdbc.queryForObject("SELECT target_version_json FROM source_runs WHERE id=?", String.class, runId);
        if (value != null) return CanonicalJson.parse(value);
        ObjectNode empty = JSON.createObjectNode();
        empty.putNull("revision");
        empty.putNull("updated_at");
        empty.putNull("evidence_ref");
        return empty;
    }

    private ObjectNode frozen(String sourceId, long version, String stage, String raw) {
        ObjectNode value = JSON.createObjectNode();
        value.put("source_id", sourceId);
        value.put("config_version", version);
        value.put("activation_stage", stage);
        value.set("config", CanonicalJson.parse(raw));
        return value;
    }

    private String receiptRef(String actor, String scope, String key) {
        List<String> refs = jdbc.query("SELECT id FROM request_receipts WHERE actor_id=? AND scope=? AND key_hash=?",
                (rs, ignored) -> rs.getString(1), actor, scope, digest(key));
        if (refs.size() != 1) throw new Refused(503, "temporarily_unavailable");
        return refs.getFirst();
    }

    private static ObjectNode control(String operation, JsonNode business, String runId, String probeId,
                                      Long revision, String state, boolean valid, Instant lease, String receiptRef) {
        ObjectNode result = JSON.createObjectNode();
        result.put("business_hash", businessHash(operation, business));
        nullable(result, "run_id", runId);
        nullable(result, "probe_id", probeId);
        if (revision == null) result.putNull("run_revision"); else result.put("run_revision", revision);
        nullable(result, "delivery_state", state);
        result.put("current_lease_valid", valid);
        nullable(result, "lease_until", lease == null ? null : time(lease));
        result.put("receipt_ref", receiptRef);
        return result;
    }

    private static String businessHash(String operation, JsonNode business) {
        ObjectNode value = JSON.createObjectNode();
        value.put("operation", operation);
        value.set("business", business);
        return CanonicalJson.hash(Domain.CONTROL_BUSINESS, value);
    }

    private static ActionContext action(String key, JsonNode body) {
        return new ActionContext(key, UUID.randomUUID().toString(),
                CanonicalJson.hash(Domain.ACTION, body));
    }

    private static String time(Instant value) { return MICROS.format(value); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static void nullable(ObjectNode target, String name, String value) {
        if (value == null) target.putNull(name); else target.put(name, value);
    }
}
