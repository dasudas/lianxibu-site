package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact permanent version judgment lookup. No insert, generation allocation or implicit transaction. */
public final class JdbcCatalogVersionJudgments {
    public static final String TYPE="catalog_version_judgment",ACTOR="catalog-version-writer",REASON="Recorded first qualified catalog version judgment";
    public record Raw(String id,String kind,String state,Long revision,byte[] identityHash,String targetType,String target,byte[] targetHash,String actor,String reason,String payload,byte[] payloadHash,byte[] actionHash,Instant actedAt,Long targetBytes,Long payloadBytes){
        public Raw{identityHash=copy(identityHash);targetHash=copy(targetHash);payloadHash=copy(payloadHash);actionHash=copy(actionHash);}
        @Override public byte[] identityHash(){return copy(identityHash);}@Override public byte[] targetHash(){return copy(targetHash);}@Override public byte[] payloadHash(){return copy(payloadHash);}@Override public byte[] actionHash(){return copy(actionHash);}
        @Override public String toString(){return "CatalogVersionJudgmentNative[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcCatalogVersionJudgments(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Raw read(String key){transaction(true);return find(key,false);}
    public Raw lockAndRead(String key){transaction(false);return find(key,true);}
    private Raw find(String key,boolean lock){
        key(key);var rows=jdbc.query("SELECT id,record_kind,state,revision,record_identity_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 32768 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE id=? OR action_key_hash=? LIMIT 2"+(lock?" FOR UPDATE":""),(r,n)->{var time=r.getTimestamp(14,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return new Raw(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Long.class),r.getBytes(5),r.getString(6),r.getString(7),r.getBytes(8),r.getString(9),r.getString(10),r.getString(11),r.getBytes(12),r.getBytes(13),time==null?null:time.toInstant(),r.getObject(15,Long.class),r.getObject(16,Long.class));},id(key),JdbcGenerationLineage.hash(action(key)));
        if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    /** Internal caller must atomically validate native eligibility and any generation allocation. */
    public void append(String key,String payload,Instant now){transaction(false);new JdbcJobRecords(Objects.requireNonNull(jdbc.getDataSource())).appendDispositionUtc(new JdbcJobRecords.Disposition(id(key),action(key),TYPE,target(key),ACTOR,REASON,payload),now);}
    public static String id(String key){key(key);return JdbcFrozenInputs.stableId("catalog-version-judgment",key);}
    public static String action(String key){key(key);return "catalog-version-judgment:"+key;}
    public static String target(String key){key(key);return JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","catalog_version").put("judgment_key",key));}
    public static byte[] identity(String key){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action(key))));}
    private static void key(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static byte[] copy(byte[] value){return value==null?null:value.clone();}
    private static void transaction(boolean read){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=read||(read?!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()):!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED)))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version judgment storage unavailable");}
}
