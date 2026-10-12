package com.lianxibu.site.repository.content;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Current-row reads only. The legacy JSON and composite identity are never rewritten. */
public final class JdbcContentReads {
    public record Row(String kind, String id, String naturalKey, String title, String bodyJson,
                      String state, long revision, Long bodyRevision, Instant publishedAt,
                      String inputJobId, byte[] bodyHashV1, byte[] reviewHashV1,
                      String reviewDependencyStamp, byte[] reviewStampHash, String reviewStampId,
                      byte[] reviewedInputHash, String blockingSummaryJson) {
        public long effectiveBodyRevision() { return bodyRevision == null ? revision : bodyRevision; }
    }

    private static final String COLUMNS = "kind,id,natural_key,title,body_json,state,revision,body_revision,"
            + "published_at,input_job_id,body_hash_v1,review_hash_v1,review_dependency_stamp,"
            + "review_stamp_hash,review_stamp_id,reviewed_input_hash,blocking_summary_json";
    private static final RowMapper<Row> MAPPER = (rs, ignored) -> new Row(
            rs.getString("kind"), rs.getString("id"), rs.getString("natural_key"),
            rs.getString("title"), rs.getString("body_json"), rs.getString("state"),
            rs.getLong("revision"), nullableLong(rs, "body_revision"),
            instant(rs, "published_at"), rs.getString("input_job_id"),
            rs.getBytes("body_hash_v1"), rs.getBytes("review_hash_v1"),
            rs.getString("review_dependency_stamp"), rs.getBytes("review_stamp_hash"),
            rs.getString("review_stamp_id"), rs.getBytes("reviewed_input_hash"),
            rs.getString("blocking_summary_json"));
    private final JdbcTemplate jdbc;

    public JdbcContentReads(DataSource source) {
        if (source == null) throw new IllegalArgumentException("content data source required");
        this.jdbc = new JdbcTemplate(source);
    }

    public Row current(String kind, String id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM contents WHERE kind=? AND id=?",
                MAPPER, kind, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }


    public record ComparisonRow(String kind,String id,long revision,Long bodyRevision,
            String inputJobId,byte[] bodyHash,String bodyJson,String versionKeyJson) {
        public ComparisonRow { bodyHash=bodyHash==null?null:bodyHash.clone(); }
        @Override public byte[] bodyHash(){return bodyHash==null?null:bodyHash.clone();}
        @Override public String toString(){return "ContentComparisonRow[redacted]";}
    }
    /** Native nullable body revision; bounded before JSON leaves the database. */
    public ComparisonRow comparisonCurrent(String kind,String id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                ||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("content comparison snapshot unavailable");
        return boundedComparison(kind,id);
    }
    /** A strict native proof read joining the original shared writer; no new transaction. */
    public ComparisonRow applicationComparisonCurrent(String kind,String id){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                ||org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED).equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("native content application writer unavailable");
        return boundedComparison(kind,id);
    }
    private ComparisonRow boundedComparison(String kind,String id){
        var values=jdbc.query("SELECT kind,id,revision,body_revision,input_job_id,body_hash_v1,CASE WHEN OCTET_LENGTH(body_json) BETWEEN 1 AND 262144 THEN body_json ELSE NULL END,CASE WHEN OCTET_LENGTH(content_key_json) BETWEEN 1 AND 16384 THEN content_key_json ELSE NULL END,content_key_json IS NOT NULL FROM contents WHERE kind=? AND id=?",
            (r,n)->{
                String body=r.getString(7),key=r.getString(8);
                if(body==null||r.getBoolean(9)&&key==null)throw new IllegalStateException("content comparison data unavailable");
                return new ComparisonRow(r.getString(1),r.getString(2),r.getLong(3),r.getObject(4,Long.class),r.getString(5),r.getBytes(6),body,key);
            },kind,id);
        if(values.size()>1)throw new IllegalStateException("content comparison identity unavailable");
        return values.isEmpty()?null:values.getFirst();
    }


    public record ApplicationHeader(String kind,String id,long revision,Long bodyRevision,
            String inputJobId,byte[] bodyHash,String state) {
        public ApplicationHeader {bodyHash=bodyHash==null?null:bodyHash.clone();}
        @Override public byte[] bodyHash(){return bodyHash==null?null:bodyHash.clone();}
        @Override public String toString(){return "ContentApplicationHeader[redacted]";}
    }
    /** Scalar metadata only; never fetch body, review or blocking JSON for a revision check. */
    public ApplicationHeader applicationHeader(String kind,String id){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("content application snapshot unavailable");
        var values=jdbc.query("SELECT kind,id,revision,body_revision,input_job_id,body_hash_v1,state FROM contents WHERE kind=? AND id=?",(r,n)->new ApplicationHeader(r.getString(1),r.getString(2),r.getLong(3),r.getObject(4,Long.class),r.getString(5),r.getBytes(6),r.getString(7)),kind,id);
        if(values.size()>1)throw new IllegalStateException("content application identity unavailable");return values.isEmpty()?null:values.getFirst();
    }

    /** Stable scan used when eligibility depends on references outside the content row. */
    public List<Row> publishedCandidates(String kind, String afterKind, String afterId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid scan limit");
        String filter = kind == null ? "" : " AND kind=?";
        String sql = "SELECT " + COLUMNS + " FROM contents WHERE state='published'" + filter
                + " AND (kind>? OR (kind=? AND id>?)) ORDER BY kind,id LIMIT ?";
        return kind == null
                ? jdbc.query(sql, MAPPER, afterKind, afterKind, afterId, limit)
                : jdbc.query(sql, MAPPER, kind, afterKind, afterKind, afterId, limit);
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
