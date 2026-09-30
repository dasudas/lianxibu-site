package com.lianxibu.site.repository.content;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Current-row reads only. The legacy JSON and composite identity are never rewritten. */
public final class JdbcContentReads {
    public record Row(String kind, String id, String naturalKey, String title, String bodyJson,
                      String state, long revision, Long bodyRevision, Instant publishedAt,
                      String inputJobId, byte[] bodyHashV1, byte[] reviewHashV1,
                      String reviewDependencyStamp, byte[] reviewStampHash, String reviewStampId,
                      byte[] reviewedInputHash, String blockingSummaryJson) {
        public long effectiveBodyRevision() { return bodyRevision == null ? revision : bodyRevision; }
    }

    private static final String COLUMNS = "kind,id,natural_key,title,body_json,state,revision,body_revision,"
            + "published_at,input_job_id,body_hash_v1,review_hash_v1,review_dependency_stamp,"
            + "review_stamp_hash,review_stamp_id,reviewed_input_hash,blocking_summary_json";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("kind"), rs.getString("id"), rs.getString("natural_key"),
            rs.getString("title"), rs.getString("body_json"), rs.getString("state"),
            rs.getLong("revision"), nullableLong(rs, "body_revision"),
            instant(rs, "published_at"), rs.getString("input_job_id"),
            rs.getBytes("body_hash_v1"), rs.getBytes("review_hash_v1"),
            rs.getString("review_dependency_stamp"), rs.getBytes("review_stamp_hash"),
            rs.getString("review_stamp_id"), rs.getBytes("reviewed_input_hash"),
            rs.getString("blocking_summary_json"));
    private final JdbcTemplate jdbc;

    public JdbcContentReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("content data source required");
        this.jdbc = new JdbcTemplate(source);
    }

    public Row current(String kind, String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM contents WHERE kind=? AND id=?",
                MAPPER, kind, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Stable scan used when eligibility depends on references outside the content row. */
    public List<Row> publishedCandidates(String kind, String afterKind, String afterId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid scan limit");
        String filter = kind == null ? "" : " AND kind=?";
        String sql = "SELECT " + COLUMNS + " FROM contents WHERE state='published'" + filter
                + " AND (kind>? OR (kind=? AND id>?)) ORDER BY kind,id LIMIT ?";
        return kind == null
                ? jdbc.query(sql, MAPPER, afterKind, afterKind, afterId, limit)
                : jdbc.query(sql, MAPPER, kind, afterKind, afterKind, afterId, limit);
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
