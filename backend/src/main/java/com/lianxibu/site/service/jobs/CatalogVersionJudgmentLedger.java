package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed first-judgment records. Reads preserve original decisions after current entity/source versions change. */
public final class CatalogVersionJudgmentLedger {
    public static final class Stored {
        private final String json,key,category,entity,verdict,hash,intent;
        private final Long generation;private final Instant recordedAt;
        private Stored(String json,String key,String category,String entity,String verdict,String hash,Long generation,String intent,Instant recordedAt){this.json=json;this.key=key;this.category=category;this.entity=entity;this.verdict=verdict;this.hash=hash;this.generation=generation;this.intent=intent;this.recordedAt=recordedAt;}
        public String json(){return json;}public String judgmentKey(){return key;}public String category(){return category;}public String entityId(){return entity;}public String verdict(){return verdict;}public String judgmentHash(){return hash;}public Long generation(){return generation;}public String intentId(){return intent;}public Instant recordedAt(){return recordedAt;}
        @Override public String toString(){return "StoredCatalogVersionJudgment[redacted]";}
    }
    private final JdbcCatalogVersionJudgments jdbc;private final String category;
    public CatalogVersionJudgmentLedger(JdbcCatalogVersionJudgments jdbc,String category){this.jdbc=Objects.requireNonNull(jdbc);this.category=category(category);}
    public Optional<Stored> read(String key){return decode(jdbc.read(key),key);}
    public Optional<Stored> lockAndRead(String key){return decode(jdbc.lockAndRead(key),key);}
    /** Pure proposed receipt only; a writer must separately validate native proof and allocated generation. */
    public static String receipt(CatalogVersionJudgmentValue.Value value,Long generation,String intent,Instant recordedAt){
        Objects.requireNonNull(value);var n=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-judgment-ledger-v1").put("judgment_value_hash",value.hash()).put("recorded_at",time(recordedAt));n.set("judgment_value",CanonicalJson.parse(value.json()));if(generation==null)n.putNull("allocated_generation");else n.put("allocated_generation",generation);if(intent==null)n.putNull("generation_intent_id");else n.put("generation_intent_id",intent);String raw=JdbcFrozenInputs.canonical(n);parse(raw);return raw;
    }
    private Optional<Stored> decode(JdbcCatalogVersionJudgments.Raw raw,String key){
        try{
            if(raw==null)return Optional.empty();if(raw.targetBytes()==null||raw.targetBytes()<1||raw.targetBytes()>2048||raw.payloadBytes()==null||raw.payloadBytes()<1||raw.payloadBytes()>32768)throw invalid();
            String target=JdbcCatalogVersionJudgments.target(key),payload=JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload()));
            if(!JdbcCatalogVersionJudgments.id(key).equals(raw.id())||!"disposition".equals(raw.kind())||!"applied".equals(raw.state())||!Long.valueOf(0).equals(raw.revision())||!JdbcCatalogVersionJudgments.TYPE.equals(raw.targetType())||!JdbcCatalogVersionJudgments.ACTOR.equals(raw.actor())||!JdbcCatalogVersionJudgments.REASON.equals(raw.reason())||!target.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.target())))||!Arrays.equals(raw.identityHash(),JdbcCatalogVersionJudgments.identity(key))||!Arrays.equals(raw.actionHash(),JdbcGenerationLineage.hash(JdbcCatalogVersionJudgments.action(key)))||!Arrays.equals(raw.targetHash(),JdbcGenerationLineage.hash(target))||!Arrays.equals(raw.payloadHash(),JdbcGenerationLineage.hash(payload)))throw invalid();
            var stored=parse(payload);if(!key.equals(stored.judgmentKey())||!category.equals(stored.category())||!stored.recordedAt().equals(raw.actedAt()))throw invalid();return Optional.of(stored);
        }catch(RuntimeException rejected){throw invalid();}
    }
    static Stored parse(String raw){
        try{
            if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>32768)throw invalid();var n=CanonicalJson.parse(raw);var fields=Set.of("schema_version","judgment_value","judgment_value_hash","allocated_generation","generation_intent_id","recorded_at");WireValues.object(n,fields,fields);if(!"catalog-version-judgment-ledger-v1".equals(text(n,"schema_version")))throw invalid();
            var v=n.get("judgment_value");var names=Set.of("schema_version","judgment_key","category","entity_id","item_id","version_changed","reanalyze_recommended","verdict","input_root_id","input_hash","result_hash","projection_hash","version_hash","qualification_hash","reason","confidence","previous_evidence_ref","new_evidence_ref");WireValues.object(v,names,names);if(!"catalog-version-judgment-value-v1".equals(text(v,"schema_version"))||CanonicalJson.bytes(v).length>16384)throw invalid();
            String cat=category(text(v,"category")),entity=WireValues.id(v.get("entity_id"),false),key=hash(v,"judgment_key");WireValues.id(v.get("item_id"),false);WireValues.id(v.get("input_root_id"),false);for(String name:List.of("input_hash","result_hash","projection_hash","version_hash","qualification_hash"))hash(v,name);
            var identity=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-judgment-key-v1").put("category",cat).put("entity_id",entity).put("version_hash",hash(v,"version_hash")).put("qualification_hash",hash(v,"qualification_hash"));if(!key.equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,identity)))throw invalid();
            if(!v.get("version_changed").isBoolean()||!v.get("reanalyze_recommended").isBoolean())throw invalid();boolean changed=v.get("version_changed").booleanValue(),recommended=v.get("reanalyze_recommended").booleanValue();String verdict=changed?(recommended?"reanalyze":"changed_declined"):"unchanged";if(!verdict.equals(text(v,"verdict"))||!changed&&recommended)throw invalid();
            var refs=Set.of("kind","proof_hash");for(String name:List.of("previous_evidence_ref","new_evidence_ref")){var ref=v.get(name);WireValues.object(ref,refs,refs);if(!"catalog_version_evidence".equals(text(ref,"kind")))throw invalid();hash(ref,"proof_hash");}if(v.get("previous_evidence_ref").equals(v.get("new_evidence_ref")))throw invalid();
            String reason=text(v,"reason"),confidence=text(v,"confidence");if(reason.codePointCount(0,reason.length())<1||reason.codePointCount(0,reason.length())>2048||!confidence.matches("0(\\.[0-9]{1,8})?|1(\\.0{1,8})?"))throw invalid();String judgmentHash=hash(n,"judgment_value_hash");if(!judgmentHash.equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,v)))throw invalid();
            Long generation=n.get("allocated_generation").isNull()?null:WireValues.nonnegativeInteger(n.get("allocated_generation"));String intent=n.get("generation_intent_id").isNull()?null:WireValues.id(n.get("generation_intent_id"),false);if(recommended?(generation==null||generation<1||intent==null):(generation!=null||intent!=null))throw invalid();Instant at=WireValues.utcMicroseconds(n.get("recorded_at"));String canonical=JdbcFrozenInputs.canonical(n);return new Stored(canonical,key,cat,entity,verdict,judgmentHash,generation,intent,at);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static String category(String value){return switch(Objects.requireNonNull(value)){case "agent","skill","workflow","model"->value;default->throw invalid();};}
    private static String text(JsonNode n,String key){return WireValues.text(n.get(key));}
    private static String hash(JsonNode n,String key){String value=text(n,key);if(!value.matches("[0-9a-f]{64}"))throw invalid();return value;}
    private static String time(Instant value){if(value==null||value.getNano()%1000!=0)throw invalid();return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog version judgment unavailable");}
}
