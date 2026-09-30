package com.lianxibu.site.repository.sources;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Internal reads of the unchanged legacy source identity and configuration. */
public final class JdbcSourceReads {
    public record Row(String id, String sourceKind, String name, String legacyState,
                      String legacyConfigJson, String draftConfigJson, String effectiveConfigJson,
                      long revision, long configRevision, String activationStage,
                      Instant configValidUntil, String category) {
        @Override public String toString() {
            return "SourceRow[id=" + id + ",activationStage=" + activationStage + "]";
        }
    }
    public record Page(List<Row> items, long total) { }
    private static final String COLUMNS = "id,source_kind,name,state,config_json,draft_config_json,"
            + "effective_config_json,revision,config_revision,activation_stage,config_valid_until,category";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("id"), rs.getString("source_kind"), rs.getString("name"),
            rs.getString("state"), rs.getString("config_json"), rs.getString("draft_config_json"),
            rs.getString("effective_config_json"), rs.getLong("revision"),
            rs.getLong("config_revision"), rs.getString("activation_stage"),
            instant(rs, "config_valid_until"), rs.getString("category"));
    private final JdbcTemplate jdbc;

    public JdbcSourceReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row byId(String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM sources WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Page page(long page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid source page");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sources", Long.class);
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM sources ORDER BY id LIMIT ? OFFSET ?",
                MAPPER, size, (page - 1) * size);
        return new Page(rows, total == null ? 0 : total);
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
