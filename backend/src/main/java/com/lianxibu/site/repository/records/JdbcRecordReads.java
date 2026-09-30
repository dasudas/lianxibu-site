package com.lianxibu.site.repository.records;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Current private record reads always bind the authenticated user and full object key. */
public final class JdbcRecordReads {
    public record Row(String userId, String packageId, String objectKind, String objectId,
                      long revision, String status, String selfAssessment, String note,
                      String artifactUrl, String responseText, Long confirmedBodyRevision,
                      Instant updatedAt, boolean isRead, boolean isFavorite, boolean isIgnored) { }
    public record Page(List<Row> items, long total) { }
    private static final String COLUMNS = "user_id,package_id,object_kind,object_id,revision,status,"
            + "self_assessment,note,artifact_url,response_text,confirmed_body_revision,updated_at,"
            + "is_read,is_favorite,is_ignored";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("user_id"), rs.getString("package_id"), rs.getString("object_kind"),
            rs.getString("object_id"), rs.getLong("revision"), rs.getString("status"),
            rs.getString("self_assessment"), rs.getString("note"), rs.getString("artifact_url"),
            rs.getString("response_text"), nullableLong(rs, "confirmed_body_revision"),
            instant(rs, "updated_at"), rs.getBoolean("is_read"), rs.getBoolean("is_favorite"),
            rs.getBoolean("is_ignored"));
    private final JdbcTemplate jdbc;

    public JdbcRecordReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("record data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row current(String userId, String kind, String packageId, String objectId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM user_records WHERE user_id=? "
                + "AND object_kind=? AND package_id=? AND object_id=?", MAPPER,
                userId, kind, packageId, objectId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page rows(String userId, String packageId, String kind, long page, int size) {
        StringBuilder filter = new StringBuilder(" WHERE user_id=? AND package_id=?");
        List<Object> args = new ArrayList<>(List.of(userId, packageId));
        if (kind != null) { filter.append(" AND object_kind=?"); args.add(kind); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM user_records" + filter,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM user_records" + filter
                + " ORDER BY updated_at DESC,object_kind,object_id LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new Page(rows, total == null ? 0 : total);
    }

    private static long offset(long page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid page");
        }
        return (page - 1) * size;
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
