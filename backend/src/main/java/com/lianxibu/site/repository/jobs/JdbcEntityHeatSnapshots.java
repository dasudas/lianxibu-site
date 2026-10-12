package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact native touched entity facts after a count-changing action, in that same short RC writer. */
public final class JdbcEntityHeatSnapshots {
    private static final int MAX_TEXT_BYTES=65536;
    public record Facts(String entityId,String category,long revision,Long occurrenceCount,String title,String summary,Instant firstRecordedAt,LocalDate firstSeenBusinessDate,boolean legacy){@Override public String toString(){return "NativeEntityHeatFacts[redacted]";}}
    private final JdbcTemplate sql;
    public JdbcEntityHeatSnapshots(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public List<Facts> readTouched(String oldId,String newId){
        writer();var ids=new TreeSet<String>();if(oldId!=null)ids.add(WireValues.id(oldId,false));if(newId!=null)ids.add(WireValues.id(newId,false));var result=new ArrayList<Facts>();
        for(String id:ids){var rows=sql.query("SELECT id,category,revision,occurrence_count,CASE WHEN COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(summary),0)<=65536 THEN title ELSE NULL END,CASE WHEN COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(summary),0)<=65536 THEN summary ELSE NULL END,first_recorded_at,first_seen_business_date,legacy_flag,COALESCE(OCTET_LENGTH(title),0)+COALESCE(OCTET_LENGTH(summary),0) FROM analyzed_entities WHERE id=? FOR UPDATE",(r,n)->{
            long revision=number(r.getObject(3));Long count=r.getObject(4)==null?null:number(r.getObject(4));long legacy=number(r.getObject(9));if(legacy>1||number(r.getObject(10))>MAX_TEXT_BYTES)throw invalid();var first=r.getTimestamp(7,Calendar.getInstance(TimeZone.getTimeZone("UTC")));var date=r.getDate(8);return new Facts(r.getString(1),r.getString(2),revision,count,r.getString(5),r.getString(6),first==null?null:first.toInstant(),date==null?null:date.toLocalDate(),legacy==1);
        },id);if(rows.size()!=1||!id.equals(rows.getFirst().entityId()))throw invalid();result.add(rows.getFirst());}
        return List.copyOf(result);
    }
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long v=new java.math.BigInteger(value.toString()).longValueExact();if(v<0||v>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return v;}catch(RuntimeException bad){throw invalid();}}
    private static void writer(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native touched entity heat facts unavailable");}
    @Override public String toString(){return "JdbcEntityHeatSnapshots[redacted]";}
}
