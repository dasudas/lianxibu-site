package com.lianxibu.site.repository.articles;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded original production association and immutable revision reads in a borrowed RR snapshot. */
public final class JdbcNewsReportLeaves {
    public record Member(String id,String runId,String sourceId,String category,long revision,Instant createdAt,String terminal,String state,String projection,boolean valid,String payload,byte[] hash,String articleId,String revisionId,String runSource,String activation,String delivery,Instant finishedAt){ }
    public record Revision(String id,String articleId,String title,String summary,String body,byte[] hash,Instant createdAt,boolean legacy,boolean valid,long validityRevision){ }
    private final JdbcTemplate jdbc;
    public JdbcNewsReportLeaves(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Member member(String id){
        readonly();var rows=jdbc.query("SELECT i.id,i.run_id,i.source_id,i.category,i.revision,i.created_at,i.terminal_state,i.state,i.projection_decision,i.effective_valid,CASE WHEN OCTET_LENGTH(i.evidence_json) BETWEEN 1 AND 204800 THEN i.evidence_json ELSE NULL END,i.evidence_hash,i.article_id,i.article_revision_id,r.source_id,r.activation_stage,r.delivery_state,r.finished_at,OCTET_LENGTH(i.evidence_json) FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id=?",(r,n)->{long bytes=r.getLong(19);if(r.wasNull()||bytes<1||bytes>204800)throw invalid();return new Member(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),instant(r,6),r.getString(7),r.getString(8),r.getString(9),r.getObject(10)!=null&&r.getInt(10)==1,r.getString(11),r.getBytes(12),r.getString(13),r.getString(14),r.getString(15),r.getString(16),r.getString(17),instant(r,18));},id);
        if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    public Revision revision(String id){
        readonly();String size="COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(source_summary),0)+COALESCE(OCTET_LENGTH(body_text),0)";
        var rows=jdbc.query("SELECT id,article_id,CASE WHEN "+size+"<=262144 THEN title ELSE NULL END,CASE WHEN "+size+"<=262144 THEN source_summary ELSE NULL END,CASE WHEN "+size+"<=262144 THEN body_text ELSE NULL END,content_hash,created_at,legacy_flag,effective_valid,validity_revision,"+size+" FROM article_revisions WHERE id=?",(r,n)->{long bytes=r.getLong(11);if(r.wasNull()||bytes>262144)throw invalid();long revision=r.getLong(10);if(r.wasNull()||revision<0||revision>9_007_199_254_740_991L)throw invalid();return new Revision(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBytes(6),instant(r,7),r.getObject(8)==null||r.getInt(8)!=0,r.getObject(9)!=null&&r.getInt(9)==1,revision);},id);
        if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    private static Instant instant(ResultSet row,int column)throws SQLException{var timestamp=row.getTimestamp(column,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return timestamp==null?null:timestamp.toInstant();}
    public static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native news report leaf unavailable");}
}
