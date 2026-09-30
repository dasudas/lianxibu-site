package com.lianxibu.site.repository.sources;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Source configuration and activation changes under the shared write entry. */
public final class JdbcSourceAdmission {
    public record Row(String id, String sourceKind, String name, String legacyConfigJson,
                      String draftConfigJson, String effectiveConfigJson, long revision,
                      long configRevision, String activationStage, Instant configValidUntil,
                      String category) {
        @Override public String toString() {
            return "SourceAdmissionRow[id=" + id + ",stage=" + activationStage + "]";
        }
    }
    private static final String COLUMNS = "id,source_kind,name,config_json,draft_config_json,"
            + "effective_config_json,revision,config_revision,activation_stage,config_valid_until,category";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("id"), rs.getString("source_kind"), rs.getString("name"),
            rs.getString("config_json"), rs.getString("draft_config_json"),
            rs.getString("effective_config_json"), rs.getLong("revision"),
            rs.getLong("config_revision"), rs.getString("activation_stage"),
            instant(rs, "config_valid_until"), rs.getString("category"));
    private final JdbcTemplate jdbc;

    public JdbcSourceAdmission(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source admission data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Row locked(String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM sources WHERE id=? FOR UPDATE", MAPPER, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void create(String id, String sourceKind, String name, String category,
                       String draftJson, Instant now) {
        jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,probe_json,"
                        + "revision,updated_at,draft_config_json,effective_config_json,"
                        + "config_revision,activation_stage,config_valid_until,category)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, sourceKind, name, "disabled", draftJson, null, 1, Timestamp.from(now),
                draftJson, null, 1, "disabled", null, category);
    }

    public boolean edit(Row old, String name, String category, String draftJson, Instant now) {
        return jdbc.update("UPDATE sources SET name=?,category=?,draft_config_json=?,"
                        + "activation_stage='disabled',config_revision=config_revision+1,"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                name, category, draftJson, Timestamp.from(now), old.id(), old.revision()) == 1;
    }

    public boolean setTrial(Row old, Instant validUntil, Instant now) {
        return jdbc.update("UPDATE sources SET activation_stage='trial',"
                        + "effective_config_json=draft_config_json,config_valid_until=?,"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?"
                        + " AND activation_stage='disabled' AND draft_config_json IS NOT NULL",
                Timestamp.from(validUntil), Timestamp.from(now), old.id(), old.revision()) == 1;
    }

    public boolean promote(Row old, Instant now) {
        return jdbc.update("UPDATE sources SET activation_stage='production',"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?"
                        + " AND activation_stage='trial' AND effective_config_json IS NOT NULL",
                Timestamp.from(now), old.id(), old.revision()) == 1;
    }

    public boolean disable(Row old, Instant now) {
        return jdbc.update("UPDATE sources SET activation_stage='disabled',"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                Timestamp.from(now), old.id(), old.revision()) == 1;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
