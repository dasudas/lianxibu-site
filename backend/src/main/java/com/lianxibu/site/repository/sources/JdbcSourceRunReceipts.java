package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.RunActionReceipt;
import com.lianxibu.site.domain.sources.RunActionReceipt.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Stable action receipts; keys and lease tokens are never stored as plaintext token values. */
public final class JdbcSourceRunReceipts implements Store {
    private static final String ACTOR = "source-run";
    private static final String SCOPE = "source-run-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSourceRunReceipts(DataSource source, Clock clock) {
        if (source == null || clock == null) throw new IllegalArgumentException("receipt dependencies required");
        this.jdbc = new JdbcTemplate(source);
        this.clock = clock;
    }

    @Override public RunActionReceipt find(String actionKey) {
        List<RunActionReceipt> found = jdbc.query("SELECT request_key_raw,operation,ack_json,principal,run_id,"
                        + "business_hash,revision,result_ref_json FROM request_receipts WHERE actor_id=?"
                        + " AND scope=? AND key_hash=?",
                (rs, ignored) -> {
                    String leaseJson = rs.getString("result_ref_json");
                    String lease = leaseJson == null ? null : CanonicalJson.parse(leaseJson).asText();
                    return new RunActionReceipt(rs.getString("request_key_raw"), rs.getString("operation"),
                            CanonicalJson.parse(rs.getString("ack_json")).asText(),
                            "schedule".equals(rs.getString("operation")) ? null
                                    : rs.getString("principal"),
                            rs.getString("run_id"),
                            rs.getBytes("business_hash"), rs.getLong("revision"),
                            lease == null ? null : Instant.parse(lease));
                }, ACTOR, SCOPE, digest(actionKey));
        if (found.size() > 1) throw new IllegalStateException("duplicate source action receipt");
        if (found.isEmpty()) return null;
        return found.getFirst();
    }

    @Override public void save(RunActionReceipt value) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("source action receipt requires write transaction");
        }
        Instant now = clock.instant();
        String kind = "schedule".equals(value.operation()) ? "admin_action" : "collector_control";
        String scopeJson = JSON.writeValueAsString(value.requestScope());
        String leaseJson = value.originalLeaseUntil() == null ? null
                : JSON.writeValueAsString(value.originalLeaseUntil().toString());
        byte[] businessHash = value.claimHash() == null ? digest(value.requestScope()) : value.claimHash();
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,"
                        + "status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,"
                        + "run_id,original_status,current_status,ack_json,result_ref_json,created_at,revision,"
                        + "request_key_raw,business_hash,control_operation)"
                        + " VALUES(?,?,?,?,200,NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.nodeId() == null ? ACTOR : value.nodeId(), value.operation(),
                value.actionKey(), digest(value.requestScope()),
                Timestamp.from(now.plusSeconds(30L * 24 * 60 * 60)),
                UUID.randomUUID().toString(), kind, ACTOR, SCOPE, digest(value.actionKey()),
                value.runId(), "applied", "applied", scopeJson, leaseJson,
                Timestamp.from(now), value.resultRevision(), value.actionKey(),
                businessHash, "schedule".equals(value.operation()) ? null : value.operation());
    }

    private static byte[] digest(String value) {
        if (value == null) throw new IllegalArgumentException("receipt identity required");
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
