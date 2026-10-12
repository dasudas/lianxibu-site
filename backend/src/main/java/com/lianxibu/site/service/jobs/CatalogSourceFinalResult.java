package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Complete catalog prose and structural output. This pure value grants no native commit authority. */
public final class CatalogSourceFinalResult {
 public static final int MAX_BYTES=16*1024*1024;
 public record Missing(String kind,String id,String reason) { }
 private final String json,hash,summary,coverageState;private final List<Missing> missing;
 private CatalogSourceFinalResult(String json,String hash,String summary,String coverage,List<Missing> missing){this.json=json;this.hash=hash;this.summary=summary;coverageState=coverage;this.missing=List.copyOf(missing);}
 public String json(){return json;}public String hash(){return hash;}public String summary(){return summary;}public String coverageState(){return coverageState;}public List<Missing> missing(){return missing;}
 public static CatalogSourceFinalResult create(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceDedupStageResult whole,CollectionHandoffProofService.Proof collection){return build(plan,universe,whole,collection,false,MAX_BYTES);}
 public static CatalogSourceFinalResult createPartial(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceDedupStageResult whole,CollectionHandoffProofService.Proof collection){return build(plan,universe,whole,collection,true,MAX_BYTES);}
 static CatalogSourceFinalResult createWithinCapacity(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceDedupStageResult whole,CollectionHandoffProofService.Proof collection,int maximum){return build(plan,universe,whole,collection,false,maximum);}
 private static CatalogSourceFinalResult build(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceDedupStageResult whole,CollectionHandoffProofService.Proof collection,boolean partial,int maximum){
  try {
   if(maximum<1||maximum>MAX_BYTES||plan==null||universe==null||whole==null||collection==null||plan.scope()==null)throw invalid();
   String category=switch(plan.stage()){case AGENT_DEDUP->"agent";case SKILL_DEDUP->"skill";case WORKFLOW_DEDUP->"workflow";case MODEL_DEDUP->"model";default->throw invalid();};
   new SourcePartitionPlanner().validate(plan,universe);var reference=FrozenSourceStagePlan.encode(plan,universe).reference();var facts=collection.facts();
   if(!reference.equals(whole.planRef())||!reference.ownerId().equals(collection.intentId())||!plan.logicalKey().equals(CollectionHandoffProofService.logicalKey(facts))||plan.generation()!=facts.generation()
     ||!category.equals(facts.category())||!universe.runId().equals(facts.runId())||!universe.sourceId().equals(facts.sourceId())||!plan.scope().businessDate().equals(facts.businessDate())
     ||!"production".equals(facts.activationStage())||"trial".equals(facts.runKind())||!universe.manifestHash().equals(facts.manifestHash())||facts.expected()!=universe.members().size()||facts.accepted()!=universe.accepted()||facts.excluded()!=universe.excluded())throw invalid();
   var manifest=FrozenCollectionManifest.create(List.of(collection));
   for(var chunk:plan.chunks())if(!FrozenCollectionManifest.read(CanonicalJson.parse(chunk.snapshotJson()).get(FrozenCollectionManifest.FIELD)).equals(List.of(collection)))throw invalid();
   var original=CanonicalJson.parse(whole.json());var key=CanonicalJson.parse(whole.observationKeyJson());
   if(!(partial?"source-dedup-stage-result-v2":"source-dedup-stage-result-v1").equals(WireValues.text(original.get("schema_version")))||!whole.hash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,original))
      ||!category.concat("_dedup").equals(WireValues.text(key.get("stage")))||!whole.decisions().equals(plan.decisions()))throw invalid();
   var omissions=new TreeSet<Missing>(Comparator.comparing(Missing::kind).thenComparing(Missing::id).thenComparing(Missing::reason));
   if(Set.of("partial","no_usable_input").contains(facts.deliveryState()))omissions.add(new Missing("source_run",universe.runId(),"collection_partial"));
   if(partial){
    if(!"partial".equals(WireValues.text(original.get("coverage_state")))||original.get("resolutions").size()!=plan.chunks().size())throw invalid();
    for(var row:original.get("resolutions")){String state=WireValues.text(row.get("state"));if(!"succeeded".equals(state)){if(!Set.of("failed","cancelled","discarded","superseded").contains(state))throw invalid();omissions.add(new Missing("source_fragment",WireValues.id(row.get("root_id"),false),"dedup_"+state));}}
   }
   var members=new HashMap<String,com.lianxibu.site.service.sources.SourceMemberStageService.Fact>();
   for(var member:whole.members()){if(!category.equals(member.category())||members.putIfAbsent(member.itemId(),member)!=null)throw invalid();if("needs_review".equals(member.state()))omissions.add(new Missing("source_item",member.itemId(),"dedup_needs_review"));}
   var summaries=new ArrayList<String>();var seen=new HashSet<String>();int fragments=0;
   for(var fragment:original.get("fragments")){
    String root=WireValues.id(fragment.get("root_id"),false);String prose=WireValues.text(fragment.get("payload").get("summary"));
    if(prose.isBlank())omissions.add(new Missing("source_fragment",root,"summary_missing"));else if(blocked(prose,plan.scope().settings().blockedKeywords()))omissions.add(new Missing("source_fragment",root,"preference_blocked_summary"));else summaries.add(prose);
    for(var row:fragment.get("payload").get("items")){String id=WireValues.id(row.get("item_id"),false);if(!category.equals(WireValues.text(row.get("category")))||!members.containsKey(id)||!seen.add(id))throw invalid();}fragments++;
   }
   long eligible=WireValues.nonnegativeInteger(original.get("eligible_count"));if(seen.size()!=members.size()||members.size()>eligible||!partial&&members.size()!=eligible)throw invalid();
   if(partial&&(WireValues.nonnegativeInteger(original.get("applied_member_count"))!=members.size()||WireValues.nonnegativeInteger(original.get("unapplied_member_count"))!=eligible-members.size()))throw invalid();
   String prose=eligible==0?"本来源没有本次待去重输入。":members.isEmpty()?"本来源原计划包含 "+eligible+" 条去重输入，尚未获得已应用的去重结果。":summaries.isEmpty()?"本来源完成 "+members.size()+" 条去重登记，未获得可用文字简报。":String.join("\n\n",summaries);
   String coverage=omissions.isEmpty()?"complete":"partial";if(!omissions.isEmpty())prose="本次来源去重覆盖不完整。\n\n"+prose;
   if(prose.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES-1)throw new GenerationFlowService.CapacityGuarded();
   var output=JsonMapper.shared().createObjectNode().put("schema_version",partial?"catalog-source-final-result-v2":"catalog-source-final-result-v1").put("category",category).put("run_id",universe.runId()).put("source_id",universe.sourceId())
    .put("logical_key",plan.logicalKey()).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString()).put("whole_result_hash",whole.hash()).put("eligible_count",eligible).put("applied_member_count",members.size()).put("unapplied_member_count",eligible-members.size()).put("stage_part_count",plan.chunks().size()).put("fragment_count",fragments).put("source_summary",prose).put("coverage_state",coverage);
   output.set("plan_ref",CanonicalJson.parse(reference.json()));output.set("stage_key",key);output.set("stage_result",original);output.set(FrozenCollectionManifest.FIELD,manifest);var missing=new ArrayList<>(omissions);output.set("missing",JsonMapper.shared().valueToTree(missing));
   byte[] bytes=CanonicalJson.bytes(output);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();return new CatalogSourceFinalResult(new String(bytes,StandardCharsets.UTF_8),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,output),prose,coverage,missing);
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
 }
 private static boolean blocked(String text,List<String> words){String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);return words.stream().anyMatch(word->normalized.contains(Normalizer.normalize(word.strip(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)));}
 private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog final result unavailable");}
 @Override public String toString(){return "CatalogSourceFinalResult[redacted]";}
}
