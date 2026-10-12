package com.lianxibu.site.repository.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Current entity ownership and unique independent source-item contribution. */
public final class JdbcEntityDedup {
    public record Member(String id, String runId, String sourceId, String sourceItemId,
                         String category, String entityId, String terminalState, String state,
                         String projectionDecision, boolean effectiveValid,
                         long revision, LocalDate businessDate) { }
    public record Entity(String id, String category, String identityKey, byte[] identityHash,
                         LocalDate firstSeenBusinessDate, Long occurrenceCount,
                         boolean legacy, long revision) { }
    private final JdbcTemplate jdbc;

    public JdbcEntityDedup(DataSource source) {
        if (source == null) throw new IllegalArgumentException("entity data source required");
        jdbc = new JdbcTemplate(source);
    }
    public Member member(String itemId) {
        List<Member> rows = jdbc.query("SELECT i.id,i.run_id,i.source_id,i.source_item_id,i.category,"
                        + "i.entity_id,i.terminal_state,i.state,i.projection_decision,"
                        + "i.effective_valid,i.revision,r.business_date FROM source_run_items i"
                        + " JOIN source_runs r ON r.id=i.run_id WHERE i.id=? FOR UPDATE",
                (rs, ignored) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getInt(10) == 1, rs.getLong(11),
                        rs.getDate(12).toLocalDate()), itemId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Entity entity(String entityId) {
        List<Entity> rows = jdbc.query("SELECT id,category,identity_key,identity_hash,"
                        + "first_seen_business_date,occurrence_count,legacy_flag,revision"
                        + " FROM analyzed_entities WHERE id=? FOR UPDATE",
                (rs, ignored) -> new Entity(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBytes(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
                        rs.getObject(6) == null ? null : rs.getLong(6), rs.getInt(7) == 1,
                        rs.getLong(8)), entityId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Entity byIdentity(String category, String identityKey) {
        List<String> ids = jdbc.query("SELECT id FROM analyzed_entities WHERE category=?"
                        + " AND identity_hash=? FOR UPDATE", (rs, ignored) -> rs.getString(1),
                category, identityHash(category, identityKey));
        Entity existing = ids.isEmpty() ? null : entity(ids.getFirst());
        if (existing != null && !identityKey.equals(existing.identityKey()))
            throw new IllegalStateException("entity identity hash collision");
        return existing;
    }
    public void insertEntity(String id, String category, String identityKey, String title,
                             String summary, LocalDate businessDate, Instant now) {
        Instant recorded=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);java.util.Calendar utc=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO analyzed_entities(id,created_at,revision,category,identity_key,identity_hash,title,summary,first_recorded_at,last_seen_at,first_seen_business_date,occurrence_count,repeat_count,analysis_state,analysis_generation,aliases_json,legacy_flag) VALUES(?,?,0,?,?,?,?,?,?,?,?,0,0,'pending',0,'[]',0)");
            statement.setString(1,id);statement.setTimestamp(2,Timestamp.from(recorded),utc);statement.setString(3,category);statement.setString(4,identityKey);statement.setBytes(5,identityHash(category,identityKey));statement.setString(6,title);statement.setString(7,summary);statement.setTimestamp(8,Timestamp.from(recorded),utc);statement.setTimestamp(9,Timestamp.from(recorded),utc);statement.setObject(10,businessDate);return statement;
        });
    }
    public void setReview(Member member) {
        if (jdbc.update("UPDATE source_run_items SET state='needs_review',revision=revision+1"
                        + " WHERE id=? AND revision=?", member.id(), member.revision()) != 1)
            throw new IllegalStateException("member review version conflict");
    }
    public void assign(Member member, String entityId, boolean isNew, String decisionJson) {
        if (jdbc.update("UPDATE source_run_items SET entity_id=?,state='ready',is_new=?,"
                        + "dedup_json=?,revision=revision+1 WHERE id=? AND revision=?",
                entityId, isNew ? 1 : 0, decisionJson, member.id(), member.revision()) != 1)
            throw new IllegalStateException("member assignment version conflict");
    }
    public boolean contributionExists(byte[] occurrenceHash) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items"
                + " WHERE counted_occurrence_key=?", Long.class, occurrenceHash);
        return count != null && count > 0;
    }
    public void setContribution(String itemId, byte[] key) {
        jdbc.update("UPDATE source_run_items SET counted_occurrence_key=? WHERE id=?", key, itemId);
    }
    public void clearContribution(String itemId) {
        jdbc.update("UPDATE source_run_items SET counted_occurrence_key=NULL WHERE id=?", itemId);
    }
    public void repairContribution(String sourceId, String sourceItemId, String entityId) {
        byte[] key = occurrenceHash(sourceId, sourceItemId, entityId);
        if (contributionExists(key)) return;
        List<String> candidates = jdbc.query("SELECT id FROM source_run_items WHERE entity_id=?"
                        + " AND source_id=? AND source_item_id=? AND terminal_state='accepted'"
                        + " AND effective_valid=1 AND state<>'excluded'"
                        + " ORDER BY created_at,id LIMIT 1 FOR UPDATE",
                (rs, ignored) -> rs.getString(1), entityId, sourceId, sourceItemId);
        if (!candidates.isEmpty()) setContribution(candidates.getFirst(), key);
    }
    public void exclude(Member member) {
        if (jdbc.update("UPDATE source_run_items SET state='excluded',effective_valid=0,"
                        + "validity_revision=validity_revision+1,revision=revision+1"
                        + " WHERE id=? AND revision=?", member.id(), member.revision()) != 1)
            throw new IllegalStateException("member exclusion version conflict");
    }
    public void reassign(Member member, String entityId) {
        if (jdbc.update("UPDATE source_run_items SET entity_id=?,state='ready',"
                        + "revision=revision+1 WHERE id=? AND revision=?", entityId,
                member.id(), member.revision()) != 1)
            throw new IllegalStateException("member reassignment version conflict");
    }
    public void recompute(String entityId) {
        Entity entity = entity(entityId);
        if (entity == null) throw new IllegalStateException("entity not found");
        if (entity.legacy() && entity.occurrenceCount() == null) return;
        Object[] count = jdbc.queryForObject("SELECT COUNT(*),MAX(created_at) FROM source_run_items"
                        + " WHERE entity_id=? AND counted_occurrence_key IS NOT NULL"
                        + " AND effective_valid=1", (rs, ignored) -> new Object[]{rs.getLong(1),
                        rs.getTimestamp(2)}, entityId);
        long total = (long) count[0];
        Timestamp latestSeen = jdbc.queryForObject("SELECT MAX(created_at) FROM source_run_items"
                + " WHERE entity_id=? AND terminal_state='accepted'", Timestamp.class, entityId);
        jdbc.update("UPDATE analyzed_entities SET occurrence_count=?,repeat_count=?,"
                        + "last_seen_at=CASE WHEN ?>last_seen_at THEN ? ELSE last_seen_at END,"
                        + "last_occurrence_at=?,"
                        + "revision=revision+1 WHERE id=?",
                total, Math.max(total - 1, 0), latestSeen, latestSeen, count[1], entityId);
    }
    /** Called only after an explicit manual reassignment or exclusion, never on late automatic backfill. */
    public void recomputeFirstSeenAfterCorrection(String entityId) {
        java.sql.Date first = jdbc.queryForObject("SELECT MIN(r.business_date)"
                        + " FROM source_run_items i JOIN source_runs r ON r.id=i.run_id"
                        + " WHERE i.entity_id=? AND i.counted_occurrence_key IS NOT NULL"
                        + " AND i.effective_valid=1", java.sql.Date.class, entityId);
        jdbc.update("UPDATE analyzed_entities SET first_seen_business_date=?,revision=revision+1"
                + " WHERE id=?", first, entityId);
    }
    public static byte[] identityHash(String category, String key) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("category", category);
        value.put("identity_key", key);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.IDENTITY, value));
    }
    public static byte[] occurrenceHash(String sourceId, String sourceItemId, String entityId) {
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("source_id", sourceId);
        value.put("source_item_id", sourceItemId);
        value.put("entity_id", entityId);
        return HexFormat.of().parseHex(CanonicalJson.hash(Domain.OCCURRENCE, value));
    }
}
