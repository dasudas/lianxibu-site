package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opaque immutable closure. Consumers must reverify the complete cohort and original progress. */
public final class JdbcSourceDayDedupClosures {
    private static final int MAX_CANONICAL=1114112,MAX_PHYSICAL=2097152;
    private static final String TYPE="source_day_dedup",ACTOR="source-day-dedup-observer",REASON="Original full day dedup barrier closed";
    private static final Set<String> CATEGORIES=Set.of("news_tech","agent","skill","workflow","model");
    public record Archive(LocalDate day,String category,String ownerId,String bundleHash,String cohortHash,String closureJson,String closureHash,Instant observedAt,String json){
        @Override public String toString(){return "SourceDayDedupArchive[redacted]";}
    }
    public static final class Prepared {
        private final Archive value;
        private Prepared(Archive value){this.value=value;}
        @Override public String toString(){return "PreparedSourceDayDedupArchive[redacted]";}
    }
    private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcSourceDayCohortSeals parents;
    public JdbcSourceDayDedupClosures(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);parents=new JdbcSourceDayCohortSeals(source);}
    public static Prepared prepare(String envelope){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        var archive=decode(envelope,MAX_CANONICAL);if(!archive.json().equals(envelope))throw invalid();return new Prepared(archive);
    }
    public Archive save(Prepared prepared){
        JdbcFrozenInputs.requireTx();if(prepared==null)throw invalid();var value=prepared.value;var previous=read(value.day(),value.category());
        if(previous!=null){if(!previous.equals(value))throw invalid();return previous;}parent(value);
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(id(value.day(),value.category()),key(value.day(),value.category()),TYPE,
                target(value),ACTOR,REASON,value.json()),value.observedAt());
        var stored=read(value.day(),value.category());if(!value.equals(stored))throw invalid();return stored;
    }
    public Archive read(LocalDate day,String category){
        requireTx();String action=key(day,category),id=id(day,category);byte[] identity=identity(action);
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 2097152 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,created_at,updated_at,eligible_at FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
            var archive=decode(r.getString(16),MAX_PHYSICAL);String target=r.getString(11);if(target==null)throw invalid();target=canonical(CanonicalJson.parse(target));
            if(!day.equals(archive.day())||!category.equals(archive.category())||!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))
                    ||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!Arrays.equals(identity,r.getBytes(8))||!Arrays.equals(sha(action),r.getBytes(9))
                    ||!TYPE.equals(r.getString(10))||!target(archive).equals(target)||!Arrays.equals(sha(target),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!REASON.equals(r.getString(14))
                    ||!archive.observedAt().equals(time(r.getTimestamp(15,utc())))||!Arrays.equals(sha(archive.json()),r.getBytes(17))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(18))
                    ||!"disposition".equals(r.getString(19))||!id.equals(r.getString(20))||!archive.observedAt().equals(time(r.getTimestamp(21,utc())))||!archive.observedAt().equals(time(r.getTimestamp(22,utc())))||!archive.observedAt().equals(time(r.getTimestamp(23,utc()))))throw invalid();
            return archive;
        },id,sha(action),identity);
        if(rows.size()>1)throw invalid();if(rows.isEmpty())return null;var value=rows.getFirst();parent(value);return value;
    }
    /** Locks the original closure, complete cohort receipt and original directory storage in RC. */
    public Archive lockAndRead(LocalDate day,String category){
        JdbcFrozenInputs.requireTx();String owner=JdbcSourceDayRosters.owner(day);
        var locked=sql.query("SELECT id FROM jobs WHERE id=? OR owner_job_id=? OR action_key_hash IN (?,?) ORDER BY id LIMIT 17 FOR UPDATE",(r,n)->r.getString(1),owner,owner,sha("source-day-cohort:"+day),sha(key(day,category)));
        if(locked.size()>16)throw invalid();return read(day,category);
    }
    private void parent(Archive value){
        var original=parents.read(value.day());if(original==null||!value.ownerId().equals(original.ownerId())||!value.bundleHash().equals(original.bundleHash())||value.observedAt().isBefore(original.sealedAt()))throw invalid();
        var categories=CanonicalJson.parse(original.bundleJson()).get("manifest").get("categories");boolean found=false;
        for(var descriptor:categories)if(value.category().equals(text(descriptor,"category"))){if(found||!value.cohortHash().equals(text(descriptor,"cohort_hash")))throw invalid();found=true;}
        if(!found)throw invalid();
    }
    private static Archive decode(String raw,int limit){
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>limit)throw invalid();var root=CanonicalJson.parse(raw);
        closed(root,"schema_version","business_date","category","directory_owner_id","bundle_hash","cohort_hash","closure_hash","closure");
        if(!"source-day-dedup-seal-v1".equals(text(root,"schema_version")))throw invalid();LocalDate day=WireValues.businessDate(root.get("business_date"));String category=text(root,"category");key(day,category);
        String owner=WireValues.id(root.get("directory_owner_id"),false),bundle=WireValues.sha256(root.get("bundle_hash")),cohort=WireValues.sha256(root.get("cohort_hash")),hash=WireValues.sha256(root.get("closure_hash"));
        if(!JdbcSourceDayRosters.owner(day).equals(owner))throw invalid();var closure=root.get("closure");closed(closure,"schema_version","cohort_hash","observed_at","cutoff","closed_early","sources");
        if(!"source-day-dedup-closure-v1".equals(text(closure,"schema_version"))||!cohort.equals(text(closure,"cohort_hash"))||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,closure))
                ||!closure.get("closed_early").isBoolean()||!closure.get("sources").isArray()||closure.get("sources").size()>10000)throw invalid();
        var observed=WireValues.utcMicroseconds(closure.get("observed_at"));if(observed.isBefore(WireValues.utcMicroseconds(closure.get("cutoff"))))throw invalid();
        String body=canonical(closure),envelope=canonical(root);if(body.getBytes(StandardCharsets.UTF_8).length>1048576||envelope.getBytes(StandardCharsets.UTF_8).length>MAX_CANONICAL)throw invalid();
        return new Archive(day,category,owner,bundle,cohort,body,hash,observed,envelope);
    }
    public static String key(LocalDate day,String category){if(day==null||!CATEGORIES.contains(category))throw invalid();return "source-day-dedup:"+category+":"+day;}
    private static String id(LocalDate day,String category){key(day,category);return JdbcFrozenInputs.stableId("source-day-dedup",category+"\n"+day);}
    private static String target(Archive value){return canonical(JsonMapper.shared().createObjectNode().put("business_date",value.day().toString()).put("category",value.category()).put("directory_owner_id",value.ownerId()).put("bundle_hash",value.bundleHash()).put("cohort_hash",value.cohortHash()));}
    private static byte[] identity(String action){return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action)));}
    private static byte[] sha(String value){return JdbcGenerationLineage.hash(value);}
    private static String text(JsonNode node,String field){return WireValues.text(node.get(field));}
    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static void closed(JsonNode node,String...fields){var keys=Set.of(fields);WireValues.object(node,keys,keys);}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{return new java.math.BigInteger(value.toString()).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant time(java.sql.Timestamp value){if(value==null)throw invalid();return value.toInstant();}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original source day dedup archive unavailable");}
}
