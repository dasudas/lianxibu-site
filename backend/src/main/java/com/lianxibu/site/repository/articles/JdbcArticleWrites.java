package com.lianxibu.site.repository.articles;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Article compatibility writes; callers must hold the shared short-transaction entry lock. */
public final class JdbcArticleWrites {
    public record Row(String id, String sourceId, String externalId, byte[] externalKey,
                      String title, String url, Instant publishedAt, String summary, String body,
                      String factsJson, byte[] oldContentHash, long revision, Instant updatedAt,
                      String sourceItemId, byte[] sourceItemHash, String latestRevisionId) { }
    public record NewArticle(String id, String sourceId, String sourceItemId, byte[] sourceItemHash,
                             byte[] externalKey, String title, String url, Instant publishedAt,
                             String summary, String body, String factsJson, byte[] contentHash,
                             Instant now) { }
    public record Revision(String id, String articleId, byte[] contentHash, String title,
                           String summary, String body, Instant sourceTime, String timeBasis,
                           String contentStatus, boolean bodyTruncated, String parserVersion,
                           long configVersion, String sourceOrderJson, boolean legacy,
                           Instant now) { }

    private static final String COLUMNS = "id,source_id,external_id,external_key,title,url,published_at,"
            + "summary,body_text,facts_json,content_hash,revision,updated_at,source_item_id,"
            + "source_item_hash,latest_revision_id";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("id"), rs.getString("source_id"), rs.getString("external_id"),
            rs.getBytes("external_key"), rs.getString("title"), rs.getString("url"),
            instant(rs, "published_at"), rs.getString("summary"), rs.getString("body_text"),
            rs.getString("facts_json"), rs.getBytes("content_hash"), rs.getLong("revision"),
            instant(rs, "updated_at"), rs.getString("source_item_id"),
            rs.getBytes("source_item_hash"), rs.getString("latest_revision_id"));
    private final JdbcTemplate jdbc;

    public JdbcArticleWrites(DataSource source) {
        if (source == null) throw new IllegalArgumentException("article data source required");
        jdbc = new JdbcTemplate(source);
    }

    public void lockSharedEntry() {
        List<String> rows = jdbc.query("SELECT id FROM worker_lock FOR UPDATE",
                (rs, ignored) -> rs.getString(1));
        if (rows.size() != 1) throw new IllegalStateException("shared writer entry unavailable");
    }

    public List<Row> sourceRows(String sourceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM articles WHERE source_id=? ORDER BY id FOR UPDATE",
                MAPPER, sourceId);
    }

    public Row byNewIdentity(String sourceId, byte[] sourceItemHash) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM articles WHERE source_id=? AND source_item_hash=? FOR UPDATE",
                MAPPER, sourceId, sourceItemHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Row byExternal(String sourceId, String externalId) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM articles WHERE source_id=? AND external_id=? FOR UPDATE",
                MAPPER, sourceId, externalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insert(NewArticle value) {
        jdbc.update("INSERT INTO articles(id,source_id,external_id,external_key,title,url,"
                        + "published_at,summary,body_text,facts_json,content_hash,revision,updated_at,"
                        + "source_item_id,source_item_hash,latest_revision_id,projection_order_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.sourceId(), value.sourceItemId(), value.externalKey(),
                value.title(), value.url(), timestamp(value.publishedAt()), value.summary(),
                value.body(), value.factsJson(), value.contentHash(), 1, timestamp(value.now()),
                value.sourceItemId(), value.sourceItemHash(), null, null);
    }

    public void insertRevision(Revision value) {
        jdbc.update("INSERT INTO article_revisions(id,created_at,revision,article_id,revision_no,"
                        + "content_hash,title,source_summary,body_text,source_time,time_basis,"
                        + "content_status,body_truncated,parser_version,config_version,"
                        + "qualification_events_json,source_order_json,legacy_flag)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), timestamp(value.now()), 0, value.articleId(), 1,
                value.contentHash(), value.title(), value.summary(), value.body(),
                timestamp(value.sourceTime()), value.timeBasis(), value.contentStatus(),
                value.bodyTruncated() ? 1 : 0, value.parserVersion(), value.configVersion(),
                "[]", value.sourceOrderJson(), value.legacy() ? 1 : 0);
    }

    public void attachNewRevision(String articleId, String revisionId, byte[] sourceItemHash,
                                  String sourceItemId) {
        int changed = jdbc.update("UPDATE articles SET latest_revision_id=?,source_item_id=?,"
                        + "source_item_hash=? WHERE id=? AND source_item_id IS NULL AND latest_revision_id IS NULL",
                revisionId, sourceItemId, sourceItemHash, articleId);
        if (changed != 1) throw new IllegalStateException("legacy article changed during conversion");
    }

    public void attachInsertedRevision(String articleId, String revisionId) {
        int changed = jdbc.update("UPDATE articles SET latest_revision_id=? WHERE id=?"
                + " AND latest_revision_id IS NULL", revisionId, articleId);
        if (changed != 1) throw new IllegalStateException("new article changed during insert");
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
