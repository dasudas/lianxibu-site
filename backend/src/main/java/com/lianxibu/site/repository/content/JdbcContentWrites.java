package com.lianxibu.site.repository.content;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Current-row content writes under the shared transaction entry lock. */
public final class JdbcContentWrites {
    public record Row(String kind, String id, String naturalKey, String title, String bodyJson,
                      String state, byte[] oldReviewHash, long revision, Instant updatedAt,
                      String contentKeyJson, byte[] periodKeyHash, String entityId,
                      Long bodyRevision, byte[] bodyHashV1, String inputJobId) { }
    public record NewContent(String kind, String id, String naturalKey, String title,
                             String bodyJson, String contentKeyJson, byte[] periodKeyHash,
                             String entityId, byte[] bodyHash, String inputJobId, Instant now) { }

    private static final String COLUMNS = "kind,id,natural_key,title,body_json,state,review_hash,"
            + "revision,updated_at,content_key_json,period_key_hash,entity_id,body_revision,"
            + "body_hash_v1,input_job_id";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("kind"), rs.getString("id"), rs.getString("natural_key"),
            rs.getString("title"), rs.getString("body_json"), rs.getString("state"),
            rs.getBytes("review_hash"), rs.getLong("revision"), instant(rs, "updated_at"),
            rs.getString("content_key_json"), rs.getBytes("period_key_hash"),
            rs.getString("entity_id"), nullableLong(rs, "body_revision"),
            rs.getBytes("body_hash_v1"), rs.getString("input_job_id"));
    private final JdbcTemplate jdbc;

    public JdbcContentWrites(DataSource source) {
        if (source == null) throw new IllegalArgumentException("content data source required");
        jdbc = new JdbcTemplate(source);
    }

    public void lockSharedEntry() {
        List<String> rows = jdbc.query("SELECT id FROM worker_lock FOR UPDATE",
                (rs, ignored) -> rs.getString(1));
        if (rows.size() != 1) throw new IllegalStateException("shared writer entry unavailable");
    }

    public List<Row> kindRows(String kind) {
        return jdbc.query("SELECT " + COLUMNS + " FROM contents WHERE kind=? ORDER BY id FOR UPDATE",
                MAPPER, kind);
    }

    public Row current(String kind, String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM contents WHERE kind=? AND id=? FOR UPDATE", MAPPER, kind, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void convert(Row old, String contentKeyJson, byte[] periodKeyHash,
                        String entityId, byte[] bodyHash) {
        int changed = jdbc.update("UPDATE contents SET content_key_json=?,period_key_hash=?,"
                        + "entity_id=?,body_revision=?,body_hash_v1=? WHERE kind=? AND id=?"
                        + " AND content_key_json IS NULL AND body_revision IS NULL AND revision=?",
                contentKeyJson, periodKeyHash, entityId, old.revision(), bodyHash,
                old.kind(), old.id(), old.revision());
        if (changed != 1) throw new IllegalStateException("legacy content changed during conversion");
    }

    public void insert(NewContent value) {
        jdbc.update("INSERT INTO contents(kind,id,natural_key,source_id,title,body_json,state,"
                        + "review_hash,revision,published_at,updated_at,entity_id,input_job_id,"
                        + "period_key_hash,body_revision,body_hash_v1,content_key_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.kind(), value.id(), value.naturalKey(), null, value.title(),
                value.bodyJson(), "draft", null, 1, null, Timestamp.from(value.now()),
                value.entityId(), value.inputJobId(), value.periodKeyHash(), 1,
                value.bodyHash(), value.contentKeyJson());
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
}
