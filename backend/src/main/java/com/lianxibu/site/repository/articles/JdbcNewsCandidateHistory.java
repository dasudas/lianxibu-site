package com.lianxibu.site.repository.articles;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded current metadata only. No unproven historical content body is read. */
public final class JdbcNewsCandidateHistory {
    public record Row(String id,String category,String identityKey,byte[] identityHash,String title,String summary,
                      Long revision,Instant firstRecordedAt,String analysisState,Integer legacy) {
        public Row {identityHash=identityHash==null?null:identityHash.clone();}
        @Override public byte[] identityHash(){return identityHash==null?null:identityHash.clone();}
        @Override public String toString(){return "CandidateMetadataRow[redacted]";}
    }
    private final JdbcTemplate jdbc;private final String category;
    public JdbcNewsCandidateHistory(DataSource source){this(source,"news_tech");}
    /** One exact category scope; native case aliases are still checked by the consumer. */
    public JdbcNewsCandidateHistory(DataSource source,String category){
        if(category==null||!Set.of("news_tech","agent","skill","workflow","model").contains(category))throw unavailable();
        this.category=category;jdbc=new JdbcTemplate(Objects.requireNonNull(source));
    }
    public List<Row> lockAll() {
        requireTx();
        return jdbc.query("SELECT id,category,OCTET_LENGTH(identity_key),OCTET_LENGTH(title),OCTET_LENGTH(summary),"
            +"CASE WHEN OCTET_LENGTH(identity_key)<=4096 THEN identity_key ELSE NULL END,identity_hash,"
            +"CASE WHEN OCTET_LENGTH(title)<=2048 THEN title ELSE NULL END,"
            +"CASE WHEN OCTET_LENGTH(summary)<=16384 THEN summary ELSE NULL END,revision,first_recorded_at,analysis_state,legacy_flag"
            +" FROM analyzed_entities WHERE category=? LIMIT 10001 FOR UPDATE",(r,n)->{
                long identityBytes=r.getLong(3);boolean missingIdentity=r.wasNull();long titleBytes=r.getLong(4);boolean missingTitle=r.wasNull();long summaryBytes=r.getLong(5);
                if(missingIdentity||missingTitle||identityBytes<1||identityBytes>4096||titleBytes<1||titleBytes>2048||summaryBytes>16384)throw unavailable();
                return new Row(r.getString(1),r.getString(2),r.getString(6),r.getBytes(7),r.getString(8),r.getString(9),r.getObject(10)==null?null:r.getLong(10),
                    r.getTimestamp(11)==null?null:r.getTimestamp(11).toInstant(),r.getString(12),r.getObject(13)==null?null:r.getInt(13));
            },category);
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();}
    private static IllegalStateException unavailable(){return new IllegalStateException("bounded candidate metadata unavailable");}
}
