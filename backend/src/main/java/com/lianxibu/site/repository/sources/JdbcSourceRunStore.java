package com.lianxibu.site.repository.sources;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Persisted logical windows and current fenced run lease. */
public final class JdbcSourceRunStore {
    public record Row(String id, String sourceId, String runKind, LocalDate businessDate,
                      long generation, Instant windowStart, Instant windowEnd,
                      long configVersion, String configSnapshotJson, String activationStage,
                      long revision, String deliveryState, String claimNodeId,
                      byte[] claimTokenHash, byte[] claimTokenCiphertext,
                      String claimKeyId, Instant leaseUntil) {
        @Override public String toString() {
            return "SourceRunRow[id=" + id + ",state=" + deliveryState + "]";
        }
    }
    public record NewRun(String id, String sourceId, String runKind, LocalDate businessDate,
                         long generation, Instant windowStart, Instant windowEnd,
                         String baseRunId, long configVersion, String configSnapshotJson,
                         String activationStage, String fetchMode, String reason, Instant now) { }
    private static final String COLUMNS = "r.id,r.source_id,r.run_kind,r.business_date,r.generation,"
            + "r.window_start,r.window_end,r.config_version,r.config_snapshot_json,"
            + "r.activation_stage,r.revision,r.delivery_state,r.claim_node_id,r.claim_token_hash,"
            + "r.claim_token_ciphertext,r.claim_key_id,r.lease_until";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("id"), rs.getString("source_id"), rs.getString("run_kind"),
            rs.getDate("business_date").toLocalDate(), rs.getLong("generation"),
            instant(rs, "window_start"), instant(rs, "window_end"),
            rs.getLong("config_version"), rs.getString("config_snapshot_json"),
            rs.getString("activation_stage"), rs.getLong("revision"),
            rs.getString("delivery_state"), rs.getString("claim_node_id"),
            rs.getBytes("claim_token_hash"), rs.getBytes("claim_token_ciphertext"),
            rs.getString("claim_key_id"), instant(rs, "lease_until"));
    private final JdbcTemplate jdbc;

    public JdbcSourceRunStore(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source run data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row locked(String runId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r WHERE r.id=? FOR UPDATE",
                MAPPER, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Row byId(String runId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r WHERE r.id=?",
                MAPPER, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Row natural(String sourceId, String kind, LocalDate day, long generation) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM source_runs r"
                + " WHERE r.source_id=? AND r.run_kind=? AND r.business_date=? AND r.generation=?",
                MAPPER, sourceId, kind, java.sql.Date.valueOf(day), generation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long nextGeneration(String sourceId, String kind, LocalDate day) {
        Long current = jdbc.queryForObject("SELECT MAX(generation) FROM source_runs"
                + " WHERE source_id=? AND run_kind=? AND business_date=?", Long.class,
                sourceId, kind, java.sql.Date.valueOf(day));
        return current == null ? 1 : current + 1;
    }

    public void insert(NewRun value) {
        jdbc.update("INSERT INTO source_runs(id,created_at,revision,source_id,run_kind,"
                        + "business_date,generation,window_start,window_end,base_run_id,"
                        + "config_version,config_snapshot_json,activation_stage,fetch_mode,"
                        + "collection_reason,delivery_state,fetch_state,analysis_state,"
                        + "accepted_count,excluded_count,pending_count,coverage_json,job_refs_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), timestamp(value.now()), 0, value.sourceId(), value.runKind(),
                java.sql.Date.valueOf(value.businessDate()), value.generation(),
                timestamp(value.windowStart()), timestamp(value.windowEnd()), value.baseRunId(),
                value.configVersion(), value.configSnapshotJson(), value.activationStage(),
                value.fetchMode(), value.reason(), "pending", "pending", "waiting_input",
                0, 0, 0, "{}", "[]");
    }

    public Row claimable(List<String> sourceIds, String kind, Instant now) {
        if (sourceIds == null || sourceIds.isEmpty()) return null;
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.size(), "?"));
        String sql = "SELECT " + COLUMNS + " FROM source_runs r JOIN sources s ON s.id=r.source_id"
                + " WHERE r.source_id IN (" + placeholders + ") AND r.run_kind "
                + ("probe".equals(kind) ? "= 'probe'" : "<> 'probe'")
                + " AND r.delivery_state IN ('pending','claimed','receiving')"
                + " AND (r.lease_until IS NULL OR r.lease_until<=?)"
                + " AND s.config_revision=r.config_version"
                + " AND s.activation_stage=r.activation_stage"
                + " AND (r.run_kind='probe' OR s.config_valid_until>?)"
                + " ORDER BY r.business_date,r.id LIMIT 1 FOR UPDATE";
        List<Object> args = new ArrayList<>(sourceIds);
        args.add(timestamp(now));
        args.add(timestamp(now));
        List<Row> rows = jdbc.query(sql, MAPPER, args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean claim(Row row, String nodeId, byte[] tokenHash, byte[] ciphertext,
                         String keyId, Instant leaseUntil, Instant now) {
        return jdbc.update("UPDATE source_runs SET claim_node_id=?,claim_token_hash=?,"
                        + "claim_token_ciphertext=?,claim_key_id=?,lease_until=?,"
                        + "delivery_state='claimed',revision=revision+1 WHERE id=? AND revision=?"
                        + " AND delivery_state IN ('pending','claimed','receiving')"
                        + " AND (lease_until IS NULL OR lease_until<=?)",
                nodeId, tokenHash, ciphertext, keyId, timestamp(leaseUntil),
                row.id(), row.revision(), timestamp(now)) == 1;
    }

    public boolean acknowledge(Row row, String nodeId, byte[] tokenHash, Instant now) {
        return jdbc.update("UPDATE source_runs SET delivery_state='receiving',"
                        + "fetch_state='running',node_id=?,"
                        + "started_at=COALESCE(started_at,?),revision=revision+1"
                        + " WHERE id=? AND revision=? AND claim_node_id=? AND claim_token_hash=?"
                        + " AND lease_until>? AND delivery_state='claimed'",
                nodeId, timestamp(now), row.id(), row.revision(), nodeId, tokenHash,
                timestamp(now)) == 1;
    }

    public boolean renew(Row row, String nodeId, byte[] tokenHash, Instant leaseUntil, Instant now) {
        return jdbc.update("UPDATE source_runs SET lease_until=?,revision=revision+1"
                        + " WHERE id=? AND revision=? AND claim_node_id=? AND claim_token_hash=?"
                        + " AND lease_until>? AND delivery_state IN ('claimed','receiving')",
                timestamp(leaseUntil), row.id(), row.revision(), nodeId, tokenHash,
                timestamp(now)) == 1;
    }

    public boolean release(Row row, String nodeId, byte[] tokenHash, Instant now) {
        return jdbc.update("UPDATE source_runs SET claim_node_id=NULL,claim_token_hash=NULL,"
                        + "claim_token_ciphertext=NULL,claim_key_id=NULL,lease_until=NULL,"
                        + "delivery_state='pending',revision=revision+1 WHERE id=? AND revision=?"
                        + " AND claim_node_id=? AND claim_token_hash=? AND lease_until>?"
                        + " AND delivery_state IN ('claimed','receiving')",
                row.id(), row.revision(), nodeId, tokenHash, timestamp(now)) == 1;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
}
