package com.lianxibu.site.repository.jobs;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded native final dispositions; the service validates their closed business metadata. */
public final class JdbcCatalogSourceFinalReceipts {
    public static final String TYPE="catalog_source_final_result",ACTOR="catalog-source-finalizer",REASON="Committed original final catalog source output";
    public record Raw(String id,String kind,String targetType,String target,byte[] targetHash,String actor,String reason,String payload,byte[] payloadHash,byte[] actionHash,Instant actedAt,Long targetBytes,Long payloadBytes){
        public Raw {targetHash=targetHash==null?null:targetHash.clone();payloadHash=payloadHash==null?null:payloadHash.clone();actionHash=actionHash==null?null:actionHash.clone();}
        @Override public byte[] targetHash(){return targetHash==null?null:targetHash.clone();}
        @Override public byte[] payloadHash(){return payloadHash==null?null:payloadHash.clone();}
        @Override public byte[] actionHash(){return actionHash==null?null:actionHash.clone();}
        @Override public String toString(){return "CatalogSourceFinalNativeReceipt[redacted]";}
    }
    private static final String COLUMNS="id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 32768 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload)";
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;private final JdbcFrozenSourcePlans owners;
    public JdbcCatalogSourceFinalReceipts(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);owners=new JdbcFrozenSourcePlans(source);}
    public JdbcFrozenSourcePlans.Owner owner(String owner){return owners.immutableOwner(owner);}
    public record Cursor(String ownerId,long revision,String state){@Override public String toString(){return "CatalogSourceFinalIntentCursor[redacted]";}}
    public Cursor lock(String owner){
        JdbcFrozenInputs.requireTx();var rows=jdbc.query("SELECT id,revision,state FROM jobs WHERE id=? AND record_kind='generation_intent' FOR UPDATE",(r,n)->{Long revision=r.getObject(2,Long.class);if(revision==null||revision<0||revision>=9_007_199_254_740_991L)throw invalid();return new Cursor(r.getString(1),revision,r.getString(3));},owner);if(rows.size()!=1||!owner.equals(rows.getFirst().ownerId()))throw invalid();return rows.getFirst();
    }
    public void complete(Cursor original,boolean noInput,Instant now){JdbcFrozenInputs.requireTx();if(jdbc.update("UPDATE jobs SET state=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='generation_intent' AND revision=? AND state=?",noInput?"no_input":"succeeded",java.sql.Timestamp.from(now),original.ownerId(),original.revision(),original.state())!=1)throw invalid();}
    public Raw read(String owner){JdbcFrozenSourceUniverses.requireTransaction(false);var rows=jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",JdbcCatalogSourceFinalReceipts::map,receiptId(owner),JdbcGenerationLineage.hash(action(owner)));if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();}
    public List<Raw> refs(List<String> refs){
        JdbcFrozenInputs.requireTx();if(refs==null||refs.size()>4096||Set.copyOf(refs).size()!=refs.size())throw invalid();if(refs.isEmpty())return List.of();String placeholders=String.join(",",Collections.nCopies(refs.size(),"?"));return jdbc.query("SELECT "+COLUMNS+" FROM jobs WHERE id IN ("+placeholders+")",JdbcCatalogSourceFinalReceipts::map,refs.toArray());
    }
    private static Raw map(java.sql.ResultSet r,int ignored)throws java.sql.SQLException{var time=r.getTimestamp(11);return new Raw(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8),r.getBytes(9),r.getBytes(10),time==null?null:time.toInstant(),r.getObject(12,Long.class),r.getObject(13,Long.class));}
    public void append(String owner,String payload,Instant now){
        JdbcFrozenInputs.requireTx();var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",owner);records.appendDisposition(new JdbcJobRecords.Disposition(receiptId(owner),action(owner),TYPE,JdbcFrozenInputs.canonical(target),ACTOR,REASON,payload),now);
    }
    public static String receiptId(String owner){return JdbcFrozenInputs.stableId("catalog-source-final-receipt",owner);}
    public static String action(String owner){return "catalog-source-final-commit:"+owner;}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog source final receipt storage unavailable");}
}
