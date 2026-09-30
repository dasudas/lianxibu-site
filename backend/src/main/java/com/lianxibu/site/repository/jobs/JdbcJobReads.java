package com.lianxibu.site.repository.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Safe metadata projections. Request, prompt, payload and receipt bodies are never selected. */
public final class JdbcJobReads {
    public record Row(String id, String recordKind, String taskType, String state, long revision,
                      int priority, Instant eligibleAt, String inputState, String coverageState,
                      String errorCode, long callCount, Long maxAttempts, Long attemptNo,
                      String usageJson, String costDecimal, Instant slotReleasedAt,
                      String releaseReason) {
        @Override public String toString() { return "JobRow[id=" + id + ",recordKind=" + recordKind + "]"; }
    }
    public record Receipt(String id, String receiptKind, String originalStatus,
                          String currentStatus, Instant createdAt, Instant expiresAt) { }
    public record Slot(String state, long revision, String occupiedJobId, Instant leaseUntil) { }
    public record Page(List<Row> items, long total) { }
    private static final String COLUMNS = "id,record_kind,task_type,state,revision,priority_no,"
            + "eligible_at,input_state,coverage_state,error_code,call_count,max_attempts,attempt_no,"
            + "usage_json,cost_decimal,slot_released_at,release_reason";
    private static final RowMapper<Row> JOB = (rs, ignored) -> new Row(rs.getString("id"),
            rs.getString("record_kind"), rs.getString("task_type"), rs.getString("state"),
            rs.getLong("revision"), rs.getInt("priority_no"), instant(rs, "eligible_at"),
            rs.getString("input_state"), rs.getString("coverage_state"),
            rs.getString("error_code"), rs.getLong("call_count"), nullableLong(rs, "max_attempts"),
            nullableLong(rs, "attempt_no"), rs.getString("usage_json"),
            rs.getString("cost_decimal"), instant(rs, "slot_released_at"),
            rs.getString("release_reason"));
    private final JdbcTemplate jdbc;

    public JdbcJobReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("job data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row job(String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM jobs WHERE id=?", JOB, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page jobs(String recordKind, long page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid job page");
        }
        String filter = recordKind == null ? "" : " WHERE record_kind=?";
        Object[] args = recordKind == null ? new Object[0] : new Object[] { recordKind };
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM jobs" + filter, Long.class, args);
        List<Row> rows = recordKind == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM jobs ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                        JOB, size, (page - 1) * size)
                : jdbc.query("SELECT " + COLUMNS + " FROM jobs" + filter
                        + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", JOB,
                        recordKind, size, (page - 1) * size);
        return new Page(rows, total == null ? 0 : total);
    }

    public Receipt receipt(String id) {
        List<Receipt> rows = jdbc.query("SELECT id,receipt_kind,original_status,current_status,"
                        + "created_at,expires_at FROM request_receipts WHERE id=?", (rs, ignored) ->
                new Receipt(rs.getString("id"), rs.getString("receipt_kind"),
                        rs.getString("original_status"), rs.getString("current_status"),
                        instant(rs, "created_at"), instant(rs, "expires_at")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Slot slot() {
        List<Slot> rows = jdbc.query("SELECT slot_state,slot_revision,job_id,lease_until "
                + "FROM worker_lock WHERE id=1", (rs, ignored) -> new Slot(
                rs.getString("slot_state"), rs.getLong("slot_revision"),
                rs.getString("job_id"), instant(rs, "lease_until")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
}
