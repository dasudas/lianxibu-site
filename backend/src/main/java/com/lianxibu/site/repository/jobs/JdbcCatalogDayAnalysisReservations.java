package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Exact bounded permanent day/entity reservation records in the existing jobs table. */
public final class JdbcCatalogDayAnalysisReservations {
    public static final String TYPE="catalog_day_analysis",ACTOR="catalog-analysis-coordinator",REASON="Original catalog day entity analysis reserved";
    public static final int MAX_PAYLOAD=131072;
    public record Raw(String json,Instant actedAt){@Override public String toString(){return "CatalogDayReservationNative[redacted]";}}
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;
    public JdbcCatalogDayAnalysisReservations(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public Raw read(String key){transaction(true);return find(key,false);}
    public Raw lockAndRead(String key){transaction(false);return find(key,true);}
    private Raw find(String key,boolean lock){
        key(key);var rows=jdbc.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 131072 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,created_at,updated_at,eligible_at FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2"+(lock?" FOR UPDATE":""),(r,n)->{
            String target=r.getString(11),body=r.getString(16);if(target==null||body==null)throw invalid();target=JdbcFrozenInputs.canonical(CanonicalJson.parse(target));body=JdbcFrozenInputs.canonical(CanonicalJson.parse(body));var timestamp=r.getTimestamp(15,utc());if(timestamp==null)throw invalid();Instant time=timestamp.toInstant();
            if(!id(key).equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!Arrays.equals(identity(key),r.getBytes(8))||!Arrays.equals(JdbcGenerationLineage.hash(action(key)),r.getBytes(9))||!TYPE.equals(r.getString(10))||!target(key).equals(target)||!Arrays.equals(JdbcGenerationLineage.hash(target),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!REASON.equals(r.getString(14))||!Arrays.equals(JdbcGenerationLineage.hash(body),r.getBytes(17))||!("disposition:"+HexFormat.of().formatHex(identity(key))).equals(r.getString(18))||!"disposition".equals(r.getString(19))||!id(key).equals(r.getString(20))||!time.equals(instant(r.getTimestamp(21,utc())))||!time.equals(instant(r.getTimestamp(22,utc())))||!time.equals(instant(r.getTimestamp(23,utc()))))throw invalid();return new Raw(body,time);
        },id(key),JdbcGenerationLineage.hash(action(key)),identity(key));if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    /** Caller must prove native eligibility and atomically commit the actual reservation first. */
    public void append(String key,String payload,Instant now){transaction(false);if(payload==null||CanonicalJson.bytes(CanonicalJson.parse(payload)).length>MAX_PAYLOAD)throw invalid();records.appendDispositionUtc(new JdbcJobRecords.Disposition(id(key),action(key),TYPE,target(key),ACTOR,REASON,payload),now);}
    public static String key(String category,LocalDate day,String entity){
        if(!Set.of("agent","skill","workflow","model").contains(category)||day==null)throw invalid();WireValues.id(entity,false);var n=JsonMapper.shared().createObjectNode().put("schema_version","catalog-day-analysis-reservation-key-v1").put("category",category).put("business_date",day.toString()).put("entity_id",entity);return CanonicalJson.hash(CanonicalJson.Domain.INPUT,n);
    }
    public static String id(String key){key(key);return JdbcFrozenInputs.stableId("catalog-day-analysis-reservation",key);}
    public static String action(String key){key(key);return "catalog-day-analysis:"+key;}
    public static String target(String key){key(key);return JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","catalog_day_analysis").put("reservation_key",key));}
    private static byte[] identity(String key){key(key);return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action(key))));}
    private static void key(String key){if(key==null||!key.matches("[0-9a-f]{64}"))throw invalid();}
    private static long number(Object n){if(!(n instanceof Number))throw invalid();return ((Number)n).longValue();}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(java.sql.Timestamp time){if(time==null)throw invalid();return time.toInstant();}
    private static void transaction(boolean read){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=read||(read?!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()):!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day analysis reservation storage unavailable");}
}
