package com.lianxibu.site.repository.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The live collector_nodes and request_receipts layout; no raw node token is persisted. */
public final class JdbcNodeStore {
    public record Row(String id, String name, String sourceIdsJson, String permissionsJson,
                      long revision, boolean enabled, Instant lastSeenAt) { }
    public record Receipt(String id, byte[] requestHash, String responseJson, Instant expiresAt,
                          String resultRefJson) { }

    private static final RowMapper<Row> NODE = (rs, row) -> {
        Timestamp seen = rs.getTimestamp("last_seen_at");
        return new Row(rs.getString("id"), rs.getString("name"), rs.getString("allowed_source_ids"),
                rs.getString("permissions_json"), rs.getLong("config_revision"),
                rs.getBoolean("enabled"), seen == null ? null : seen.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcNodeStore(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("data source required");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /** Serializes an owner's receipt check and write, including two simultaneous create requests. */
    public boolean lockActor(String actorId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), actorId));
    }

    public Receipt receipt(String actorId, String scope, byte[] keyHash) {
        return jdbc.query("SELECT id, request_hash, response_json, expires_at, result_ref_json FROM request_receipts "
                        + "WHERE actor_id = ? AND scope = ? AND key_hash = ? AND receipt_kind = 'admin_action'",
                rs -> {
                    if (!rs.next()) return null;
                    Timestamp expiry = rs.getTimestamp(4);
                    return new Receipt(rs.getString(1), rs.getBytes(2), rs.getString(3),
                            expiry == null ? null : expiry.toInstant(), rs.getString(5));
                }, actorId, scope, keyHash);
    }

    public void saveReceipt(String actorId, String scope, String key, byte[] keyHash, byte[] requestHash,
                            String responseJson, String resultRefJson, Instant now) {
        jdbc.update("INSERT INTO request_receipts (principal, operation, request_key, request_hash, "
                        + "status_code, response_json, expires_at, id, receipt_kind, actor_id, scope, key_hash, "
                        + "original_status, current_status, result_ref_json, created_at, revision) "
                        + "VALUES (?, ?, ?, ?, 200, ?, ?, ?, 'admin_action', ?, ?, ?, 'applied', 'applied', ?, ?, 1)",
                actorId, scope, key, requestHash, responseJson,
                Timestamp.from(now.plusSeconds(30L * 24 * 60 * 60)), UUID.randomUUID().toString(),
                actorId, scope, keyHash, resultRefJson, Timestamp.from(now));
    }

    public boolean sourceExists(String sourceId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT id FROM sources WHERE id = ?",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), sourceId));
    }

    public void create(String id, String name, byte[] tokenHash, String sourcesJson, String permissionsJson) {
        jdbc.update("INSERT INTO collector_nodes "
                        + "(id, name, token_hash, allowed_source_ids, enabled, last_seen_at, "
                        + "config_revision, permissions_json) VALUES (?, ?, ?, ?, 1, NULL, 1, ?)",
                id, name, tokenHash, sourcesJson, permissionsJson);
    }

    public Row lockNode(String id) {
        List<Row> found = jdbc.query("SELECT id, name, allowed_source_ids, permissions_json, "
                + "config_revision, enabled, last_seen_at FROM collector_nodes WHERE id = ? FOR UPDATE", NODE, id);
        return found.isEmpty() ? null : found.getFirst();
    }

    public Row byTokenHash(byte[] hash) {
        List<Row> found = jdbc.query("SELECT id, name, allowed_source_ids, permissions_json, "
                + "config_revision, enabled, last_seen_at FROM collector_nodes WHERE token_hash = ?", NODE, hash);
        return found.isEmpty() ? null : found.getFirst();
    }

    public int rotate(String id, long revision, byte[] tokenHash) {
        return jdbc.update("UPDATE collector_nodes SET token_hash = ?, config_revision = config_revision + 1 "
                        + "WHERE id = ? AND config_revision = ? AND enabled = 1", tokenHash, id, revision);
    }

    public int revoke(String id, long revision) {
        return jdbc.update("UPDATE collector_nodes SET enabled = 0, config_revision = config_revision + 1 "
                        + "WHERE id = ? AND config_revision = ? AND enabled = 1", id, revision);
    }

    public int setPermissions(String id, long revision, String sourcesJson, String permissionsJson) {
        return jdbc.update("UPDATE collector_nodes SET allowed_source_ids = ?, permissions_json = ?, "
                        + "config_revision = config_revision + 1 WHERE id = ? AND config_revision = ? "
                        + "AND enabled = 1", sourcesJson, permissionsJson, id, revision);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM collector_nodes", Long.class);
        return value == null ? 0 : value;
    }

    public List<Row> page(long offset, int pageSize) {
        return jdbc.query("SELECT id, name, allowed_source_ids, permissions_json, "
                        + "config_revision, enabled, last_seen_at FROM collector_nodes ORDER BY id LIMIT ? OFFSET ?",
                NODE, pageSize, offset);
    }
}
