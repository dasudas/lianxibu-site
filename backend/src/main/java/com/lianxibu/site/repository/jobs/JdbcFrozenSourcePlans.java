package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded immutable plan blocks and their independent receipt in the existing jobs table. */
public final class JdbcFrozenSourcePlans {
    public record Owner(String id,String kind,String key,byte[] keyHash,long generation,String type,LocalDate businessDate,String state) {
        public Owner {keyHash=keyHash==null?null:keyHash.clone();}
        @Override public byte[] keyHash(){return keyHash==null?null:keyHash.clone();}
        @Override public String toString(){return "SourcePlanOwner[redacted]";}
    }
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;private final JdbcFrozenSourceUniverses blocks;
    public JdbcFrozenSourcePlans(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);blocks=new JdbcFrozenSourceUniverses(source);}
    public Owner lockOwner(String id){return owner(id,true);}
    public Owner immutableOwner(String id){return owner(id,false);}
    /** Exact original identity or a genuine absence, including alternate IDs for that intent. */
    public Owner findImmutableOwner(String id,String key,long generation) {
        JdbcFrozenSourceUniverses.requireTransaction(false);
        var rows=jdbc.query("SELECT id,record_kind,logical_key,logical_key_hash,generation,task_type,business_date,state FROM jobs WHERE id=? OR (record_kind='generation_intent' AND logical_key_hash=? AND generation=?) LIMIT 3",
                (rs,n)->mapOwner(rs),id,JdbcGenerationLineage.hash(key),generation);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    private Owner owner(String id,boolean lock) {
        JdbcFrozenSourceUniverses.requireTransaction(lock);
        var rows=jdbc.query("SELECT id,record_kind,logical_key,logical_key_hash,generation,task_type,business_date,state FROM jobs WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->mapOwner(rs),id);
        if(rows.size()!=1||!id.equals(rows.getFirst().id()))throw unavailable();return rows.getFirst();
    }
    private static Owner mapOwner(java.sql.ResultSet rs)throws java.sql.SQLException {
        String day=rs.getString(7);long generation=rs.getLong(5);if(rs.wasNull()||day==null)throw unavailable();
        return new Owner(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBytes(4),generation,rs.getString(6),LocalDate.parse(day),rs.getString(8));
    }
    public boolean immutableHasRoots(String owner){JdbcFrozenSourceUniverses.requireTransaction(false);return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM jobs WHERE record_kind='logical_task' AND intent_id=?)",Boolean.class,owner));}
    public boolean hasRoots(String owner){JdbcFrozenInputs.requireTx();return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM jobs WHERE record_kind='logical_task' AND intent_id=?)",Boolean.class,owner));}
    public List<JdbcFrozenSourceUniverses.Part> parts(String owner){return blocks.read(owner,stamp(owner));}
    public List<JdbcFrozenSourceUniverses.Part> immutableParts(String owner){return blocks.immutableParts(owner,stamp(owner));}
    public void put(String owner,int number,String payload,Instant now){blocks.put(owner,stamp(owner),number,payload,now);}
    public JdbcJobRecords.Disposition receipt(String owner){return receipt(owner,true);}
    public JdbcJobRecords.Disposition immutableReceipt(String owner){return receipt(owner,false);}
    private JdbcJobRecords.Disposition receipt(String owner,boolean lock) {
        JdbcFrozenSourceUniverses.requireTransaction(lock);String action=action(owner);
        var rows=jdbc.query("SELECT id,record_kind,target_type,target_key_json,target_key_hash,operator_id,reason,action_payload,action_payload_hash,action_key_hash,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload)"
                +" FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3"+(lock?" FOR UPDATE":""),(rs,n)->{
            long targetBytes=rs.getLong(11);if(rs.wasNull()||targetBytes<1||targetBytes>4096)throw unavailable();
            long payloadBytes=rs.getLong(12);if(rs.wasNull()||payloadBytes<1||payloadBytes>4096)throw unavailable();
            String target=rs.getString(4),payload=rs.getString(8);
            if(!receiptId(owner).equals(rs.getString(1))||!"disposition".equals(rs.getString(2))
                    ||!Arrays.equals(rs.getBytes(5),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(target))))
                    ||!Arrays.equals(rs.getBytes(9),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(payload))))
                    ||!Arrays.equals(rs.getBytes(10),JdbcGenerationLineage.hash(action)))throw unavailable();
            return new JdbcJobRecords.Disposition(rs.getString(1),action,rs.getString(3),target,rs.getString(6),rs.getString(7),payload);
        },receiptId(owner),JdbcGenerationLineage.hash(action));
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    public void append(String owner,String actor,String payload,Instant now) {
        JdbcFrozenInputs.requireTx();var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();target.put("kind","generation_intent");target.put("id",owner);
        records.appendDisposition(new JdbcJobRecords.Disposition(receiptId(owner),action(owner),"source_stage_plan",JdbcFrozenInputs.canonical(target),actor,"Frozen complete source stage plan",payload),now);
    }
    public static String stamp(String owner){return JdbcFrozenInputs.stableId("source-stage-plan",owner);}
    public static String receiptId(String owner){return JdbcFrozenInputs.stableId("source-stage-plan-receipt",owner);}
    private static String action(String owner){return "source-plan:"+owner;}
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen source plan storage unavailable");}
}
