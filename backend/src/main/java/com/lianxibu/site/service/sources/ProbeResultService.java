package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Permanently records a probe result without granting trial or production admission. */
public final class ProbeResultService {
    public record Outcome(String probeId, String businessHash, long revision, String deliveryState,
                          Instant originalLeaseUntil, String receiptRef) { }
    public static final class ProbeRejected extends RuntimeException {
        public ProbeRejected() { super("probe result or lease rejected"); }
    }
    private record Run(String id, String sourceId, String kind, long revision, String state,
                       String nodeId, byte[] claimHash, Instant leaseUntil, long configVersion,
                       String stage, String resultJson) { }
    private record Receipt(String id, byte[] attemptHash, byte[] businessHash, long revision) { }

    private final JdbcTemplate jdbc;
    private final SharedWriteExecutor writes;
    private final NodeIdentityService nodes;
    private final Clock clock;

    public ProbeResultService(DataSource source, SharedWriteExecutor writes,
                              NodeIdentityService nodes, Clock clock) {
        if (source == null || writes == null || nodes == null || clock == null)
            throw new IllegalArgumentException("probe dependencies required");
        jdbc = new JdbcTemplate(source);
        this.writes = writes;
        this.nodes = nodes;
        this.clock = clock;
    }

    public Outcome submit(String bearer, String probeId, JsonNode business, JsonNode attempt,
                          ActionContext action) {
        validate(probeId, business, attempt);
        String businessHash = businessHash(business);
        String token = attempt.get("claim_token").asText();
        Run initial = required(probeId, false);
        String nodeId = nodes.authorizeBearer(bearer, "probe:write", initial.sourceId()).nodeId();
        byte[] attemptHash = attemptHash(nodeId, businessHash, attempt);
        String scope = "probe_result:" + probeId;
        return writes.execute(action, key -> {
            Run current = required(probeId, false);
            nodes.authorizeBearer(bearer, "probe:write", current.sourceId());
            Receipt previous = receipt(nodeId, scope, key);
            if (previous == null) return null;
            if (!MessageDigest.isEqual(previous.attemptHash(), attemptHash)
                    || !MessageDigest.isEqual(previous.businessHash(), HexFormat.of().parseHex(businessHash)))
                throw new ProbeRejected();
            return new Outcome(probeId, businessHash, previous.revision(), current.state(),
                    current.leaseUntil(), previous.id());
        }, context -> {
            Run current = required(probeId, true);
            nodes.authorizeBearer(bearer, "probe:write", current.sourceId());
            if (!"probe".equals(current.kind()) || !nodeId.equals(current.nodeId())
                    || current.claimHash() == null
                    || !MessageDigest.isEqual(current.claimHash(), RunLeaseTokenCodec.digest(token)))
                throw new ProbeRejected();
            Receipt fixed = fixed(scope);
            if (fixed != null) {
                if (!MessageDigest.isEqual(fixed.businessHash(), HexFormat.of().parseHex(businessHash)))
                    throw new ProbeRejected();
                return new Outcome(probeId, businessHash, fixed.revision(), current.state(),
                        current.leaseUntil(), fixed.id());
            }
            if (current.resultJson() != null || current.leaseUntil() == null
                    || !current.leaseUntil().isAfter(clock.instant())
                    || !Set.of("claimed", "receiving").contains(current.state())) throw new ProbeRejected();
            List<Boolean> sourceMatches = jdbc.query("SELECT config_revision,activation_stage FROM sources"
                            + " WHERE id=? FOR UPDATE", (rs, ignored) -> current.configVersion() == rs.getLong(1)
                            && current.stage().equals(rs.getString(2)), current.sourceId());
            if (sourceMatches.size() != 1 || !sourceMatches.getFirst()) throw new ProbeRejected();
            JsonNode result = business.get("result");
            boolean valid = result.get("reachable").booleanValue() && result.get("tls_valid").booleanValue()
                    && result.get("parser_valid").booleanValue() && result.get("identity_valid").booleanValue();
            String state = valid ? "complete" : "no_usable_input";
            String fetch = result.get("reachable").booleanValue() ? "succeeded" : "failed";
            ObjectNode coverage = JsonMapper.shared().createObjectNode();
            coverage.put("state", valid ? "complete" : "no_input");
            coverage.putArray("missing_inputs");
            coverage.putArray("missing_dates");
            coverage.putArray("no_update_dates");
            Instant now = clock.instant();
            int changed = jdbc.update("UPDATE source_runs SET probe_trial_result_json=?,delivery_state=?,"
                            + "fetch_state=?,coverage_json=?,finished_at=?,barrier_closed_at=?,revision=revision+1"
                            + " WHERE id=? AND revision=? AND run_kind='probe' AND probe_trial_result_json IS NULL",
                    new String(CanonicalJson.bytes(business), StandardCharsets.UTF_8), state, fetch,
                    new String(CanonicalJson.bytes(coverage), StandardCharsets.UTF_8),
                    Timestamp.from(now), Timestamp.from(now), probeId, current.revision());
            if (changed != 1) throw new ProbeRejected();
            long revision = current.revision() + 1;
            String receiptId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,"
                            + "status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,"
                            + "run_id,original_status,current_status,ack_json,created_at,revision,request_key_raw,"
                            + "business_hash,control_operation) VALUES(?,?,?, ?,200,NULL,NULL,?,'collector_control',"
                            + "?,?,?,?,?,?,?, ?,?,?,?,?)",
                    nodeId, "probe_result", context.actionKey(), attemptHash, receiptId, nodeId,
                    scope, digest(context.actionKey()), probeId, "applied", "applied",
                    new String(CanonicalJson.bytes(business), StandardCharsets.UTF_8),
                    Timestamp.from(now), revision, context.actionKey(),
                    HexFormat.of().parseHex(businessHash), "probe_result");
            return new Outcome(probeId, businessHash, revision, state, current.leaseUntil(), receiptId);
        });
    }

    private Run required(String probeId, boolean lock) {
        List<Run> rows = jdbc.query("SELECT id,source_id,run_kind,revision,delivery_state,claim_node_id,"
                        + "claim_token_hash,lease_until,config_version,activation_stage,probe_trial_result_json"
                        + " FROM source_runs WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, ignored) -> new Run(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getBytes(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                        rs.getLong(9), rs.getString(10), rs.getString(11)), probeId);
        if (rows.size() != 1) throw new ProbeRejected();
        return rows.getFirst();
    }

    private Receipt receipt(String nodeId, String scope, String key) {
        List<Receipt> rows = jdbc.query("SELECT id,request_hash,business_hash,revision FROM request_receipts"
                        + " WHERE actor_id=? AND scope=? AND key_hash=? AND receipt_kind='collector_control'",
                (rs, ignored) -> new Receipt(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getLong(4)),
                nodeId, scope, digest(key));
        if (rows.size() > 1) throw new ProbeRejected();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Receipt fixed(String scope) {
        List<Receipt> rows = jdbc.query("SELECT id,request_hash,business_hash,revision FROM request_receipts"
                        + " WHERE scope=? AND receipt_kind='collector_control'",
                (rs, ignored) -> new Receipt(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getLong(4)), scope);
        if (rows.size() > 1) throw new ProbeRejected();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void validate(String probeId, JsonNode business, JsonNode attempt) {
        if (business == null || attempt == null) throw new ProbeRejected();
        WireValues.object(business, Set.of("probe_id", "result"), Set.of("probe_id", "result"));
        if (!probeId.equals(WireValues.id(business.get("probe_id"), false))) throw new ProbeRejected();
        JsonNode result = WireValues.object(business.get("result"),
                Set.of("reachable", "http_status", "tls_valid", "parser_valid", "identity_valid",
                        "checked_at", "error_code", "evidence_refs"),
                Set.of("reachable", "http_status", "tls_valid", "parser_valid", "identity_valid",
                        "checked_at", "error_code", "evidence_refs"));
        for (String field : List.of("reachable", "tls_valid", "parser_valid", "identity_valid"))
            if (!result.get(field).isBoolean()) throw new ProbeRejected();
        JsonNode status = result.get("http_status");
        if (!status.isNull() && (!status.isIntegralNumber() || status.intValue() < 100 || status.intValue() > 599))
            throw new ProbeRejected();
        WireValues.utcMicroseconds(result.get("checked_at"));
        JsonNode code = result.get("error_code");
        if (!code.isNull() && (!code.isTextual() || !code.asText().matches("[A-Za-z0-9._:-]{1,64}")))
            throw new ProbeRejected();
        JsonNode refs = result.get("evidence_refs");
        if (!refs.isArray() || refs.size() > 32) throw new ProbeRejected();
        for (JsonNode ref : refs)
            if (!ref.isTextual() || ref.asText().length() > 2000 || !ref.asText().startsWith("https://"))
                throw new ProbeRejected();
        WireValues.object(attempt, Set.of("claim_token", "expected_run_revision"),
                Set.of("claim_token", "expected_run_revision"));
        WireValues.id(attempt.get("claim_token"), false);
        if (!attempt.get("expected_run_revision").isNull())
            WireValues.nonnegativeInteger(attempt.get("expected_run_revision"));
    }

    private static String businessHash(JsonNode business) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("operation", "probe_result");
        value.set("business", business);
        return CanonicalJson.hash(Domain.CONTROL_BUSINESS, value);
    }

    private static byte[] attemptHash(String nodeId, String businessHash, JsonNode attempt) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("node_id", nodeId);
        value.put("operation", "probe_result");
        value.put("business_hash", businessHash);
        value.set("attempt", attempt);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.CONTROL_ATTEMPT, value));
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
