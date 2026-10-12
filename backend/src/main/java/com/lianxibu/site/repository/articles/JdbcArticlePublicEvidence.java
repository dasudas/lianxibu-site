package com.lianxibu.site.repository.articles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact immutable revision and its retained production observation associations. */
public final class JdbcArticlePublicEvidence {
    public record Revision(String id,String articleId,String title,String summary,String body,byte[] hash,Instant createdAt,boolean legacy,boolean valid) { }
    private final JdbcTemplate jdbc;
    public JdbcArticlePublicEvidence(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Revision lock(String id) {
        requireTx();var rows=jdbc.query("SELECT id,article_id,title,source_summary,body_text,content_hash,created_at,legacy_flag,effective_valid FROM article_revisions WHERE id=? FOR UPDATE",
            (r,n)->new Revision(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBytes(6),r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant(),r.getObject(8)==null||r.getInt(8)!=0,r.getObject(9)!=null&&r.getInt(9)==1),id);
        return rows.size()==1?rows.getFirst():null;
    }
    public List<String> members(Revision revision,Instant cutoff) {
        requireTx();return jdbc.query("SELECT i.id FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.article_revision_id=? AND i.article_id=? AND r.activation_stage='production' AND r.delivery_state IN ('complete','partial') AND r.finished_at<=? AND i.created_at<=? ORDER BY i.id LIMIT 101 FOR UPDATE",
            (r,n)->r.getString(1),revision.id(),revision.articleId(),Timestamp.from(cutoff),Timestamp.from(cutoff));
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("article citation requires writer transaction");}
}
