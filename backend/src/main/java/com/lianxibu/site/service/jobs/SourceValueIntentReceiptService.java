package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Metadata provider. The consumer separately verifies complete original P1 and source facts. */
public final class SourceValueIntentReceiptService {
    private static final JsonMapper JSON=JsonMapper.shared();
    public record Handoff(FrozenSourceStagePlan.Reference p1Ref,String p1ResultHash,String observationKey,Instant observedAt,String runId,String sourceId,LocalDate day,String p1Key,long p1Generation,long stageParts,String manifestHash,long accepted,long excluded,String p2Owner,String p2Key,long p2Generation,Instant createdAt){
        public Handoff {
            Objects.requireNonNull(p1Ref);hash(p1ResultHash);hash(manifestHash);id(runId,64);id(sourceId,80);id(p2Owner,64);key(p1Key);key(p2Key);number(p1Generation,true);number(p2Generation,true);
            if(day==null||stageParts<0||stageParts>10000||accepted<0||excluded<0||accepted>MemberUniverse.MAX_MEMBERS||excluded>MemberUniverse.MAX_MEMBERS||accepted+excluded>MemberUniverse.MAX_MEMBERS||observedAt==null||createdAt==null||observedAt.getNano()%1000!=0||createdAt.getNano()%1000!=0||createdAt.isBefore(observedAt)||!p2Owner.equals(JdbcFrozenInputs.stableId("intent",p2Key+":"+p2Generation))||!p2Key.equals(valueKey(sourceId,day,p1Key)))throw invalid();
            JdbcSourceStageAvailability.key(observationKey);var k=CanonicalJson.parse(observationKey);
            if(!p1Ref.ownerId().equals(text(k,"owner_job_id"))||!p1Ref.planHash().equals(text(k,"plan_hash"))||!p1Ref.indexHash().equals(text(k,"plan_index_hash"))||!p1ResultHash.equals(text(k,"result_hash"))||!runId.equals(text(k,"run_id"))||!sourceId.equals(text(k,"source_id"))||!day.toString().equals(text(k,"business_date"))||p1Generation!=integer(k,"generation"))throw invalid();
        }
        public static Handoff create(SourceDedupStageResult proof,MemberUniverse.Universe universe,Instant observed,long generation,Instant created){
            Objects.requireNonNull(proof);Objects.requireNonNull(universe);var p=CanonicalJson.parse(proof.json());var ref=FrozenSourceUniverse.Reference.fromJson(canonical(p.get("universe_ref")));
            if(!ref.equals(FrozenSourceUniverse.encode(proof.planRef().ownerId(),universe).reference()))throw invalid();String key=text(p,"logical_key");LocalDate day=LocalDate.parse(text(p,"business_date"));String valueKey=valueKey(universe.sourceId(),day,key);
            return new Handoff(proof.planRef(),proof.hash(),proof.observationKeyJson(),observed,universe.runId(),universe.sourceId(),day,key,integer(p,"generation"),integer(p,"stage_part_count"),universe.manifestHash(),universe.accepted(),universe.excluded(),JdbcFrozenInputs.stableId("intent",valueKey+":"+generation),valueKey,generation,created);
        }
        public String json(){
            var n=JSON.createObjectNode().put("schema_version","source-value-intent-handoff-v1");n.set("p1_plan_ref",CanonicalJson.parse(p1Ref.json()));n.put("p1_result_hash",p1ResultHash).put("p1_observation_key",observationKey).put("p1_observed_at",time(observedAt)).put("run_id",runId).put("source_id",sourceId).put("business_date",day.toString()).put("p1_logical_key",p1Key).put("p1_generation",p1Generation).put("p1_stage_part_count",stageParts).put("manifest_hash",manifestHash).put("accepted_count",accepted).put("excluded_count",excluded).put("p2_intent_id",p2Owner).put("p2_logical_key",p2Key).put("p2_generation",p2Generation).put("created_at",time(createdAt));String raw=canonical(n);if(raw.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();return raw;
        }
        static Handoff parse(String raw){
            if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();var n=CanonicalJson.parse(raw);var fields=Set.of("schema_version","p1_plan_ref","p1_result_hash","p1_observed_at","p1_observation_key","run_id","source_id","business_date","p1_logical_key","p1_generation","p1_stage_part_count","manifest_hash","accepted_count","excluded_count","p2_intent_id","p2_logical_key","p2_generation","created_at");WireValues.object(n,fields,fields);if(!"source-value-intent-handoff-v1".equals(text(n,"schema_version")))throw invalid();
            return new Handoff(FrozenSourceStagePlan.Reference.fromJson(canonical(n.get("p1_plan_ref"))),text(n,"p1_result_hash"),text(n,"p1_observation_key"),WireValues.utcMicroseconds(n.get("p1_observed_at")),text(n,"run_id"),text(n,"source_id"),WireValues.businessDate(n.get("business_date")),text(n,"p1_logical_key"),integer(n,"p1_generation"),integer(n,"p1_stage_part_count"),text(n,"manifest_hash"),integer(n,"accepted_count"),integer(n,"excluded_count"),text(n,"p2_intent_id"),text(n,"p2_logical_key"),integer(n,"p2_generation"),WireValues.utcMicroseconds(n.get("created_at")));
        }
        @Override public String toString(){return "SourceValueIntentHandoff[redacted]";}
    }
    private final JdbcSourceValueIntents store;private final JdbcSourceStageAvailability observations;
    public SourceValueIntentReceiptService(JdbcSourceValueIntents store,JdbcSourceStageAvailability observations){this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);}
    public long nextGeneration(String key,LocalDate day){return store.next(key,day);}
    public Handoff reserve(Handoff value){
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(value);var previous=read(value.p1Ref().ownerId());if(previous!=null){if(!previous.json().equals(value.json()))throw invalid();return previous;}
            var original=validateOriginal(value);if(!Set.of("waiting_input","prepared","queued","running").contains(original.state())||value.p2Generation()!=store.next(value.p2Key(),value.day()))throw invalid();
            store.create(value.p2Owner(),value.p2Key(),value.p2Generation(),value.day(),value.createdAt());store.append(value.p1Ref().ownerId(),value.p2Owner(),value.json(),value.createdAt());var saved=read(value.p1Ref().ownerId());if(saved==null||!saved.json().equals(value.json()))throw invalid();return saved;
        }catch(RuntimeException rejected){throw invalid();}
    }
    public Handoff read(String p1Owner){
        try {
            id(p1Owner,64);var r=store.read(p1Owner);if(r==null)return null;
            if(r.targetBytes()==null||r.targetBytes()<1||r.targetBytes()>2048||r.payloadBytes()==null||r.payloadBytes()<1||r.payloadBytes()>8192)throw invalid();var target=CanonicalJson.parse(r.target());var fields=Set.of("kind","id");WireValues.object(target,fields,fields);String payload=canonical(CanonicalJson.parse(r.payload()));
            if(!receiptId(p1Owner).equals(r.id())||!"disposition".equals(r.kind())||!JdbcSourceValueIntents.TYPE.equals(r.type())||!JdbcSourceValueIntents.ACTOR.equals(r.actor())||!JdbcSourceValueIntents.REASON.equals(r.reason())||!"generation_intent".equals(text(target,"kind"))||!Arrays.equals(r.targetHash(),JdbcGenerationLineage.hash(canonical(target)))||!Arrays.equals(r.payloadHash(),JdbcGenerationLineage.hash(payload))||!Arrays.equals(r.actionHash(),JdbcGenerationLineage.hash(JdbcSourceValueIntents.action(p1Owner))))throw invalid();
            var value=Handoff.parse(payload);if(!p1Owner.equals(value.p1Ref().ownerId())||!value.p2Owner().equals(text(target,"id"))||!value.createdAt().equals(r.actedAt())||!payload.equals(value.json()))throw invalid();validateOriginal(value);validateOwner(store.owner(value.p2Owner()),value.p2Owner(),value.p2Key(),value.p2Generation(),"news_value",value.day());return value;
        }catch(RuntimeException rejected){throw invalid();}
    }
    private JdbcFrozenSourcePlans.Owner validateOriginal(Handoff value){
        var owner=store.owner(value.p1Ref().ownerId());validateOwner(owner,value.p1Ref().ownerId(),value.p1Key(),value.p1Generation(),"news_dedup",value.day());var receipt=store.originalPlan(owner.id());if(receipt==null||!"source_stage_plan".equals(receipt.targetType()))throw invalid();WireValues.id(receipt.operatorId(),false);
        var target=CanonicalJson.parse(receipt.targetKeyJson());var two=Set.of("kind","id");WireValues.object(target,two,two);if(!"generation_intent".equals(text(target,"kind"))||!owner.id().equals(text(target,"id")))throw invalid();
        var p=CanonicalJson.parse(receipt.payloadJson());var fields=Set.of("schema_version","owner_job_id","logical_key","generation","stage","business_date","universe_ref","plan_ref");WireValues.object(p,fields,fields);
        if(!"source-stage-plan-receipt-v1".equals(text(p,"schema_version"))||!value.p1Ref().equals(FrozenSourceStagePlan.Reference.fromJson(canonical(p.get("plan_ref"))))||!owner.id().equals(text(p,"owner_job_id"))||!value.p1Key().equals(text(p,"logical_key"))||value.p1Generation()!=integer(p,"generation")||!"news_dedup".equals(text(p,"stage"))||!value.day().toString().equals(text(p,"business_date")))throw invalid();
        var u=FrozenSourceUniverse.Reference.fromJson(canonical(p.get("universe_ref")));var k=CanonicalJson.parse(value.observationKey());if(!owner.id().equals(u.ownerId())||!value.runId().equals(u.runId())||!u.universeHash().equals(text(k,"universe_hash"))||!u.indexHash().equals(text(k,"universe_index_hash"))||!value.observedAt().equals(observations.read(value.observationKey())))throw invalid();return owner;
    }
    private static void validateOwner(JdbcFrozenSourcePlans.Owner row,String id,String key,long generation,String type,LocalDate day){if(row==null||!id.equals(row.id())||!"generation_intent".equals(row.kind())||!key.equals(row.key())||generation!=row.generation()||!type.equals(row.type())||!day.equals(row.businessDate())||!Arrays.equals(row.keyHash(),JdbcGenerationLineage.hash(key))||!id.equals(JdbcFrozenInputs.stableId("intent",key+":"+generation)))throw invalid();}
    public static String receiptId(String owner){return JdbcSourceValueIntents.receiptId(owner);}
    private static String valueKey(String source,LocalDate day,String key){var n=JSON.createObjectNode().put("source_id",source).put("business_date",day.toString()).put("p1_logical_key",key);return "news-value:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,n);}
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}private static long integer(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
    private static String canonical(JsonNode n){return JdbcFrozenInputs.canonical(n);}private static String time(Instant n){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(n);}
    private static void id(String n,int max){if(n==null||n.length()>max||!n.matches("[A-Za-z0-9._:-]{1,"+max+"}"))throw invalid();}private static void key(String n){if(n==null||n.isBlank()||n.length()>4096)throw invalid();}private static void hash(String n){if(n==null||!n.matches("[0-9a-f]{64}"))throw invalid();}private static void number(long n,boolean positive){if(n<(positive?1:0)||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original source value intent metadata unavailable");}
}
