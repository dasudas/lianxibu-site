package com.lianxibu.site.repository.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.domain.protocol.ObservationEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Permanent observation identity, bounded ACK, and run member evidence in one caller transaction. */
public final class JdbcObservationStore {
    public record Receipt(String id, String nodeId, String sourceId, String observationId,
                          String runId, String memberId, byte[] payloadHash, String status,
                          String ackJson) { }
    public record Member(String id, String runId, String memberId, String observationId,
                         String terminalState, String evidenceJson, long revision) { }
    private final JdbcTemplate jdbc;

    public JdbcObservationStore(DataSource source) {
        if (source == null) throw new IllegalArgumentException("observation data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Receipt receipt(String nodeId, String sourceId, String observationId) {
        List<Receipt> rows = jdbc.query("SELECT id,principal,source_id,observation_id,run_id,member_id,"
                        + "request_hash,original_status,ack_json FROM request_receipts"
                        + " WHERE actor_id=? AND scope=? AND key_hash=?"
                        + " AND receipt_kind='collector_observation'",
                (rs, ignored) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBytes(7),
                        rs.getString(8), rs.getString(9)),
                nodeId, scope(sourceId), sha(observationId));
        if (rows.size() > 1) throw new IllegalStateException("duplicate observation identity");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void saveReceipt(String id, String nodeId, ObservationEnvelope observation,
                            String runId, String status, String ackJson, Instant now) {
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,"
                        + "status_code,response_json,expires_at,id,receipt_kind,actor_id,source_id,scope,"
                        + "key_hash,run_id,member_id,observation_id,original_status,current_status,"
                        + "ack_json,created_at,revision) VALUES(?,?,?, ?,200,NULL,NULL,?,"
                        + "'collector_observation',?,?,?,?,?,?,?,?,?,?,?,0)",
                nodeId, "observation:" + HexFormat.of().formatHex(sha(observation.sourceId())),
                observation.observationId(),
                HexFormat.of().parseHex(observation.payloadHash()), id, nodeId,
                observation.sourceId(), scope(observation.sourceId()),
                sha(observation.observationId()), runId, observation.memberId(),
                observation.observationId(), status, status, ackJson, Timestamp.from(now));
    }

    public Member member(String runId, String memberId) {
        List<Member> rows = jdbc.query("SELECT id,run_id,member_id,observation_id,terminal_state,"
                        + "evidence_json,revision FROM source_run_items WHERE run_id=? AND member_id=? FOR UPDATE",
                (rs, ignored) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)),
                runId, memberId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean manifestStarted(String runId) {
        Boolean started = jdbc.queryForObject("SELECT CASE WHEN manifest_hash IS NOT NULL"
                + " OR part_count IS NOT NULL THEN TRUE ELSE FALSE END FROM source_runs"
                + " WHERE id=?", Boolean.class, runId);
        return Boolean.TRUE.equals(started);
    }

    public boolean itemIdentityTaken(String runId, String sourceId, String sourceItemId) {
        List<String> rows = jdbc.query("SELECT id FROM source_run_items WHERE run_id=?"
                        + " AND source_item_hash=? FOR UPDATE",
                (rs, ignored) -> rs.getString(1), runId, sourceItemHash(sourceId, sourceItemId));
        return !rows.isEmpty();
    }

    /** Preserve original evidence and receipt while freeing the current member identity. */
    public boolean supersede(Member old) {
        return jdbc.update("UPDATE source_run_items SET member_id=?,source_item_id=NULL,"
                        + "source_item_hash=NULL,terminal_state='excluded',state='excluded',"
                        + "exclusion_reason='superseded_observation',effective_valid=0,"
                        + "validity_revision=validity_revision+1,revision=revision+1"
                        + " WHERE id=? AND revision=? AND terminal_state='accepted'",
                "superseded:" + old.id(), old.id(), old.revision()) == 1;
    }

    public void insertMember(String id, String nodeId, ObservationEnvelope observation,
                             String category, String receiptId, String terminalState,
                             String evidenceJson, String articleId, String revisionId,
                             String projectionDecision, Instant now) {
        byte[] itemHash = sourceItemHash(observation.sourceId(), observation.sourceItemId());
        byte[] evidenceHash = evidenceJson == null ? null : sha(evidenceJson);
        JsonNode payload = observation.wire().get("payload");
        String order = "article".equals(observation.kind())
                ? new String(CanonicalJson.bytes(payload.get("source_order")), StandardCharsets.UTF_8) : null;
        jdbc.update("INSERT INTO source_run_items(id,created_at,revision,run_id,member_id,source_id,"
                        + "source_item_id,source_item_hash,category,observation_id,node_id,payload_hash,"
                        + "replaces_observation_id,evidence_json,evidence_hash,article_id,article_revision_id,"
                        + "terminal_state,receipt_ref,state,projection_decision,source_order_json,"
                        + "validity_revision,effective_valid) VALUES(?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "?,?,?, ?,?,0,1)",
                id, Timestamp.from(now), observation.runId(), observation.memberId(),
                observation.sourceId(), observation.sourceItemId(), itemHash, category,
                observation.observationId(), nodeId,
                HexFormat.of().parseHex(observation.payloadHash()),
                nullableText(observation.wire().get("replaces_observation_id")), evidenceJson,
                evidenceHash, articleId, revisionId, terminalState, receiptId,
                "needs_review".equals(projectionDecision) ? "needs_review" : "pending",
                projectionDecision, order);
    }

    public static byte[] sourceItemHash(String sourceId, String sourceItemId) {
        ObjectNode identity = JsonMapper.shared().createObjectNode();
        identity.put("source_id", sourceId);
        identity.put("source_item_id", sourceItemId);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.SOURCE_ITEM, identity));
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
    private static String scope(String sourceId) { return "observation:" + sourceId; }
    private static byte[] sha(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
