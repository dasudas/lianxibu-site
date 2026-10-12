package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class CatalogSourceFinalResultTest {
 record Fixture(CatalogSourcePlanBuilderTest source,SourcePartitionPlanner.Plan plan,SourceDedupStageResult whole) { }
 Fixture fixture(String category,int count,String prose,List<String> blocked){
  var fragments=new CatalogSourceDedupFragmentProofTest(){@Override ObjectNode payload(SourcePartitionPlanner.Chunk chunk,String value){var payload=super.payload(chunk,value);payload.put("summary",prose.isBlank()?prose:prose+" tail-"+chunk.stagePart());return payload;}};
  var source=fragments.fixture(category,count);source.settings=new FrozenGenerationSettings(1,List.of(),blocked);var plan=source.build();var proofs=plan.chunks().stream().map(chunk->fragments.read(source,plan,chunk,fragments.handoff(source,plan,chunk))).toList();
  return new Fixture(source,plan,SourceDedupStageResult.create(plan,source.original,FrozenSourceStagePlan.encode(plan,source.original).reference(),proofs));
 }
 CatalogSourceFinalResult result(Fixture fixture){return CatalogSourceFinalResult.create(fixture.plan(),fixture.source().original,fixture.whole(),fixture.source().collection);}
 @Test void fourCatalogsPreserveAll203Decisions102MembersAndBothFullSummaryTails(){
  for(String category:List.of("agent","skill","workflow","model")){
   var f=fixture(category,203,"Fictional complete catalog summary",List.of());var result=result(f);var body=CanonicalJson.parse(result.json());
   assertEquals(203,body.path("stage_result").path("decisions").size());assertEquals(102,body.path("applied_member_count").asInt());assertEquals(0,body.path("unapplied_member_count").asInt());assertEquals(2,body.path("fragment_count").asInt());assertEquals(100,body.path("stage_result").path("fragments").get(0).path("members").size());assertEquals(2,body.path("stage_result").path("fragments").get(1).path("members").size());
   assertTrue(result.summary().contains("tail-0"));assertTrue(result.summary().contains("tail-1"));assertEquals(f.whole().json(),new String(CanonicalJson.bytes(body.get("stage_result")),java.nio.charset.StandardCharsets.UTF_8));assertEquals(category,body.path("category").asText());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),result.hash());assertEquals(result.json(),result(f).json());assertEquals("partial",result.coverageState());assertTrue(result.missing().stream().anyMatch(m->m.reason().equals("collection_partial")));assertTrue(result.missing().stream().anyMatch(m->m.reason().equals("dedup_needs_review")));assertThrows(UnsupportedOperationException.class,()->result.missing().clear());assertFalse(result.toString().contains("Fictional"));
  }
 }
 @Test void genuineZeroPlanIsNotAllFailedAndHasNoSyntheticModelSummary(){
  for(String category:List.of("agent","skill","workflow","model")){var f=fixture(category,0,"",List.of());var result=result(f);assertEquals("complete",result.coverageState());assertTrue(result.missing().isEmpty());assertTrue(result.summary().contains("没有本次待去重输入"));assertFalse(result.summary().contains("尚未获得已应用"));assertEquals(0,CanonicalJson.parse(result.json()).path("stage_result").path("fragments").size());}
 }
 @Test void noEligibleOriginalMembersRetainCollectorDecisionsWithoutFabricatedResult(){
  var source=new CatalogSourceDedupFragmentProofTest().fixture("skill",5);var old=source.selected;source.selected=new com.lianxibu.site.service.articles.CatalogDedupEligibilityService.Selection(old.runId(),old.sourceId(),old.category(),old.day(),old.cutoff(),old.entries().stream().map(e->e.reason()==com.lianxibu.site.service.articles.CatalogDedupEligibilityService.Reason.ELIGIBLE?new com.lianxibu.site.service.articles.CatalogDedupEligibilityService.Entry(e.itemId(),com.lianxibu.site.service.articles.CatalogDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null):e).toList());
  var plan=source.build();var whole=SourceDedupStageResult.create(plan,source.original,FrozenSourceStagePlan.encode(plan,source.original).reference(),List.of());var result=CatalogSourceFinalResult.create(plan,source.original,whole,source.collection);assertEquals(5,CanonicalJson.parse(result.json()).path("stage_result").path("decisions").size());assertEquals(0,CanonicalJson.parse(result.json()).path("eligible_count").asInt());assertTrue(result.summary().contains("没有本次待去重输入"));assertEquals("partial",result.coverageState());
 }
 @Test void missingAndPreferenceBlockedProseRemainExplicitWithoutDroppingOriginalStructure(){
  var blank=fixture("workflow",5,"",List.of());assertTrue(result(blank).missing().stream().anyMatch(m->m.reason().equals("summary_missing")));
  var blocked=fixture("model",5,"ＦＯＲＢＩＤＤＥＮ fictional complete summary",List.of("forbidden"));var result=result(blocked);assertTrue(result.missing().stream().anyMatch(m->m.reason().equals("preference_blocked_summary")));assertFalse(result.summary().contains("ＦＯＲＢＩＤＤＥＮ"));assertTrue(result.summary().contains("未获得可用文字简报"));assertEquals(blocked.whole().hash(),CanonicalJson.parse(result.json()).path("whole_result_hash").asText());assertEquals(3,CanonicalJson.parse(result.json()).path("stage_result").path("fragments").get(0).path("members").size());
 }
 @Test void foreignScopeCategoryOwnerAndProductionCollectionCannotGrantFinalValue(){
  var f=fixture("agent",5,"Fictional complete summary",List.of());var original=f.source().collection;var facts=original.facts();
  var other=fixture("skill",5,"Fictional complete summary",List.of());assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(f.plan(),f.source().original,f.whole(),other.source().collection));
  var foreign=new CollectionHandoffProofService.Proof("fictional-other",original.dispositionId(),original.payloadHash(),facts);assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(f.plan(),f.source().original,f.whole(),foreign));
  for(var proof:List.of(new CollectionHandoffProofService.Proof(original.intentId(),"fictional-other-origin",original.payloadHash(),facts),new CollectionHandoffProofService.Proof(original.intentId(),original.dispositionId(),"b".repeat(64),facts)))assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(f.plan(),f.source().original,f.whole(),proof));
  var trial=new CollectionHandoffProofService.Proof(original.intentId(),original.dispositionId(),original.payloadHash(),new com.lianxibu.site.service.sources.CollectionHandoffService.Facts(facts.runId(),facts.sourceId(),"trial",facts.businessDate(),facts.generation(),facts.category(),"trial",facts.configVersion(),facts.configHash(),facts.manifestHash(),facts.deliveryState(),facts.fetchState(),facts.expected(),facts.accepted(),facts.excluded()));assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(f.plan(),f.source().original,f.whole(),trial));
  assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.createPartial(f.plan(),f.source().original,f.whole(),original));var changedScope=new SourcePartitionPlanner.Scope(f.plan().scope().businessDate(),f.plan().scope().cutoff(),new FrozenGenerationSettings(2,List.of(),List.of()));var changed=new SourcePartitionPlanner.Plan(f.plan().stage(),f.plan().logicalKey(),f.plan().generation(),f.plan().reference(),f.plan().decisions(),f.plan().chunks(),changedScope);assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(changed,f.source().original,f.whole(),original));
 }
 @Test void originalNewsFinalCannotUseCatalogPolicyAndCapacityCannotReturnTruncatedOutput(){
  var old=new SourceDedupStageResultTest();var p=old.f.plan(old.f.universe);var whole=SourceDedupStageResult.create(p,old.f.universe,old.ref(p,old.f.universe),old.proofs(p,old.f.universe));var f=fixture("agent",5,"Fictional full summary",List.of());assertThrows(RuntimeException.class,()->CatalogSourceFinalResult.create(p,old.f.universe,whole,f.source().collection));assertThrows(GenerationFlowService.CapacityGuarded.class,()->CatalogSourceFinalResult.createWithinCapacity(f.plan(),f.source().original,f.whole(),f.source().collection,2048));assertNotNull(result(f));
 }
}
