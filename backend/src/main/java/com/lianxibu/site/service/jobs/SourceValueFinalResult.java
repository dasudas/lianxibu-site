package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Programmatic final source selection and full prose concatenation from an original P2 structure. */
public final class SourceValueFinalResult {
    public static final int MAX_BYTES=16*1024*1024;
    public record Selected(String itemId,String entityId,String rootId,String resultHash,String category,String reason){@Override public String toString(){return "SelectedSourceValue[redacted]";}}
    public record Missing(String kind,String id,String reason){ }
    private record Candidate(Selected selected,boolean blocked){ }
    private final String json,hash,summary,coverageState;private final List<Selected> selected;private final List<Missing> missing;
    private SourceValueFinalResult(String json,String hash,String summary,String coverage,List<Selected> selected,List<Missing> missing){this.json=json;this.hash=hash;this.summary=summary;coverageState=coverage;this.selected=List.copyOf(selected);this.missing=List.copyOf(missing);}
    public String json(){return json;}public String hash(){return hash;}public String summary(){return summary;}public String coverageState(){return coverageState;}public List<Selected> selected(){return selected;}public List<Missing> missing(){return missing;}
    public static SourceValueFinalResult create(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole){return createWithinCapacity(plan,universe,whole,MAX_BYTES);}
    static SourceValueFinalResult createWithinCapacity(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole,int maximum){
        return build(plan,universe,whole,maximum,false);
    }
    public static SourceValueFinalResult createPartial(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole){return createPartialWithinCapacity(plan,universe,whole,MAX_BYTES);}
    static SourceValueFinalResult createPartialWithinCapacity(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole,int maximum){
        return build(plan,universe,whole,maximum,true);
    }
    private static SourceValueFinalResult build(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole,int maximum,boolean partial){
        try {
            if(maximum<1||maximum>MAX_BYTES||plan==null||whole==null||plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||!whole.planRef().equals(FrozenSourceStagePlan.encode(plan,universe).reference()))throw invalid();
            var original=CanonicalJson.parse(whole.json());if(!(partial?"source-value-stage-result-v2":"source-value-stage-result-v1").equals(WireValues.text(original.get("schema_version"))))throw invalid();
            var context=CanonicalJson.parse(plan.scope().stageContextJson());var omissions=new TreeSet<Missing>(Comparator.comparing(Missing::kind).thenComparing(Missing::id).thenComparing(Missing::reason));
            if(partial){
                if(!"partial".equals(WireValues.text(original.get("coverage_state"))))throw invalid();
                for(var row:original.get("missing")){var fields=Set.of("kind","id","reason");WireValues.object(row,fields,fields);omissions.add(new Missing(WireValues.text(row.get("kind")),WireValues.text(row.get("id")),WireValues.text(row.get("reason"))));}
                if(omissions.isEmpty())throw invalid();
            }else if("partial".equals(context.get("coverage_state").asText()))omissions.add(new Missing("source_run",universe.runId(),"collection_partial"));
            for(var fragment:context.get(FrozenSourceDedupDependency.FIELD).get("stage_result").get("fragments"))for(var fact:fragment.get("members"))if("needs_review".equals(fact.get("state").asText()))omissions.add(new Missing("source_item",fact.get("itemId").asText(),"dedup_needs_review"));
            var proposed=new TreeMap<String,Candidate>();var summaries=new ArrayList<String>();long valuable=0,high=0;
            for(var f:whole.fragments()){
                var payload=CanonicalJson.parse(f.result().payloadJson());var facts=new HashMap<String,Fact>();f.members().forEach(m->facts.put(m.itemId(),m));var values=new HashMap<String,JsonNode>();
                for(var item:payload.get("items")){String id=WireValues.id(item.get("item_id"),false);if(values.putIfAbsent(id,item)!=null||!facts.containsKey(id))throw invalid();if(item.get("valuable").booleanValue())valuable++;if(item.get("high_value").booleanValue())high++;}
                if(values.size()!=facts.size())throw invalid();String summary=WireValues.text(payload.get("summary"));
                if(summary.isBlank())omissions.add(new Missing("source_fragment",f.rootId(),"summary_missing"));else if(blocked(summary,plan.scope().settings().blockedKeywords()))omissions.add(new Missing("source_fragment",f.rootId(),"preference_blocked_summary"));else summaries.add(summary);
                for(var id:payload.get("selected_item_ids")){
                    String itemId=WireValues.id(id,false);var item=values.get(itemId);var fact=facts.get(itemId);if(item==null||fact==null||!item.get("valuable").booleanValue()||!item.get("high_value").booleanValue())throw invalid();
                    var candidate=new Candidate(new Selected(itemId,fact.entityId(),f.rootId(),f.result().resultHash(),WireValues.text(item.get("category")),WireValues.text(item.get("reason"))),blocked(WireValues.text(item.get("reason")),plan.scope().settings().blockedKeywords()));if(proposed.putIfAbsent(itemId,candidate)!=null)throw invalid();
                }
            }
            long eligible=partial?WireValues.nonnegativeInteger(original.get("eligible_count")):whole.members().size();
            var output=JsonMapper.shared().createObjectNode().put("schema_version",partial?"source-value-final-result-v2":"source-value-final-result-v1").put("run_id",universe.runId()).put("source_id",universe.sourceId()).put("logical_key",plan.logicalKey()).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString())
                    .put("whole_result_hash",whole.hash()).put("selection_policy","source-value-stable-item-order-v1").put("eligible_count",eligible).put("valuable_count",valuable).put("high_value_count",high).put("fragment_count",whole.fragments().size());
            if(partial){long applied=WireValues.nonnegativeInteger(original.get("applied_member_count")),unapplied=WireValues.nonnegativeInteger(original.get("unapplied_member_count"));if(applied!=whole.members().size()||applied+unapplied!=eligible)throw invalid();output.put("applied_member_count",applied).put("unapplied_member_count",unapplied).put("stage_part_count",plan.chunks().size());}
            output.set("plan_ref",CanonicalJson.parse(whole.planRef().json()));output.set("stage_key",CanonicalJson.parse(whole.observationKeyJson()));var selected=new ArrayList<Selected>();var entities=new HashSet<String>();var decisions=output.putArray("selection_decisions");
            for(var candidate:proposed.values()){
                var value=candidate.selected();String disposition;
                if(candidate.blocked())disposition="preference_blocked_result";else if(entities.contains(value.entityId()))disposition="entity_duplicate";else if(selected.size()==5)disposition="source_limit";else{disposition="selected";selected.add(value);entities.add(value.entityId());}
                decisions.addObject().put("item_id",value.itemId()).put("entity_id",value.entityId()).put("disposition",disposition);
            }
            output.set("selected_items",JsonMapper.shared().valueToTree(selected));output.put("selected_count",selected.size());String coverage=omissions.isEmpty()?"complete":"partial";
            String summary=whole.members().isEmpty()?"本来源没有可分析输入。":summaries.isEmpty()?"本来源完成 "+whole.members().size()+" 条价值判定，未获得可用文字总结。":String.join("\n\n",summaries);
            if(partial&&whole.members().isEmpty())summary=eligible>0?"本来源原计划包含 "+eligible+" 条价值判定输入，尚未获得已应用的判定结果。":"本来源尚未获得通过原始去重证明的可分析输入。";
            if(!omissions.isEmpty())summary="本次来源覆盖不完整。\n\n"+summary;if(summary.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES-1)throw new GenerationFlowService.CapacityGuarded();
            output.put("source_summary",summary).put("coverage_state",coverage);var missing=new ArrayList<>(omissions);output.set("missing",JsonMapper.shared().valueToTree(missing));byte[] bytes=CanonicalJson.bytes(output);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();
            return new SourceValueFinalResult(new String(bytes,StandardCharsets.UTF_8),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,output),summary,coverage,selected,missing);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static boolean blocked(String text,List<String> words){String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);return words.stream().anyMatch(word->normalized.contains(Normalizer.normalize(word.strip(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)));}
    private static IllegalStateException invalid(){return new IllegalStateException("original final source value result unavailable");}
    @Override public String toString(){return "SourceValueFinalResult[redacted]";}
}
