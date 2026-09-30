package com.lianxibu.site.repository.settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Exact current setting reads; legacy JSON remains unchanged. */
public final class JdbcSettingsReads {
    public record Setting(String key, String valueJson, long revision, Instant updatedAt) {
        @Override public String toString() { return "Setting[key=" + key + ",revision=" + revision + "]"; }
    }
    private static final RowMapper<Setting> SETTING = (rs, ignored) -> new Setting(
            rs.getString("setting_key"), rs.getString("value_json"), rs.getLong("revision"),
            instant(rs, "updated_at"));
    private final JdbcTemplate jdbc;

    public JdbcSettingsReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("settings data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Setting setting(String key) {
        List<Setting> rows = jdbc.query("SELECT setting_key,value_json,revision,updated_at "
                + "FROM runtime_settings WHERE setting_key=?", SETTING, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean publishedPackage(String id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM learning_packages "
                + "WHERE id=? AND state='published'", Long.class, id);
        return count != null && count == 1;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
