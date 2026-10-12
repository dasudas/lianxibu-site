package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** One original P1/P3-P6 result. A storage provider independently establishes applied state. */
public final class SourceDedupFragmentProof {
 private static final Set<String> HANDOFF=Set.of("schema_version","root_id","input_hash","result_hash","stage","generation","stage_part","payload","members");
 private static final Set<String> FACT=Set.of("itemId","runId","sourceId","sourceItemId","category","entityId","state","revision","businessDate","targetBusinessDate","generation","activationStage");
 private final String rootId,handoffHash;private final long generation,stagePart;
 private final ResultApplicationService.Validated result;private final List<Fact> members;
 private SourceDedupFragmentProof(String root,long generation,long part,ResultApplicationService.Validated result,List<Fact> members,String hash){this.rootId=root;this.generation=generation;this.stagePart=part;this.result=result;this.members=List.copyOf(members);this.handoffHash=hash;}
 public String rootId(){return rootId;}public long generation(){return generation;}public long stagePart(){return stagePart;}
 public ResultApplicationService.Validated result(){return result;}public List<Fact> members(){return members;}public String handoffHash(){return handoffHash;}
 public static SourceDedupFragmentProof read(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,String rootId,String envelope,String rawHandoff){
  try {
   WireValues.id(rootId,false);new SourcePartitionPlanner().validate(plan,universe);
   String category=category(plan.stage());if(rawHandoff==null||rawHandoff.getBytes(StandardCharsets.UTF_8).length>262144)throw invalid();
   var selected=plan.chunks().stream().filter(c->rootId.equals(c.rootId())).toList();if(selected.size()!=1)throw invalid();var chunk=selected.getFirst();
   var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
   var input=context.input(rootId,"source-result-proof",chunk.inputHash(),0);var bindings=FrozenMemberBindings.read(snapshot,input);
   var result=ResultApplicationService.validate(input,envelope);var handoff=CanonicalJson.parse(rawHandoff);WireValues.object(handoff,HANDOFF,HANDOFF);
   if(!"source-member-stage-v1".equals(text(handoff,"schema_version"))||!rootId.equals(text(handoff,"root_id"))||!chunk.inputHash().equals(text(handoff,"input_hash"))
      ||!result.resultHash().equals(text(handoff,"result_hash"))||!plan.stage().name().toLowerCase(Locale.ROOT).equals(text(handoff,"stage"))||plan.generation()!=number(handoff,"generation")
      ||chunk.stagePart()!=number(handoff,"stage_part")||!Arrays.equals(CanonicalJson.bytes(handoff.get("payload")),CanonicalJson.bytes(CanonicalJson.parse(result.payloadJson()))))throw invalid();
   var originals=new HashMap<String,MemberUniverse.Member>();universe.members().forEach(m->originals.put(m.itemId(),m));
   var decisions=new HashMap<String,JsonNode>();for(var item:handoff.get("payload").get("items"))decisions.put(text(item,"item_id"),item);
   var facts=handoff.get("members");if(!facts.isArray()||facts.size()!=chunk.itemIds().size())throw invalid();var sorted=new TreeMap<String,Fact>();
   for(var n:facts){
    WireValues.object(n,FACT,FACT);String id=WireValues.id(n.get("itemId"),false);var original=originals.get(id);var binding=bindings.get(id);var decision=decisions.get(id);
    if(original==null||binding==null||decision==null||!"accepted".equals(original.terminalState())||!universe.runId().equals(text(n,"runId"))||!universe.sourceId().equals(text(n,"sourceId"))
       ||!Objects.equals(original.sourceItemId(),text(n,"sourceItemId"))||!category.equals(text(n,"category"))||!"production".equals(text(n,"activationStage"))
       ||!plan.scope().businessDate().equals(WireValues.businessDate(n.get("businessDate")))||plan.generation()!=number(n,"generation")
       ||!n.get("targetBusinessDate").isNull()&&!plan.scope().businessDate().equals(WireValues.businessDate(n.get("targetBusinessDate")))
       ||binding.expectedRevision()>CanonicalJson.MAX_SAFE_INTEGER-2||number(n,"revision")!=binding.expectedRevision()+2)throw invalid();
    String entity=n.get("entityId").isNull()?null:WireValues.id(n.get("entityId"),false);boolean review="needs_review".equals(text(decision,"decision"));
    if(!Objects.equals(text(n,"state"),review?"needs_review":"ready")||review!=(entity==null)
       ||"existing".equals(text(decision,"decision"))&&!Objects.equals(entity,text(decision,"candidate_entity_id")))throw invalid();
    var fact=new Fact(id,universe.runId(),universe.sourceId(),original.sourceItemId(),category,entity,text(n,"state"),number(n,"revision"),plan.scope().businessDate(),n.get("targetBusinessDate").isNull()?null:WireValues.businessDate(n.get("targetBusinessDate")),plan.generation(),"production");
    if(sorted.putIfAbsent(id,fact)!=null)throw invalid();
   }
   if(!new ArrayList<>(sorted.keySet()).equals(chunk.itemIds()))throw invalid();
   return new SourceDedupFragmentProof(rootId,plan.generation(),chunk.stagePart(),result,new ArrayList<>(sorted.values()),CanonicalJson.hash(CanonicalJson.Domain.ACTION,handoff));
  }catch(RuntimeException rejected){throw invalid();}
 }
 private static String category(GenerationFlowService.Stage stage){
  if(stage==null)throw invalid();return switch(stage){
   case NEWS_DEDUP->"news_tech";case AGENT_DEDUP->"agent";case SKILL_DEDUP->"skill";
   case WORKFLOW_DEDUP->"workflow";case MODEL_DEDUP->"model";default->throw invalid();
  };
 }
 private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
 private static long number(JsonNode n,String field){return WireValues.nonnegativeInteger(n.get(field));}
 private static IllegalStateException invalid(){return new IllegalStateException("original source dedup fragment unavailable");}
 @Override public String toString(){return "SourceDedupFragmentProof[redacted]";}
}
