package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded current monitoring object; no business-table cleanup. */
public final class JdbcOperationalSummaries {
    public static final String SETTING_KEY = "ops_summary-v1";
    public record Summary(long revision, String valueJson) { }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("operational summary revision conflict"); }
    }
    private final JdbcTemplate jdbc;
    public JdbcOperationalSummaries(DataSource source) {
        if (source == null) throw new IllegalArgumentException("operational data source required");
        jdbc = new JdbcTemplate(source);
    }
    public Summary read() { return select(false); }
    public Summary locked() { requireTx(); return select(true); }
    private Summary select(boolean lock) {
        List<Summary> rows = jdbc.query("SELECT revision,value_json FROM runtime_settings WHERE setting_key=?"
                + (lock ? " FOR UPDATE" : ""), (rs, ignored) -> new Summary(rs.getLong(1), rs.getString(2)), SETTING_KEY);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void replace(long expectedRevision, String json, Instant now) {
        requireTx();
        if (expectedRevision < 0 || expectedRevision >= CanonicalJson.MAX_SAFE_INTEGER || now == null
                || json == null || json.getBytes(StandardCharsets.UTF_8).length > 262144
                || !CanonicalJson.parse(json).isObject()) throw new IllegalArgumentException("invalid operational summary");
        if (expectedRevision == 0) {
            if (read() != null) throw new Conflict();
            jdbc.update("INSERT INTO runtime_settings(setting_key,value_json,revision,updated_at) VALUES(?,?,1,?)",
                    SETTING_KEY, json, Timestamp.from(now));
        } else if (jdbc.update("UPDATE runtime_settings SET value_json=?,revision=revision+1,updated_at=?"
                        + " WHERE setting_key=? AND revision=?", json, Timestamp.from(now), SETTING_KEY, expectedRevision) != 1)
            throw new Conflict();
    }
    public String auditPayload(byte[] actionKeyHash) {
        List<String> rows = jdbc.query("SELECT action_payload FROM jobs WHERE record_kind='disposition'"
                + " AND target_type='ops_rule' AND action_key_hash=?", (rs, ignored) -> rs.getString(1), actionKeyHash);
        if (rows.size() > 1) throw new IllegalStateException("duplicate operational action receipt");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static void requireTx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("operational write requires shared transaction");
    }
}
