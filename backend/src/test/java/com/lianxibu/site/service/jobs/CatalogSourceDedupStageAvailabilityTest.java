package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class CatalogSourceDedupStageAvailabilityTest {
 CatalogSourceDedupStageProofTest f;SourceDedupStageAvailabilityService service;JdbcSourceStageAvailability storage;Instant first;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){install("agent",203);}
 void install(String category,int count){f=new CatalogSourceDedupStageProofTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceDedupStageAvailabilityTest.this.database();}};f.install(category,count);f.f.f.jdbc.execute(f.f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs ALTER COLUMN acted_at TIMESTAMP(6)":"ALTER TABLE jobs MODIFY acted_at TIMESTAMP(6) NULL");storage=new JdbcSourceStageAvailability(f.f.f.source);first=f.f.f.clock.instant().plusNanos(1000);service=provider(f.service,f.f.f.clock.instant().plusNanos(1));}
 SourceDedupStageAvailabilityService provider(SourceDedupStageProofService proofs,Instant now){return new SourceDedupStageAvailabilityService(proofs,storage,f.f.f.writes,new DataSourceTransactionManager(f.f.f.source),Clock.fixed(now,ZoneOffset.UTC));}
 @Test void everyOriginalCatalogPartMustCommitBeforeActualFirstObservationAndCeilingFloor(){
  assertNull(service.capture(f.ref));f.complete(0);assertNull(service.capture(f.ref));f.complete(1);int calls=f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var proof=f.read().proof();assertEquals(first,service.capture(f.ref));int rows=f.f.f.count("1=1");assertEquals(first,provider(f.service,first.plusSeconds(3600)).capture(f.ref));assertEquals(rows,f.f.f.count("1=1"));assertEquals(calls,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertNull(f.f.readonly(()->service.availableAt(proof,first.minusNanos(1000))));assertEquals(first,f.f.readonly(()->service.availableAt(proof,first)));assertEquals(1,f.f.f.count("target_type='source_stage_availability'"));
 }
 @Test void allFourOriginalStageKeysAndZeroPlansAreObservedWithoutSyntheticAiRoots(){
  for(String category:java.util.List.of("agent","skill","workflow","model"))for(int count:java.util.List.of(0,5)){install(category,count);if(count>0)f.complete(0);var proof=f.read().proof();assertEquals(category+"_dedup",CanonicalJson.parse(proof.observationKeyJson()).path("stage").asText());assertEquals(first,service.capture(f.ref));assertEquals(first,f.f.readonly(()->service.availableAt(proof,first)));if(count==0)assertEquals(0,f.f.f.count("record_kind IN ('logical_task','ai_attempt')"));}
 }
 @Test void laterCurrentIntentAndRestartKeepExactOriginalFirstObservation(){
  f.complete(0);f.complete(1);var original=f.read().proof();assertEquals(first,service.capture(f.ref));f.f.tx(()->new JdbcJobRecords(f.f.f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",f.f.plan.logicalKey()+":2"),f.f.plan.logicalKey(),2,"agent_dedup",f.f.plan.scope().businessDate()),f.f.f.clock.instant()));service=provider(f.service,first.plusSeconds(3600));assertEquals(first,service.capture(f.ref));assertEquals(first,f.f.readonly(()->service.availableAt(original,first.plusSeconds(3600))));assertEquals(1,f.f.f.count("target_type='source_stage_availability'"));
 }
 @Test void brokenActualFragmentOrObservationCannotGrantPreviouslyStoredAvailability(){
  f.complete(0);f.complete(1);var original=f.read().proof();service.capture(f.ref);f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.f.receipt());assertThrows(RuntimeException.class,()->f.f.readonly(()->service.availableAt(original,first)));assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(1,f.f.f.count("target_type='source_stage_availability'"));
 }
 @Test void closedNativeKeyOtherStagesHashesAndWrongTransactionsCannotGrantObservation(){
  f.complete(0);f.complete(1);var proof=f.read().proof();for(String stage:java.util.List.of("news_value","agent_analysis","report","fictional")){var n=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(proof.observationKeyJson());n.put("stage",stage);String raw=JdbcFrozenInputs.canonical(n);assertThrows(RuntimeException.class,()->JdbcSourceStageAvailability.key(raw));}
  assertThrows(RuntimeException.class,()->f.f.tx(()->service.capture(f.ref)));assertThrows(RuntimeException.class,()->service.availableAt(proof,first));assertThrows(RuntimeException.class,()->f.f.readonly(()->service.availableAt(proof,first.plusNanos(1))));assertThrows(RuntimeException.class,()->provider(f.service,f.f.f.clock.instant().minusNanos(1)).capture(f.ref));assertEquals(0,f.f.f.count("target_type='source_stage_availability'"));
 }
 @Test void sparseCatalogMustHaveItsActualDurableSealBeforeAnyObservation(){
  var partial=new CatalogSourceDedupPartialSealTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceDedupStageAvailabilityTest.this.database();}};partial.setup("workflow",5);partial.a.f.terminal(0,"failed");var fixture=partial.a.f.f;var proofs=new SourceDedupStageProofService(fixture.archives,fixture.universes,fixture.service,partial.service);var nativeStorage=new JdbcSourceStageAvailability(partial.a.db().source);var observer=new SourceDedupStageAvailabilityService(proofs,nativeStorage,partial.a.db().writes,new DataSourceTransactionManager(partial.a.db().source),partial.a.db().clock);assertNull(observer.capture(partial.a.f.ref));assertEquals(0,partial.a.db().count("target_type='source_stage_availability'"));var sealed=partial.service.seal(partial.a.f.ref);assertEquals(sealed.minimumObservationTime(),observer.capture(partial.a.f.ref));assertEquals(1,partial.a.db().count("target_type='source_stage_availability'"));assertEquals(sealed.minimumObservationTime(),fixture.readonly(()->observer.availableAt(sealed.proof(),sealed.minimumObservationTime())));
 }
}
