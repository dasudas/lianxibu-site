package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class CatalogSourceStageResolutionTest {
 CatalogSourceDedupStageProofTest whole;CatalogSourceDedupResultProofServiceTest f;SourceStagePreparationService parts;SourceStageResolutionService service;JdbcSourceStageRoots roots;FrozenSourceStagePlan.Reference ref;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){install("agent",203);}
 void install(String category,int count){
  whole=new CatalogSourceDedupStageProofTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceStageResolutionTest.this.database();}};whole.install(category,count);f=whole.f;ref=whole.ref;
  for(var chunk:f.plan.chunks()){f.f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND purpose='input'",chunk.rootId());f.f.jdbc.update("DELETE FROM jobs WHERE id=?",chunk.rootId());}
  parts=new SourceStagePreparationService(f.f.source,f.archives,f.universes,run->f.catalog.collection,f.f.clock,"fictional-owner");
  for(var chunk:f.plan.chunks()){var captured=parts.capturePart(ref.ownerId(),chunk.stagePart(),SourcePlanPreparationCoordinator.partAction(ref.ownerId(),chunk.stagePart()));f.tx(()->parts.prepareCaptured(captured));}
  roots=new JdbcSourceStageRoots(f.f.source);service=provider(roots);
 }
 SourceStageResolutionService provider(JdbcSourceStageRoots store){
  var results=new JdbcSourceDedupResults(f.f.source);var dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.f.source));
  return new SourceStageResolutionService(store,f.archives,f.universes,parts,new JdbcFrozenInputs(f.f.source),results,f.service,new SourceValueResultProofService(results,f.archives,f.universes,dependencies),dependencies);
 }
 SourceStageResolutionService.Snapshot read(){return f.readonly(()->service.read(ref));}
 void complete(int n){whole.complete(n);}
 void terminal(int n,String state){var root=f.plan.chunks().get(n).rootId();f.f.jdbc.update("UPDATE jobs SET state=?,call_count=? WHERE id=?",state,state.equals("failed")?3:0,root);}
 @Test void actualPreparedFourCatalogStagesRemainPendingAndUnknownDoesNotSettle(){
  for(String category:List.of("agent","skill","workflow","model")){
   install(category,5);int rows=f.f.count("1=1");String root=f.plan.chunks().getFirst().rootId();for(String state:List.of("queued","running","waiting_retry","cancel_requested","unknown_external","blocked_configuration")){
    f.f.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,root);var snapshot=read();assertFalse(snapshot.settled());assertFalse(snapshot.hasFailures());assertEquals(SourceStageResolutionService.State.PENDING,snapshot.fragments().getFirst().state());assertNull(snapshot.fragments().getFirst().dedup());assertNull(snapshot.fragments().getFirst().value());
   }assertEquals(rows,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
  }
 }
 @Test void allOriginal203MembersResolveCompleteOrExplicitSparseFailuresWithoutValueProof(){
  complete(0);assertFalse(read().settled());terminal(1,"failed");var snapshot=read();assertTrue(snapshot.settled());assertTrue(snapshot.hasFailures());assertNotNull(snapshot.fragments().getFirst().dedup());assertNull(snapshot.fragments().getFirst().value());assertEquals(SourceStageResolutionService.State.FAILED,snapshot.fragments().getLast().state());
  var result=SourceDedupStageResult.createResolved(snapshot);var body=CanonicalJson.parse(result.json());assertEquals(203,result.decisions().size());assertEquals(100,result.members().size());assertEquals("partial",body.path("coverage_state").asText());assertEquals(2,body.path("resolutions").size());assertEquals(2,body.path("unapplied_member_count").asInt());assertEquals("agent_dedup",CanonicalJson.parse(result.observationKeyJson()).path("stage").asText());assertEquals(4,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void originalTerminalStatesRequireCompletePreparationAndBudgetMarkers(){
  install("workflow",5);for(String state:List.of("failed","cancelled","discarded","superseded")){terminal(0,state);var snapshot=read();assertTrue(snapshot.settled());assertTrue(snapshot.hasFailures());assertEquals(state.toUpperCase(Locale.ROOT),snapshot.fragments().getFirst().state().name());}
  String root=f.plan.chunks().getFirst().rootId();f.f.jdbc.update("UPDATE jobs SET state='failed',call_count=2 WHERE id=?",root);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET call_count=3,application_state='APPLIED' WHERE id=?",root);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET state='queued',call_count=0,application_state=NULL WHERE id=?",root);f.f.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenInputs.stableId("prepare-action",SourcePlanPreparationCoordinator.partAction(ref.ownerId(),0)));assertThrows(RuntimeException.class,this::read);
 }
 @Test void appliedCatalogProofMustKeepRetainedOriginalHandoffAndExactRootType(){
  complete(0);complete(1);assertTrue(read().settled());assertFalse(read().hasFailures());assertTrue(read().fragments().stream().allMatch(k->k.dedup()!=null&&k.value()==null));assertEquals("source-dedup-stage-result-v1",CanonicalJson.parse(SourceDedupStageResult.createResolved(read()).json()).path("schema_version").asText());
  f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.receipt());assertThrows(RuntimeException.class,this::read);
 }
 @Test void changedNativeDuringActualReadRefusesResolutionAndReadonlyIdentityIsRequired(){
  install("skill",5);String root=f.plan.chunks().getFirst().rootId();var racing=spy(roots);var calls=new java.util.concurrent.atomic.AtomicInteger();
  doAnswer(call->{var original=call.callRealMethod();if(calls.incrementAndGet()==1)CompletableFuture.runAsync(()->f.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root)).get(5,TimeUnit.SECONDS);return original;}).when(racing).find(root);service=provider(racing);assertThrows(RuntimeException.class,this::read);service=provider(roots);
  assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->f.tx(()->service.read(ref)));assertThrows(RuntimeException.class,()->f.readonly(()->service.read(new FrozenSourceStagePlan.Reference(ref.ownerId(),"b".repeat(64),ref.indexHash()))));
 }
 @Test void zeroOriginalCatalogPlansSettleWithoutAnyModelOrObservation(){
  for(String category:List.of("agent","skill","workflow","model")){install(category,0);int rows=f.f.count("1=1");var result=read();assertTrue(result.settled());assertFalse(result.hasFailures());assertTrue(result.fragments().isEmpty());assertTrue(SourceDedupStageResult.createResolved(result).members().isEmpty());assertEquals(rows,f.f.count("1=1"));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(0,f.f.count("target_type='source_stage_availability'"));}
 }
}
