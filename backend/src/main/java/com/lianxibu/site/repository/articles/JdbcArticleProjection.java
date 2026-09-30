package com.lianxibu.site.repository.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Current selected revision and append-only evidence revisions under the shared entry lock. */
public final class JdbcArticleProjection {
    public record ManualAudit(String articleId, String evidenceRevisionId,
                              String selectedRevisionId, long expectedRevision, String reason) { }
    public record Current(String id, long revision, String selectedRevisionId,
                          byte[] selectedHash, String orderJson) { }
    public record Revision(String id, String articleId, long revisionNo, byte[] contentHash,
                           String title, String summary, String body, Instant sourceTime,
                           String timeBasis, String contentStatus, boolean bodyTruncated,
                           String parserVersion, long configVersion, String orderJson,
                           String qualificationEventsJson, Instant now,
                           Instant publishedAt, Instant updatedAt, Instant submittedAt) { }
    private final JdbcTemplate jdbc;

    public JdbcArticleProjection(DataSource source) {
        if (source == null) throw new IllegalArgumentException("article projection data source required");
        jdbc = new JdbcTemplate(source);
    }

    public Current current(String articleId) {
        List<Current> rows = jdbc.query("SELECT a.id,a.revision,a.latest_revision_id,"
                        + "r.content_hash,a.projection_order_json FROM articles a"
                        + " JOIN article_revisions r ON r.id=a.latest_revision_id"
                        + " WHERE a.id=? FOR UPDATE",
                (rs, ignored) -> new Current(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getBytes(4), rs.getString(5)), articleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long nextRevisionNo(String articleId) {
        Long highest = jdbc.queryForObject("SELECT MAX(revision_no) FROM article_revisions"
                + " WHERE article_id=?", Long.class, articleId);
        return highest == null ? 1 : highest + 1;
    }

    public void append(Revision value) {
        jdbc.update("INSERT INTO article_revisions(id,created_at,revision,article_id,revision_no,"
                        + "content_hash,title,source_summary,body_text,source_time,time_basis,"
                        + "content_status,body_truncated,parser_version,config_version,"
                        + "qualification_events_json,source_order_json,legacy_flag,published_at,updated_at,submitted_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), Timestamp.from(value.now()), 0, value.articleId(), value.revisionNo(),
                value.contentHash(), value.title(), value.summary(), value.body(),
                time(value.sourceTime()), value.timeBasis(), value.contentStatus(),
                value.bodyTruncated() ? 1 : 0, value.parserVersion(), value.configVersion(),
                value.qualificationEventsJson(), value.orderJson(), 0,
                time(value.publishedAt()), time(value.updatedAt()), time(value.submittedAt()));
    }

    public boolean advanceWatermark(Current old, String orderJson) {
        return jdbc.update("UPDATE articles SET projection_order_json=?,revision=revision+1"
                + " WHERE id=? AND revision=? AND latest_revision_id=?",
                orderJson, old.id(), old.revision(), old.selectedRevisionId()) == 1;
    }

    public boolean promote(Current old, String revisionId, String orderJson, String title,
                           String summary, String body, Instant publishedAt, byte[] hash,
                           String factsJson, Instant now) {
        return jdbc.update("UPDATE articles SET title=?,summary=?,body_text=?,published_at=?,"
                        + "facts_json=?,content_hash=?,latest_revision_id=?,projection_order_json=?,"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?"
                        + " AND latest_revision_id=?",
                title, summary, body, time(publishedAt), factsJson, hash, revisionId, orderJson,
                Timestamp.from(now), old.id(), old.revision(), old.selectedRevisionId()) == 1;
    }

    public boolean manualPromote(String articleId, long expectedRevision, String selectedRevisionId,
                                 Revision target, String factsJson, Instant now) {
        return jdbc.update("UPDATE articles SET title=?,summary=?,body_text=?,published_at=?,"
                        + "facts_json=?,content_hash=?,latest_revision_id=?,projection_order_json=?,"
                        + "revision=revision+1,updated_at=? WHERE id=? AND revision=?"
                        + " AND latest_revision_id=?",
                target.title(), target.summary(), target.body(), time(target.publishedAt()),
                factsJson, target.contentHash(), target.id(), target.orderJson(), Timestamp.from(now),
                articleId, expectedRevision, selectedRevisionId) == 1;
    }

    public Revision byId(String revisionId) {
        List<Revision> rows = jdbc.query("SELECT id,article_id,revision_no,content_hash,title,"
                        + "source_summary,body_text,source_time,time_basis,content_status,"
                        + "body_truncated,parser_version,config_version,source_order_json,"
                        + "qualification_events_json,created_at,published_at,updated_at,submitted_at"
                        + " FROM article_revisions WHERE id=?",
                (rs, ignored) -> new Revision(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getBytes(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                        rs.getString(9), rs.getString(10), rs.getInt(11) == 1,
                        rs.getString(12), rs.getLong(13), rs.getString(14), rs.getString(15),
                        rs.getTimestamp(16).toInstant(),
                        rs.getTimestamp(17) != null ? rs.getTimestamp(17).toInstant()
                                : "published".equals(rs.getString(9)) && rs.getTimestamp(8) != null
                                    ? rs.getTimestamp(8).toInstant() : null,
                        rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toInstant(),
                        rs.getTimestamp(19) == null ? null : rs.getTimestamp(19).toInstant()), revisionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Revision existingMaterial(String articleId, byte[] hash, String title,
                                     String summary, String body) {
        List<String> ids = jdbc.query("SELECT id FROM article_revisions WHERE article_id=?"
                        + " AND content_hash=? ORDER BY revision_no DESC",
                (rs, ignored) -> rs.getString(1), articleId, hash);
        for (String id : ids) {
            Revision revision = byId(id);
            if (revision != null && title.equals(revision.title())
                    && java.util.Objects.equals(summary, revision.summary())
                    && java.util.Objects.equals(body, revision.body())) return revision;
        }
        return null;
    }

    public void saveManualAudit(String actorId, String actionKey, String articleId,
                                String evidenceRevisionId, String selectedRevisionId,
                                long expectedRevision, String reason, Instant now) {
        ObjectNode proof = JsonMapper.shared().createObjectNode();
        proof.put("article_id", articleId);
        proof.put("evidence_revision_id", evidenceRevisionId);
        proof.put("selected_revision_id", selectedRevisionId);
        proof.put("expected_revision", expectedRevision);
        proof.put("reason", reason);
        String body = new String(CanonicalJson.bytes(proof), StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,"
                        + "status_code,response_json,expires_at,id,receipt_kind,actor_id,scope,key_hash,"
                        + "original_status,current_status,result_ref_json,created_at,revision)"
                        + " VALUES(?,'article_projection',?,?,?,?,?,?,"
                        + "'admin_action',?,?,?,'applied','applied',?,?,?)",
                actorId, actionKey, digest(body), 200, null,
                Timestamp.from(now.plusSeconds(30L * 24 * 60 * 60)), UUID.randomUUID().toString(),
                actorId, "article_projection", digest(actionKey), body,
                Timestamp.from(now), expectedRevision + 1);
    }

    public ManualAudit manualAudit(String actorId, String actionKey) {
        List<String> found = jdbc.query("SELECT result_ref_json FROM request_receipts"
                        + " WHERE actor_id=? AND scope='article_projection' AND key_hash=?"
                        + " AND receipt_kind='admin_action'",
                (rs, ignored) -> rs.getString(1), actorId, digest(actionKey));
        if (found.size() > 1) throw new IllegalStateException("duplicate projection action");
        if (found.isEmpty()) return null;
        var proof = CanonicalJson.parse(found.getFirst());
        return new ManualAudit(proof.get("article_id").asText(),
                proof.get("evidence_revision_id").asText(),
                proof.get("selected_revision_id").asText(),
                proof.get("expected_revision").longValue(), proof.get("reason").asText());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
