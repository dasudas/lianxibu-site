package com.lianxibu.site.repository.jobs;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native metadata for a durable original P1 to P2 relationship. */
public final class JdbcSourceValueIntents {
    public static final String TYPE="source_value_intent",ACTOR="source-analysis-parent",REASON="Original source dedup handed to value analysis";
    public record Raw(String id,String kind,String type,String target,byte[] targetHash,String actor,String reason,String payload,byte[] payloadHash,byte[] actionHash,Instant actedAt,Long targetBytes,Long payloadBytes){
        public Raw {targetHash=copy(targetHash);payloadHash=copy(payloadHash);actionHash=copy(actionHash);}
        @Override public byte[] targetHash(){return copy(targetHash);}@Override public byte[] payloadHash(){return copy(payloadHash);}@Override public byte[] actionHash(){return copy(actionHash);}
        @Override public String toString(){return "SourceValueIntentNativeMetadata[redacted]";}
        private static byte[] copy(byte[] value){return value==null?null:value.clone();}
    }
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;private final JdbcFrozenSourcePlans plans;
    public JdbcSourceValueIntents(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);plans=new JdbcFrozenSourcePlans(source);}
    public JdbcFrozenSourcePlans.Owner owner(String id){return plans.immutableOwner(id);}
    public JdbcJobRecords.Disposition originalPlan(String owner){return plans.immutableReceipt(owner);}
    public Raw read(String p1Owner){
        JdbcFrozenSourceUniverses.requireTransaction(false);
        var rows=jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 8192 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",
            (r,n)->{var at=r.getTimestamp(11);return new Raw(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8),r.getBytes(9),r.getBytes(10),at==null?null:at.toInstant(),r.getObject(12,Long.class),r.getObject(13,Long.class));},receiptId(p1Owner),JdbcGenerationLineage.hash(action(p1Owner)));
        if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    public long next(String key,LocalDate day){
        JdbcFrozenInputs.requireTx();if(key==null||key.isBlank()||key.length()>4096||day==null)throw invalid();
        var rows=jdbc.query("SELECT id,record_kind,logical_key,logical_key_hash,generation,task_type,business_date,state FROM jobs WHERE record_kind='generation_intent' AND logical_key_hash=? ORDER BY generation DESC LIMIT 2 FOR UPDATE",
            (r,n)->{Long generation=r.getObject(5,Long.class);var date=r.getDate(7);if(generation==null||date==null)throw invalid();return new JdbcFrozenSourcePlans.Owner(r.getString(1),r.getString(2),r.getString(3),r.getBytes(4),generation,r.getString(6),date.toLocalDate(),r.getString(8));},JdbcGenerationLineage.hash(key));
        for(var row:rows)if(!key.equals(row.key())||!"news_value".equals(row.type())||!day.equals(row.businessDate())||row.generation()<1||row.generation()>=9_007_199_254_740_991L||!Arrays.equals(row.keyHash(),JdbcGenerationLineage.hash(key))||!row.id().equals(JdbcFrozenInputs.stableId("intent",key+":"+row.generation())))throw invalid();
        if(rows.size()==2&&rows.get(0).generation()<=rows.get(1).generation())throw invalid();return rows.isEmpty()?1:rows.getFirst().generation()+1;
    }
    public void create(String owner,String key,long generation,LocalDate day,Instant now){
        JdbcFrozenInputs.requireTx();if(records.byId(owner)!=null||!owner.equals(records.ensureIntent(new JdbcJobRecords.Intent(owner,key,generation,"news_value",day),now)))throw invalid();
    }
    public void append(String p1Owner,String p2Owner,String payload,Instant now){
        JdbcFrozenInputs.requireTx();var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",p2Owner);
        records.appendDisposition(new JdbcJobRecords.Disposition(receiptId(p1Owner),action(p1Owner),TYPE,JdbcFrozenInputs.canonical(target),ACTOR,REASON,payload),now);
    }
    public record Cursor(String ownerId,long revision,String state){
        @Override public String toString(){return "SourceDedupIntentCursor[redacted]";}
    }
    public Cursor lockP1(String owner){
        JdbcFrozenInputs.requireTx();var rows=jdbc.query("SELECT id,revision,state FROM jobs WHERE id=? AND record_kind='generation_intent' AND task_type='news_dedup' FOR UPDATE",(r,n)->{
            Long revision=r.getObject(2,Long.class);if(revision==null||revision<0||revision>=9_007_199_254_740_991L)throw invalid();return new Cursor(r.getString(1),revision,r.getString(3));
        },owner);if(rows.size()!=1||!owner.equals(rows.getFirst().ownerId()))throw invalid();return rows.getFirst();
    }
    public void completeP1(Cursor cursor,boolean empty,Instant now){
        JdbcFrozenInputs.requireTx();Objects.requireNonNull(cursor);Objects.requireNonNull(now);
        if(jdbc.update("UPDATE jobs SET state=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='generation_intent' AND task_type='news_dedup' AND revision=? AND state=?",empty?"no_input":"succeeded",java.sql.Timestamp.from(now),cursor.ownerId(),cursor.revision(),cursor.state())!=1)throw invalid();
    }
    public static String receiptId(String owner){return JdbcFrozenInputs.stableId("source-value-intent",owner);}
    public static String action(String owner){return "source-value-intent:"+owner;}
    private static IllegalStateException invalid(){return new IllegalStateException("source value intent native metadata unavailable");}
}
