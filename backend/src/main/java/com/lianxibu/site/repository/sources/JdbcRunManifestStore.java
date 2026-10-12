package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Calendar;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SQL persistence for manifest parts, placeholders, result intent and derived counts. */
public final class JdbcRunManifestStore {
    public record Run(String id, String sourceId, String deliveryState, long revision,
                      String claimNodeId, byte[] claimHash, Instant leaseUntil,
                      Long partCount, Long resultPartCount, String resultJson,
                      long accepted, long excluded, long pending) { }
    public record Receipt(byte[] attemptHash, byte[] businessHash, String json) { }
    public record PartReceipt(long partNo, String hash, int memberCount) { }
    public record Counts(long total, long accepted, long excluded, long pending) { }
    private final JdbcTemplate jdbc;

    public JdbcRunManifestStore(DataSource source) {
        if (source == null) throw new IllegalArgumentException("manifest data source required");
        jdbc = new JdbcTemplate(source);
    }
    public Run run(String id, boolean lock) {
        List<Run> rows = jdbc.query("SELECT id,source_id,delivery_state,revision,claim_node_id,"
                        + "claim_token_hash,lease_until,part_count,result_json,accepted_count,"
                        + "excluded_count,pending_count FROM source_runs WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""), (rs, ignored) -> {
                    String result = rs.getString("result_json");
                    JsonNode parsed = result == null ? null : CanonicalJson.parse(result);
                    Number parts = (Number) rs.getObject("part_count");
                    return new Run(rs.getString("id"), rs.getString("source_id"),
                            rs.getString("delivery_state"), rs.getLong("revision"),
                            rs.getString("claim_node_id"), rs.getBytes("claim_token_hash"),
                            timestamp(rs.getTimestamp("lease_until", utc())), parts == null ? null : parts.longValue(),
                            parsed == null ? null : parsed.get("part_count").longValue(), result,
                            rs.getLong("accepted_count"), rs.getLong("excluded_count"),
                            rs.getLong("pending_count"));
                }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Receipt receipt(String nodeId, String scope, String actionKey) {
        List<Receipt> rows = jdbc.query("SELECT request_hash,business_hash,ack_json FROM request_receipts"
                        + " WHERE actor_id=? AND scope=? AND key_hash=? AND receipt_kind='collector_control'",
                (rs, ignored) -> new Receipt(rs.getBytes(1), rs.getBytes(2), rs.getString(3)),
                nodeId, scope, sha(actionKey));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Receipt fixed(String scope) {
        List<Receipt> rows = jdbc.query("SELECT request_hash,business_hash,ack_json FROM request_receipts"
                        + " WHERE scope=? AND receipt_kind='collector_control'",
                (rs, ignored) -> new Receipt(rs.getBytes(1), rs.getBytes(2), rs.getString(3)), scope);
        if (rows.size() > 1) throw new IllegalStateException("multiple frozen manifest business identities");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void saveReceipt(String nodeId, String runId, String scope, String actionKey, byte[] attemptHash,
                            byte[] businessHash, String json, Instant now) {
        String operation = scope.substring(0, scope.indexOf(':'));
        mutate("INSERT INTO request_receipts(principal,operation,request_key,request_hash,"
                        + "status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,"
                        + "run_id,original_status,current_status,ack_json,created_at,revision,request_key_raw,"
                        + "business_hash,control_operation) VALUES(?,?,?,?,200,NULL,NULL,?,"
                        + "'collector_control',?,?,?,?,?,?,?, ?,0,?,?,?)",
                nodeId, operation, actionKey,
                attemptHash, UUID.randomUUID().toString(), nodeId, scope, sha(actionKey),
                runId, "applied", "applied", json,
                Timestamp.from(now), actionKey, businessHash, operation);
    }
    public void markManifestStarted(String runId) {
        jdbc.update("UPDATE source_runs SET part_count=0,revision=revision+1"
                + " WHERE id=? AND part_count IS NULL", runId);
    }
    public void attachMember(Run run, String memberId, String sourceItemId,
                             String observationId, String payloadHash,
                             String replacesObservationId, long partNo, String nodeId, Instant now) {
        List<Object[]> rows = jdbc.query("SELECT id,source_item_id,observation_id,payload_hash,"
                        + "replaces_observation_id,part_no FROM source_run_items"
                        + " WHERE run_id=? AND member_id=? FOR UPDATE",
                (rs, ignored) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBytes(4), rs.getString(5), rs.getObject(6)}, run.id(), memberId);
        byte[] payload = HexFormat.of().parseHex(payloadHash);
        if (!rows.isEmpty()) {
            Object[] old = rows.getFirst();
            if (!java.util.Objects.equals(old[1], sourceItemId)
                    || !old[2].equals(observationId)
                    || !MessageDigest.isEqual((byte[]) old[3], payload)
                    || !java.util.Objects.equals(old[4], replacesObservationId)
                    || old[5] != null) throw new IllegalStateException("manifest member conflict");
            jdbc.update("UPDATE source_run_items SET part_no=?,revision=revision+1 WHERE id=?",
                    partNo, old[0]);
            return;
        }
        byte[] sourceHash = sourceItemId == null ? null : sourceItemHash(run.sourceId(), sourceItemId);
        mutate("INSERT INTO source_run_items(id,created_at,revision,run_id,member_id,source_id,"
                        + "source_item_id,source_item_hash,observation_id,node_id,payload_hash,"
                        + "replaces_observation_id,part_no,terminal_state,state,validity_revision,effective_valid)"
                        + " VALUES(?,?,0,?,?,?,?,?,?,?,?,?,?,'pending','pending',0,1)",
                UUID.randomUUID().toString(), Timestamp.from(now), run.id(), memberId,
                run.sourceId(), sourceItemId, sourceHash, observationId,
                nodeId, payload, replacesObservationId, partNo);
    }
    public List<PartReceipt> parts(String runId) {
        return jdbc.query("SELECT ack_json FROM request_receipts WHERE run_id=?"
                        + " AND control_operation='manifest'"
                        + " AND receipt_kind='collector_control' ORDER BY scope",
                (rs, ignored) -> {
                    JsonNode part = CanonicalJson.parse(rs.getString(1));
                    return new PartReceipt(part.get("part_no").longValue(),
                            part.get("part_hash").asText(), part.get("members").size());
                }, runId).stream()
                .sorted(java.util.Comparator.comparingLong(PartReceipt::partNo)).toList();
    }
    public void saveResult(String runId, byte[] manifestHash, long parts, long expected,
                           String fetch, String coverage, String json) {
        jdbc.update("UPDATE source_runs SET manifest_hash=?,part_count=?,expected_count=?,"
                        + "fetch_state=?,coverage_json=?,result_json=?,delivery_state='waiting_items',"
                        + "revision=revision+1 WHERE id=?",
                manifestHash, parts, expected, fetch, coverage, json, runId);
    }
    public Counts counts(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*),"
                        + "SUM(CASE WHEN terminal_state='accepted' THEN 1 ELSE 0 END),"
                        + "SUM(CASE WHEN terminal_state='excluded' THEN 1 ELSE 0 END),"
                        + "SUM(CASE WHEN terminal_state='pending' THEN 1 ELSE 0 END)"
                        + " FROM source_run_items WHERE run_id=? AND part_no IS NOT NULL",
                (rs, ignored) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), runId);
    }
    public boolean seal(String runId, String memberId, long expectedRevision, String reason) {
        return jdbc.update("UPDATE source_run_items SET terminal_state='excluded',state='excluded',"
                        + "exclusion_reason=?,revision=revision+1 WHERE run_id=? AND member_id=?"
                        + " AND revision=? AND terminal_state='pending'", reason, runId,
                memberId, expectedRevision) == 1;
    }
    public void updateCounts(String runId, String state, long accepted, long excluded,
                             long pending, Instant finished) {
        mutate("UPDATE source_runs SET delivery_state=?,accepted_count=?,excluded_count=?,"
                        + "pending_count=?,finished_at=?,revision=revision+1 WHERE id=?",
                state, accepted, excluded, pending,
                finished == null ? null : Timestamp.from(finished), runId);
    }
    private static Instant timestamp(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private int mutate(String sql, Object... args) {
        return jdbc.update(sql, ps -> {
            for (int i=0;i<args.length;i++) {
                if(args[i] instanceof Timestamp time) ps.setTimestamp(i+1,time,utc());
                else ps.setObject(i+1,args[i]);
            }
        });
    }
    private static byte[] sourceItemHash(String sourceId, String sourceItemId) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("source_id", sourceId);
        value.put("source_item_id", sourceItemId);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.SOURCE_ITEM, value));
    }
    private static byte[] sha(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
