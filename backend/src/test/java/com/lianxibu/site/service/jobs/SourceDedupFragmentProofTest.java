package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupFragmentProofTest {
 final MemberUniverse.Universe universe=FrozenSourceUniverseTest.universe(5,false);
 SourcePartitionPlanner.Plan plan(MemberUniverse.Universe original){
  var ref=FrozenSourceUniverse.encode(com.lianxibu.site.repository.jobs.JdbcFrozenInputs.stableId("intent",SourcePartitionPlannerTest.KEY+":1"),original).reference();
  return new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,SourcePartitionPlannerTest.KEY,1,original,ref,SourcePartitionPlannerTest.decisions(original),(ids,part,root)->{
   var n=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,part,root,"Fictional original input"));
   for(var c:n.get(FrozenApplicationContext.FIELD).get("candidates_by_item"))((ObjectNode)c).putArray("entity_ids").add("fictional-existing-entity");
   return canonical(n);
  });
 }
 ObjectNode payload(SourcePartitionPlanner.Chunk c){
  var p=JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","Fictional fragment summary");var items=p.putArray("items");int index=0;
  for(String id:c.itemIds()){
   String decision=List.of("new","existing","needs_review").get(index++%3);var item=items.addObject().put("item_id",id).put("category","news_tech").put("decision",decision);
   if(decision.equals("existing"))item.put("candidate_entity_id","fictional-existing-entity");else item.putNull("candidate_entity_id");
   item.put("reason","Fictional evidence").put("confidence","1").put("suggested_title","Fictional title").putNull("suggested_summary");item.put("reanalyze_recommended",false);
  }return p;
 }
 ResultApplicationService.Input input(SourcePartitionPlanner.Chunk c){return FrozenApplicationContext.fromJson(CanonicalJson.parse(c.snapshotJson()).get(FrozenApplicationContext.FIELD)).input(c.rootId(),"fictional-proof",c.inputHash(),0);}
 String envelope(SourcePartitionPlanner.Chunk c){return ResultApplicationService.providerEnvelope(input(c),canonical(payload(c)));}
 ObjectNode handoff(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u,SourcePartitionPlanner.Chunk c){
  var result=ResultApplicationService.validate(input(c),envelope(c));var n=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",c.rootId()).put("input_hash",c.inputHash()).put("result_hash",result.resultHash()).put("stage","news_dedup").put("generation",p.generation()).put("stage_part",c.stagePart());n.set("payload",CanonicalJson.parse(result.payloadJson()));var facts=n.putArray("members");int i=0;
  for(String id:c.itemIds()){
   var original=u.members().stream().filter(m->m.itemId().equals(id)).findFirst().orElseThrow();boolean review=i++%3==2;
   var fact=new com.lianxibu.site.service.sources.SourceMemberStageService.Fact(id,u.runId(),u.sourceId(),original.sourceItemId(),"news_tech",review?null:"fictional-existing-entity",review?"needs_review":"ready",3,p.scope().businessDate(),null,p.generation(),"production");facts.add(JsonMapper.shared().valueToTree(fact));
  }return n;
 }
 SourceDedupFragmentProof read(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u,SourcePartitionPlanner.Chunk c,ObjectNode h){return SourceDedupFragmentProof.read(p,u,c.rootId(),envelope(c),canonical(h));}
 @Test void allOriginalDecisionsAndFactsBindWithoutCallingANewSuggestionCreated(){
  var p=plan(universe);var c=p.chunks().getFirst();var h=handoff(p,universe,c);var proof=read(p,universe,c,h);
  assertEquals(c.rootId(),proof.rootId());assertEquals(c.inputHash(),proof.result().inputHash());assertEquals(0,proof.stagePart());assertEquals(1,proof.generation());
  assertEquals(c.itemIds(),proof.members().stream().map(f->f.itemId()).toList());assertEquals("fictional-existing-entity",proof.members().getFirst().entityId());assertEquals("needs_review",proof.members().getLast().state());
  assertEquals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,h),proof.handoffHash());assertThrows(UnsupportedOperationException.class,()->proof.members().clear());assertFalse(proof.toString().contains("Fictional fragment summary"));
 }
 @Test void originalFactOrderIsNormalizedWhileTheActualHandoffHashRemainsBound(){
  var p=plan(universe);var c=p.chunks().getFirst();var h=handoff(p,universe,c);var before=read(p,universe,c,h);var rows=new ArrayList<JsonNode>();h.get("members").forEach(rows::add);Collections.reverse(rows);var array=h.putArray("members");rows.forEach(array::add);var after=read(p,universe,c,h);
  assertEquals(before.members(),after.members());assertEquals(before.result(),after.result());assertNotEquals(before.handoffHash(),after.handoffHash());
 }
 @Test void handoffIdentityAndClosedFieldsCannotReplaceTheOriginalResult(){
  var p=plan(universe);var c=p.chunks().getFirst();
  for(String mode:List.of("root_id","input_hash","result_hash","stage","generation","stage_part","payload","schema_version","extra","missing","oversize")){
   var h=handoff(p,universe,c);switch(mode){case "generation","stage_part"->h.put(mode,2);case "payload"->((ObjectNode)h.get(mode)).put("summary","Fictional altered");case "extra"->h.put("extra",true);case "missing"->h.remove("members");case "oversize"->((ObjectNode)h.get("payload")).put("summary","F".repeat(262145));default->h.put(mode,"fictional-wrong");}
   assertThrows(RuntimeException.class,()->read(p,universe,c,h),mode);
  }
  String invalid=envelope(c).replace("Fictional fragment summary","Fictional changed result");assertThrows(RuntimeException.class,()->SourceDedupFragmentProof.read(p,universe,c.rootId(),invalid,canonical(handoff(p,universe,c))));
 }
 @Test void everyOriginalFactFieldIsCheckedAndCannotGrantTrialOrOtherScope(){
  var p=plan(universe);var c=p.chunks().getFirst();
  for(String mode:List.of("itemId","runId","sourceId","sourceItemId","category","entityId","state","revision","businessDate","targetBusinessDate","generation","activationStage","extra","missing","unsafe")){
   var h=handoff(p,universe,c);var f=(ObjectNode)h.get("members").get(0);switch(mode){case "revision"->f.put(mode,4);case "generation"->f.put(mode,2);case "unsafe"->f.put("revision",CanonicalJson.MAX_SAFE_INTEGER+1);case "businessDate","targetBusinessDate"->f.put(mode,"2026-01-02");case "entityId"->f.putNull(mode);case "extra"->f.put("extra",1);case "missing"->f.remove("sourceItemId");default->f.put(mode,"fictional-wrong");}
   assertThrows(RuntimeException.class,()->read(p,universe,c,h),mode);
  }
  var h=handoff(p,universe,c);((ObjectNode)h.get("members").get(1)).put("entityId","fictional-other-entity");assertThrows(RuntimeException.class,()->read(p,universe,c,h));
  var review=handoff(p,universe,c);((ObjectNode)review.get("members").get(2)).put("state","ready");assertThrows(RuntimeException.class,()->read(p,universe,c,review));
 }
 @Test void completeMemberSetAndDistinctOriginalPartsAreMandatory(){
  var p=plan(universe);var c=p.chunks().getFirst();for(String mode:List.of("missing","duplicate","extra")){
   var h=handoff(p,universe,c);var rows=(tools.jackson.databind.node.ArrayNode)h.get("members");if(mode.equals("missing"))rows.remove(rows.size()-1);else if(mode.equals("duplicate"))rows.set(1,rows.get(0).deepCopy());else rows.add(rows.get(0).deepCopy());assertThrows(RuntimeException.class,()->read(p,universe,c,h));
  }
  var u=FrozenSourceUniverseTest.universe(203,false);var many=plan(u);assertEquals(2,many.chunks().size());var first=many.chunks().getFirst();var second=many.chunks().getLast();assertEquals(100,read(many,u,first,handoff(many,u,first)).members().size());assertEquals(2,read(many,u,second,handoff(many,u,second)).members().size());
  assertThrows(RuntimeException.class,()->SourceDedupFragmentProof.read(many,u,second.rootId(),envelope(first),canonical(handoff(many,u,first))));
  var empty=FrozenSourceUniverseTest.universe(0,false);assertThrows(RuntimeException.class,()->SourceDedupFragmentProof.read(plan(empty),empty,first.rootId(),envelope(first),canonical(handoff(many,u,first))));
 }
 static String canonical(JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}
}
