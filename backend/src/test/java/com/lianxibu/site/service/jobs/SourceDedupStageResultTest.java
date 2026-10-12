package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupStageResultTest {
 final SourceDedupFragmentProofTest f=new SourceDedupFragmentProofTest();
 List<SourceDedupFragmentProof> proofs(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u){return p.chunks().stream().map(c->f.read(p,u,c,f.handoff(p,u,c))).toList();}
 FrozenSourceStagePlan.Reference ref(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u){return FrozenSourceStagePlan.encode(p,u).reference();}
 @Test void allOriginalPartsDecisionsResultsAndFactsFormOneStableCompleteProof(){
  var u=FrozenSourceUniverseTest.universe(203,false);var p=f.plan(u);var proofs=new ArrayList<>(proofs(p,u));var result=SourceDedupStageResult.create(p,u,ref(p,u),proofs);
  assertEquals(102,result.members().size());assertEquals(203,result.decisions().size());assertEquals(ref(p,u),result.planRef());var json=CanonicalJson.parse(result.json());
  assertEquals(102,json.get("eligible_count").asInt());assertEquals(101,json.get("excluded").asInt());assertEquals(2,json.get("fragments").size());assertEquals(2,json.get("fragments").get(1).get("members").size());
  assertEquals("Fictional fragment summary",json.get("fragments").get(0).get("payload").get("summary").asText());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json),result.hash());
  Collections.reverse(proofs);assertEquals(result.json(),SourceDedupStageResult.create(p,u,ref(p,u),proofs).json());assertThrows(UnsupportedOperationException.class,()->result.members().clear());assertFalse(result.toString().contains("Fictional fragment summary"));
 }
 @Test void noEligibleAndEmptyUniverseKeepFullDecisionsAndScopeWithoutFakeAiFragments(){
  for(int n:List.of(0,5)){
   var u=FrozenSourceUniverseTest.universe(n,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,u).reference();var decisions=u.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),"excluded".equals(m.terminalState())?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList();
   var p=new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,SourcePartitionPlannerTest.KEY,1,u,ref,decisions,FrozenSourceStagePlanTest.scope(),(ids,part,root)->{throw new AssertionError();});var result=SourceDedupStageResult.create(p,u,ref(p,u),List.of());
   assertEquals(n,result.decisions().size());assertTrue(result.members().isEmpty());assertEquals(0,CanonicalJson.parse(result.json()).get("stage_part_count").asInt());assertEquals("2026-01-01",CanonicalJson.parse(result.json()).get("business_date").asText());
  }
 }
 @Test void missingExtraDuplicateAndDifferentOriginalInputCannotCountAsFullCoverage(){
  var u=FrozenSourceUniverseTest.universe(203,false);var p=f.plan(u);var original=proofs(p,u);var reference=ref(p,u);
  for(var bad:List.of(original.subList(0,1),List.of(original.getFirst(),original.getFirst()),List.of(original.getFirst(),original.getLast(),original.getFirst())))assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(p,u,reference,bad));
  var changed=p.chunks().stream().map(c->{var node=(ObjectNode)CanonicalJson.parse(c.snapshotJson());for(var m:node.get("members")){var body=(ObjectNode)m.get("body");body.put("text","Fictional altered input");((ObjectNode)m).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));}String snapshot=JdbcText.canonical(node),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,node);return new SourcePartitionPlanner.Chunk(c.stagePart(),c.rootId(),c.itemIds(),snapshot,hash,new GenerationRequestBuilder().build(p.stage(),snapshot,hash));}).toList();
  var modified=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),changed,p.scope());
  assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(p,u,reference,proofs(modified,u)));
 }
 @Test void anExternalWholePlanReferenceAndOriginalStageScopeAreRequired(){
  var p=f.plan(f.universe);var ref=ref(p,f.universe);var wrong=new FrozenSourceStagePlan.Reference(ref.ownerId(),"b".repeat(64),ref.indexHash());
  assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(p,f.universe,wrong,proofs(p,f.universe)));
  var noScope=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),null);assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(noScope,f.universe,ref,proofs(p,f.universe)));
  var wrongStage=new SourcePartitionPlanner.Plan(GenerationFlowService.Stage.NEWS_VALUE,p.logicalKey(),1,p.reference(),p.decisions(),p.chunks(),p.scope());assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(wrongStage,f.universe,ref,proofs(p,f.universe)));
 }
 @Test void actualCanonicalCapacityFailureReturnsNoPartialResult(){
  var p=f.plan(f.universe);assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupStageResult.createWithinCapacity(p,f.universe,ref(p,f.universe),proofs(p,f.universe),2048));
  assertNotNull(SourceDedupStageResult.create(p,f.universe,ref(p,f.universe),proofs(p,f.universe)));
 }
 private static final class JdbcText{static String canonical(tools.jackson.databind.JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}}
}
