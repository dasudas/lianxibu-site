package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure immutable judgment from original native projection and original model constraints; no write permission. */
public final class CatalogVersionJudgmentValue {
    public static final class Value {
        private final String json,hash,key,category,entity,member,verdict;
        private final boolean changed,recommended;
        private Value(CatalogVersionQualificationProjection.Projection projection,Input input,Validated result,tools.jackson.databind.JsonNode item){
            key=projection.judgmentKey();category=projection.category();entity=projection.entityId();member=projection.memberId();changed=projection.versionChanged();recommended=item.get("reanalyze_recommended").booleanValue();verdict=changed?(recommended?"reanalyze":"changed_declined"):"unchanged";
            var proof=projection.toJson();var value=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-judgment-value-v1").put("judgment_key",key).put("category",category).put("entity_id",entity).put("item_id",member).put("version_changed",changed).put("reanalyze_recommended",recommended).put("verdict",verdict).put("input_root_id",input.rootId()).put("input_hash",input.inputHash()).put("result_hash",result.resultHash()).put("projection_hash",projection.hash()).put("version_hash",proof.get("current_proof").get("version_hash").asText()).put("qualification_hash",proof.get("current_proof").get("qualification_hash").asText()).put("reason",item.get("reason").asText()).put("confidence",item.get("confidence").asText());
            value.set("previous_evidence_ref",item.get("previous_evidence_ref").deepCopy());value.set("new_evidence_ref",item.get("new_evidence_ref").deepCopy());byte[] bytes=CanonicalJson.bytes(value);if(bytes.length>16384)throw invalid();json=new String(bytes,StandardCharsets.UTF_8);hash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,value);
        }
        public String json(){return json;}public String hash(){return hash;}public String judgmentKey(){return key;}public String category(){return category;}public String entityId(){return entity;}public String memberId(){return member;}public String verdict(){return verdict;}
        public boolean versionChanged(){return changed;}public boolean reanalyzeRecommended(){return recommended;}
        @Override public String toString(){return "CatalogVersionJudgmentValue[redacted]";}
    }
    private CatalogVersionJudgmentValue(){ }
    /** Unknown version, new/review decisions and another candidate create no permanent negative value. */
    public static Optional<Value> create(CatalogVersionQualificationProjection.Projection projection,Input input,Validated result){
        try{Objects.requireNonNull(projection);return Optional.ofNullable(createAll(Map.of(projection.memberId(),projection),input,result).get(projection.memberId()));}catch(RuntimeException rejected){throw invalid();}
    }
    /** One complete result validation and one member selection pass; no writer or native permission. */
    public static Map<String,Value> createAll(Map<String,CatalogVersionQualificationProjection.Projection> projections,Input input,Validated result){
        try{
            Objects.requireNonNull(projections);Objects.requireNonNull(input);Objects.requireNonNull(result);if(projections.size()>100||!"2".equals(input.dedupResultVersion())||!Set.of(GenerationFlowService.Stage.AGENT_DEDUP,GenerationFlowService.Stage.SKILL_DEDUP,GenerationFlowService.Stage.WORKFLOW_DEDUP,GenerationFlowService.Stage.MODEL_DEDUP).contains(input.stage()))throw invalid();String category=input.stage().name().toLowerCase(Locale.ROOT).replace("_dedup","");
            var envelope=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",result.inputHash()).put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw invalid();
            var selected=new HashMap<String,tools.jackson.databind.JsonNode>();for(var item:envelope.get("payload").get("items"))if(selected.putIfAbsent(item.get("item_id").asText(),item)!=null)throw invalid();var values=new HashMap<String,Value>();
            for(var entry:projections.entrySet()){
                var p=Objects.requireNonNull(entry.getValue());if(!entry.getKey().equals(p.memberId())||!category.equals(p.category())||!input.itemIds().contains(p.memberId()))throw invalid();var item=selected.get(p.memberId());if(item==null)throw invalid();if(item.get("version_changed").isNull())continue;
                if(!"existing".equals(item.get("decision").asText())||!p.entityId().equals(item.get("candidate_entity_id").asText())||!p.resultConstraint().equals(input.catalogVersionQualifications().get(p.memberId())))throw invalid();values.put(p.memberId(),new Value(p,input,result,item));
            }
            return Map.copyOf(values);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("catalog version judgment value unavailable");}
}
