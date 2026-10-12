package com.lianxibu.site.service.jobs;
import com.lianxibu.site.service.articles.CatalogDedupEligibilityService;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class CatalogSourceDedupStageResultTest {
 final CatalogSourceDedupFragmentProofTest f=new CatalogSourceDedupFragmentProofTest();
 List<SourceDedupFragmentProof> proofs(CatalogSourcePlanBuilderTest c,SourcePartitionPlanner.Plan p){return p.chunks().stream().map(k->f.read(c,p,k,f.handoff(c,p,k))).toList();}
 FrozenSourceStagePlan.Reference ref(CatalogSourcePlanBuilderTest c,SourcePartitionPlanner.Plan p){return FrozenSourceStagePlan.encode(p,c.original).reference();}
 SourceDedupStageResult result(CatalogSourcePlanBuilderTest c,SourcePartitionPlanner.Plan p){return SourceDedupStageResult.create(p,c.original,ref(c,p),proofs(c,p));}
 @Test void allFourComplete203MemberPlansKeepEveryDecisionAndOriginalFragment(){
  for(String category:List.of("agent","skill","workflow","model")){
   var c=f.fixture(category,203);var p=c.build();var original=result(c,p);var body=CanonicalJson.parse(original.json());
   assertEquals(203,original.decisions().size());assertEquals(102,original.members().size());assertEquals(2,body.path("stage_part_count").asInt());assertEquals(2,body.path("fragments").get(1).path("members").size());
   assertTrue(original.members().stream().allMatch(m->category.equals(m.category())));assertEquals(category+"_dedup",CanonicalJson.parse(original.observationKeyJson()).path("stage").asText());
   assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),original.hash());var reverse=new ArrayList<>(proofs(c,p));Collections.reverse(reverse);assertEquals(original.json(),SourceDedupStageResult.create(p,c.original,ref(c,p),reverse).json());
   assertThrows(UnsupportedOperationException.class,()->original.members().clear());assertFalse(original.toString().contains("Fictional"));
  }
 }
 @Test void missingDuplicateOtherOriginalPartsAndForeignReferencesNeverProduceWholeOutput(){
  var c=f.fixture("skill",203);var p=c.build();var facts=proofs(c,p);var reference=ref(c,p);
  for(var bad:List.of(facts.subList(0,1),List.of(facts.getFirst(),facts.getFirst()),List.of(facts.getFirst(),facts.getLast(),facts.getFirst())))assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(p,c.original,reference,bad));
  var changed=f.fixture("skill",203);changed.settings=new FrozenGenerationSettings(2,List.of("Go"),List.of("Fictional"));var other=changed.build();assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(other,changed.original,reference,proofs(changed,other)));
  var foreign=new FrozenSourceStagePlan.Reference(reference.ownerId(),"b".repeat(64),reference.indexHash());assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(p,c.original,foreign,facts));
 }
 @Test void emptyAndZeroEligibleKeepActualFullScopeWithoutSyntheticFragments(){
  for(String category:List.of("agent","skill","workflow","model"))for(int count:List.of(0,5)){
   var c=f.fixture(category,count);var old=c.selected;c.selected=new CatalogDedupEligibilityService.Selection(old.runId(),old.sourceId(),category,old.day(),old.cutoff(),old.entries().stream().map(e->e.reason()==CatalogDedupEligibilityService.Reason.ELIGIBLE?new CatalogDedupEligibilityService.Entry(e.itemId(),CatalogDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null):e).toList());
   var p=c.build();var r=result(c,p);assertEquals(count,r.decisions().size());assertTrue(r.members().isEmpty());assertTrue(p.chunks().isEmpty());assertEquals(0,CanonicalJson.parse(r.json()).path("eligible_count").asInt());assertEquals(category+"_dedup",CanonicalJson.parse(r.observationKeyJson()).path("stage").asText());
  }
 }
 @Test void nonDedupStagesAndActualCanonicalCapacityCannotGrantWholeProof(){
  var c=f.fixture("workflow",5);var p=c.build();var reference=ref(c,p);var facts=proofs(c,p);
  var wrong=new SourcePartitionPlanner.Plan(GenerationFlowService.Stage.NEWS_VALUE,p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),p.scope());assertThrows(RuntimeException.class,()->SourceDedupStageResult.create(wrong,c.original,reference,facts));
  assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupStageResult.createWithinCapacity(p,c.original,reference,facts,2048));assertNotNull(result(c,p));
 }

 @Test void originalP1WholeBytesRemainExact(){
  var old=new SourceDedupStageResultTest();var p=old.f.plan(old.f.universe);var actual=SourceDedupStageResult.create(p,old.f.universe,old.ref(p,old.f.universe),old.proofs(p,old.f.universe));
  assertEquals("960eee94443e6a20e2bd1bc61032711fb9983402a941b6f914e456710a5afad0",actual.hash());assertEquals("5eb70c2c892de58de6b08a26d692d53b24b673d96c997e10120d02b7a733c7a2",sha(actual.json()));assertEquals("1a0cf190884936eb2e623d82317ac811bf1f236cc8639b3d89f905d83f2c9393",sha(actual.observationKeyJson()));
 }
 static String sha(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
}
