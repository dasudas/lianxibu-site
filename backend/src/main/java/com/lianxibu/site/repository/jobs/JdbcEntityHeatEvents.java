package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Complete immutable native decision event. A writer timestamp does not prove commit visibility. */
public final class JdbcEntityHeatEvents {
    public record Event(String id,String actor,String reason,String targetJson,String payloadJson,String payloadHash,Instant actedAt){@Override public String toString(){return "NativeEntityHeatEvent[redacted]";}}
    private final JdbcTemplate sql;private final Set<String> actors;
    public JdbcEntityHeatEvents(DataSource source){this(source,Set.of("analysis-worker"));}
    public JdbcEntityHeatEvents(DataSource source,Set<String> actors){sql=new JdbcTemplate(Objects.requireNonNull(source));Objects.requireNonNull(actors);if(actors.isEmpty()||actors.size()>64)throw invalid();actors.forEach(a->WireValues.id(a,false));this.actors=Set.copyOf(actors);}
    public Event read(String id){readonly();return readNative(id);}
    /** Exact full parent reread inside the original short first-observation writer. */
    public Event readInWriter(String id){writer();return readNative(id);}
    private Event readNative(String id){
        if(id==null||!id.matches("[0-9a-f]{64}"))throw invalid();String action="entity_decision:"+id;byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action)));
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,created_at,updated_at,eligible_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 1572864 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,root_job_id,intent_id,generation,task_type,logical_key,attempt_no,owner_job_id,purpose,stamp_id,part_no FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
            String target=r.getString(11),body=r.getString(19),actor=r.getString(13),reason=r.getString(14);if(target==null||body==null||!actors.contains(actor)||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw invalid();target=JdbcFrozenInputs.canonical(CanonicalJson.parse(target));body=JdbcFrozenInputs.canonical(CanonicalJson.parse(body));if(body.getBytes(StandardCharsets.UTF_8).length>1114112)throw invalid();Instant acted=instant(r.getTimestamp(15,utc()));
            if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!same(identity,r.getBytes(8))||!same(sha(action),r.getBytes(9))||!"entity_decision".equals(r.getString(10))||!same(sha(target),r.getBytes(12))||!acted.equals(instant(r.getTimestamp(16,utc())))||!acted.equals(instant(r.getTimestamp(17,utc())))||!acted.equals(instant(r.getTimestamp(18,utc())))||!same(sha(body),r.getBytes(20))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(21))||!"disposition".equals(r.getString(22))||!id.equals(r.getString(23)))throw invalid();
            for(int field=24;field<=33;field++)if(r.getObject(field)!=null)throw invalid();return new Event(id,actor,reason,target,body,HexFormat.of().formatHex(sha(body)),acted);
        },id,sha(action),identity);if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{return new java.math.BigInteger(value.toString()).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static byte[] sha(String value){return JdbcGenerationLineage.hash(value);}
    private static boolean same(byte[] a,byte[] b){return a!=null&&b!=null&&MessageDigest.isEqual(a,b);}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static void writer(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original native entity heat event unavailable");}
    @Override public String toString(){return "JdbcEntityHeatEvents[redacted]";}
}
