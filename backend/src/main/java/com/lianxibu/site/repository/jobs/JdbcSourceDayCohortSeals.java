package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opaque immutable day bundle. Consumers must verify it against the original full directory. */
public final class JdbcSourceDayCohortSeals {
    private static final int MAX_CANONICAL=2097152,MAX_PHYSICAL=3145728;
    private static final String TYPE="source_day_cohort",ACTOR="source-day-coordinator",REASON="Original full day source cohorts sealed";
    private static final List<String> CATEGORIES=List.of("news_tech","agent","skill","workflow","model");
    private final JdbcTemplate sql;
    public record Archive(LocalDate day,String ownerId,String bundleJson,String bundleHash,Instant sealedAt){
        @Override public String toString(){return "SourceDayCohortArchive[redacted]";}
    }
    public static final class Prepared {
        private final Archive value;private final String action,id,target;
        private final byte[] actionHash,identity,targetHash,payloadHash;
        private Prepared(Archive value){
            this.value=value;action=action(value.day());id=id(value.day());
            target=canonical(JsonMapper.shared().createObjectNode().put("business_date",value.day().toString()).put("owner_job_id",value.ownerId()));
            actionHash=sha(action);identity=identity(action);targetHash=sha(target);payloadHash=sha(value.bundleJson());
        }
        @Override public String toString(){return "PreparedSourceDayCohortSeal[redacted]";}
    }
    public JdbcSourceDayCohortSeals(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public static Prepared prepare(String bundle,String expectedHash,Instant sealedAt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("prepare source day seal outside transaction");
        if(expectedHash==null||!expectedHash.matches("[0-9a-f]{64}"))throw invalid();
        return decode(bundle,expectedHash,sealedAt,MAX_CANONICAL);
    }
    public Archive save(Prepared prepared){
        requireTransaction(true);Objects.requireNonNull(prepared);var value=prepared.value;var previous=read(value.day());
        if(previous!=null){if(!previous.equals(value))throw invalid();return previous;}
        parent(value.day(),value.ownerId());
        sql.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO jobs(id,business_key,task_kind,target_id,state,priority_no,eligible_at,revision,created_at,updated_at,record_kind,record_identity_hash,call_count,action_key_hash,target_type,target_key_json,target_key_hash,operator_id,reason,acted_at,action_payload,action_payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            statement.setString(1,prepared.id);statement.setString(2,"disposition:"+HexFormat.of().formatHex(prepared.identity));statement.setString(3,"disposition");statement.setString(4,prepared.id);statement.setString(5,"applied");statement.setInt(6,0);
            statement.setTimestamp(7,Timestamp.from(value.sealedAt()),utc());statement.setLong(8,0);statement.setTimestamp(9,Timestamp.from(value.sealedAt()),utc());statement.setTimestamp(10,Timestamp.from(value.sealedAt()),utc());
            statement.setString(11,"disposition");statement.setBytes(12,prepared.identity);statement.setInt(13,0);statement.setBytes(14,prepared.actionHash);statement.setString(15,TYPE);statement.setString(16,prepared.target);statement.setBytes(17,prepared.targetHash);statement.setString(18,ACTOR);statement.setString(19,REASON);
            statement.setTimestamp(20,Timestamp.from(value.sealedAt()),utc());statement.setString(21,value.bundleJson());statement.setBytes(22,prepared.payloadHash);return statement;
        });
        return value;
    }
    /** Null means no receipt; damaged originals are errors and are never reconstructed. */
    public Archive read(LocalDate day){
        requireTransaction(false);String action=action(day),id=id(day);byte[] expectedIdentity=identity(action);
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json)<=1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,CASE WHEN OCTET_LENGTH(action_payload)<=3145728 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,created_at,updated_at,eligible_at FROM jobs WHERE action_key_hash=? OR id=? OR record_identity_hash=? LIMIT 2",(rs,index)->{
            String body=rs.getString(16);Instant acted=instant(rs.getTimestamp(15,utc()));var prepared=decode(body,null,acted,MAX_PHYSICAL);var archive=prepared.value;
            String target=rs.getString(11);if(target==null||target.getBytes(StandardCharsets.UTF_8).length>1024)throw invalid();target=canonical(CanonicalJson.parse(target));
            if(!day.equals(archive.day())||!id.equals(rs.getString(1))||!"disposition".equals(rs.getString(2))||!"applied".equals(rs.getString(3))||number(rs.getObject(4))!=0||number(rs.getObject(5))!=0||rs.getObject(6)!=null||number(rs.getObject(7))!=0
                    ||!same(rs.getBytes(8),expectedIdentity)||!same(rs.getBytes(9),sha(action))||!TYPE.equals(rs.getString(10))||!prepared.target.equals(target)||!same(rs.getBytes(12),prepared.targetHash)||!ACTOR.equals(rs.getString(13))||!REASON.equals(rs.getString(14))||!same(rs.getBytes(17),prepared.payloadHash)
                    ||!("disposition:"+HexFormat.of().formatHex(expectedIdentity)).equals(rs.getString(18))||!"disposition".equals(rs.getString(19))||!id.equals(rs.getString(20))||!acted.equals(instant(rs.getTimestamp(21,utc())))||!acted.equals(instant(rs.getTimestamp(22,utc())))||!acted.equals(instant(rs.getTimestamp(23,utc()))))throw invalid();
            return archive;
        },sha(action),id,expectedIdentity);
        if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();var result=rows.getFirst();parent(day,result.ownerId());return result;
    }
    private void parent(LocalDate day,String owner){
        String logical="source-day-roster:"+day;
        var rows=sql.query("SELECT record_kind,task_type,logical_key,logical_key_hash,generation,business_date,state,input_state,revision,call_count,max_attempts,priority_no,record_identity_hash FROM jobs WHERE id=?",(rs,index)->
            "generation_intent".equals(rs.getString(1))&&"source_day_roster".equals(rs.getString(2))&&logical.equals(rs.getString(3))&&same(rs.getBytes(4),sha(logical))&&number(rs.getObject(5))==1&&day.toString().equals(rs.getString(6))&&"waiting_input".equals(rs.getString(7))&&"waiting_input".equals(rs.getString(8))&&number(rs.getObject(9))==0&&number(rs.getObject(10))==0&&rs.getObject(11)==null&&number(rs.getObject(12))==0&&same(rs.getBytes(13),identity("generation_intent",logical+"\u0000"+1)),owner);
        if(!JdbcSourceDayRosters.owner(day).equals(owner)||rows.size()!=1||!rows.getFirst())throw invalid();
    }
    private static Prepared decode(String body,String expectedHash,Instant sealedAt,int physicalLimit){
        if(body==null||body.getBytes(StandardCharsets.UTF_8).length>physicalLimit)throw invalid();JsonNode root=CanonicalJson.parse(body);closed(root,"schema_version","manifest","cohorts");
        if(!"source-day-cohort-bundle-v1".equals(text(root,"schema_version")))throw invalid();String canonical=canonical(root);if(canonical.getBytes(StandardCharsets.UTF_8).length>MAX_CANONICAL)throw invalid();
        String hash=CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,root);if(expectedHash!=null&&!hash.equals(expectedHash))throw invalid();
        var manifest=root.get("manifest");closed(manifest,"schema_version","business_date","directory_owner_id","directory_hash","registered_at","source_count","categories","unassigned");
        if(!"source-day-cohort-plan-v1".equals(text(manifest,"schema_version"))||WireValues.nonnegativeInteger(manifest.get("source_count"))>10000)throw invalid();WireValues.sha256(manifest.get("directory_hash"));
        LocalDate day=WireValues.businessDate(manifest.get("business_date"));String owner=text(manifest,"directory_owner_id");Instant registeredAt=WireValues.utcMicroseconds(manifest.get("registered_at"));window(day,registeredAt);window(day,sealedAt);if(sealedAt.isBefore(registeredAt)||!JdbcSourceDayRosters.owner(day).equals(owner))throw invalid();
        var categories=manifest.get("categories");var cohorts=root.get("cohorts");if(!categories.isArray()||categories.size()!=5||!cohorts.isArray()||cohorts.size()!=5||!manifest.get("unassigned").isArray())throw invalid();
        for(int i=0;i<5;i++){var descriptor=categories.get(i);closed(descriptor,"category","cohort_hash");if(!CATEGORIES.get(i).equals(text(descriptor,"category"))||!CATEGORIES.get(i).equals(text(cohorts.get(i),"category"))||!WireValues.sha256(descriptor.get("cohort_hash")).equals(CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,cohorts.get(i))))throw invalid();}
        return new Prepared(new Archive(day,owner,canonical,hash,sealedAt));
    }
    private static String action(LocalDate day){JdbcSourceDayRosters.owner(day);return "source-day-cohort:"+day;}
    private static String id(LocalDate day){return JdbcFrozenInputs.stableId("source-day-cohort",day.toString());}
    private static byte[] identity(String action){return identity("disposition",action);}
    private static byte[] identity(String kind,String natural){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind",kind).put("natural_key",natural)));}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static boolean same(byte[] left,byte[] right){return left!=null&&MessageDigest.isEqual(left,right);}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{return new java.math.BigInteger(value.toString()).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(Timestamp value){if(value==null)throw invalid();return value.toInstant();}
    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static String text(JsonNode node,String key){return WireValues.text(node.get(key));}
    private static void closed(JsonNode node,String...keys){var fields=Set.of(keys);WireValues.object(node,fields,fields);}
    private static void window(LocalDate day,Instant value){if(value==null||value.getNano()%1000!=0||value.isBefore(day.plusDays(1).atTime(0,10).atZone(ZoneId.of("Asia/Shanghai")).toInstant())||!value.isBefore(day.plusDays(1).atTime(4,30).atZone(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();}
    private static void requireTransaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||writer&&TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source day seal transaction required");}
    private static IllegalStateException invalid(){return new IllegalStateException("immutable source day cohort seal unavailable");}
    @Override public String toString(){return "JdbcSourceDayCohortSeals[redacted]";}
}
