package com.lianxibu.site.repository.articles;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Closed projections for legacy article and entity reads; source body is never selected. */
public final class JdbcArticleReads {
    public record ArticleRow(String id, String title, String summary, String sourceId,
                             String sourceName, String sourceUrl, Instant publishedAt,
                             Instant observedAt, long revision) { }
    public record EntityRow(String id, String category, String title, String summary,
                            Instant firstRecordedAt, LocalDate firstSeenBusinessDate,
                            Instant lastSeenAt, Long occurrenceCount, Long repeatCount,
                            String analysisState, long revision) { }
    public record Page<T>(List<T> items, long total) { }
    private static final String ARTICLE_COLUMNS = "a.id,a.title,a.summary,a.source_id,"
            + "s.name AS source_name,a.url,a.published_at,a.updated_at,a.revision";
    private static final RowMapper<ArticleRow> ARTICLE = (rs, ignored) ->
            new ArticleRow(rs.getString("id"), rs.getString("title"), rs.getString("summary"),
                    rs.getString("source_id"), rs.getString("source_name"), rs.getString("url"),
                    instant(rs, "published_at"), instant(rs, "updated_at"), rs.getLong("revision"));
    private static final RowMapper<EntityRow> ENTITY = (rs, ignored) ->
            new EntityRow(rs.getString("id"), rs.getString("category"), rs.getString("title"),
                    rs.getString("summary"), instant(rs, "first_recorded_at"),
                    rs.getDate("first_seen_business_date") == null ? null
                            : rs.getDate("first_seen_business_date").toLocalDate(),
                    instant(rs, "last_seen_at"), nullableLong(rs, "occurrence_count"),
                    nullableLong(rs, "repeat_count"), rs.getString("analysis_state"),
                    rs.getLong("revision"));
    private final JdbcTemplate jdbc;

    public JdbcArticleReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("article data source required");
        jdbc = new JdbcTemplate(source);
    }

    public ArticleRow article(String id) {
        List<ArticleRow> rows = jdbc.query("SELECT " + ARTICLE_COLUMNS
                + " FROM articles a JOIN sources s ON s.id=a.source_id WHERE a.id=?", ARTICLE, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page<ArticleRow> articles(String sourceId, String titleQuery, long page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sourceId != null) { where.append(" AND a.source_id=?"); args.add(sourceId); }
        if (titleQuery != null) {
            where.append(" AND a.title LIKE ? ESCAPE '!'");
            args.add("%" + escape(titleQuery) + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM articles a" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<ArticleRow> rows = jdbc.query("SELECT " + ARTICLE_COLUMNS
                + " FROM articles a JOIN sources s ON s.id=a.source_id" + where
                + " ORDER BY a.published_at DESC,a.id LIMIT ? OFFSET ?", ARTICLE, pageArgs.toArray());
        return new Page<>(rows, total == null ? 0 : total);
    }

    public EntityRow entity(String id) {
        List<EntityRow> rows = jdbc.query("SELECT id,category,title,summary,first_recorded_at,"
                + "first_seen_business_date,last_seen_at,occurrence_count,repeat_count,"
                + "analysis_state,revision FROM analyzed_entities WHERE id=?", ENTITY, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page<EntityRow> entities(String category, String titleQuery, long page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null) { where.append(" AND category=?"); args.add(category); }
        if (titleQuery != null) {
            where.append(" AND title LIKE ? ESCAPE '!'");
            args.add("%" + escape(titleQuery) + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<EntityRow> rows = jdbc.query("SELECT id,category,title,summary,first_recorded_at,"
                + "first_seen_business_date,last_seen_at,occurrence_count,repeat_count,"
                + "analysis_state,revision FROM analyzed_entities" + where
                + " ORDER BY id LIMIT ? OFFSET ?", ENTITY, pageArgs.toArray());
        return new Page<>(rows, total == null ? 0 : total);
    }

    private static String escape(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
    private static long offset(long page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid page");
        }
        return (page - 1) * size;
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
