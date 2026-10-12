package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native P2 partial seals. The consumer validates closed business metadata and original proofs. */
public final class JdbcSourceValuePartialReceipts {
    public static final String TYPE="source_value_partial_result",ACTOR="source-value-partial-sealer",REASON="Sealed original partial source value output";
    public record Raw(String id,String kind,String targetType,String target,byte[] targetHash,String actor,String reason,String payload,byte[] payloadHash,byte[] actionHash,Instant actedAt,Long targetBytes,Long payloadBytes){
        public Raw {targetHash=copy(targetHash);payloadHash=copy(payloadHash);actionHash=copy(actionHash);}
        @Override public byte[] targetHash(){return copy(targetHash);}@Override public byte[] payloadHash(){return copy(payloadHash);}@Override public byte[] actionHash(){return copy(actionHash);}
        @Override public String toString(){return "SourceValuePartialNativeReceipt[redacted]";}
    }
    private static final String COLUMNS="id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 8192 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload)";
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;private final JdbcFrozenSourcePlans owners;
    public JdbcSourceValuePartialReceipts(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);owners=new JdbcFrozenSourcePlans(source);}
    public void lockOwner(String owner){validate(owners.lockOwner(owner));}
    public Raw read(String owner){
        JdbcFrozenSourceUniverses.requireTransaction(false);WireValues.id(owner,false);var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",(r,n)->{var time=r.getTimestamp(11);return new Raw(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8),r.getBytes(9),r.getBytes(10),time==null?null:time.toInstant(),r.getObject(12,Long.class),r.getObject(13,Long.class));},receiptId(owner),JdbcGenerationLineage.hash(action(owner)));if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    public void append(String owner,String payload,Instant now){
        JdbcFrozenInputs.requireTx();WireValues.id(owner,false);if(payload==null||payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192||now==null||now.getNano()%1000!=0)throw invalid();
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",owner);records.appendDisposition(new JdbcJobRecords.Disposition(receiptId(owner),action(owner),TYPE,JdbcFrozenInputs.canonical(target),ACTOR,REASON,payload),now);
    }
    private static void validate(JdbcFrozenSourcePlans.Owner owner){
        if(!"generation_intent".equals(owner.kind())||!"news_value".equals(owner.type())||owner.key()==null||owner.key().isBlank()||owner.generation()<1||owner.generation()>CanonicalJson.MAX_SAFE_INTEGER||!Arrays.equals(owner.keyHash(),JdbcGenerationLineage.hash(owner.key()))||!owner.id().equals(JdbcFrozenInputs.stableId("intent",owner.key()+":"+owner.generation())))throw invalid();
    }
    private static byte[] copy(byte[] value){return value==null?null:value.clone();}
    public static String receiptId(String owner){return JdbcFrozenInputs.stableId("source-value-partial-receipt",owner);}
    public static String action(String owner){return "source-value-partial-seal:"+owner;}
    private static IllegalStateException invalid(){return new IllegalStateException("original source value partial receipt storage unavailable");}
}
