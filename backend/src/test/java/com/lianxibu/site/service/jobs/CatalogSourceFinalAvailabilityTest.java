package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

/** Actual committed finalizer and permanent first observation; retained fragment is fictional. */
class CatalogSourceFinalAvailabilityTest {
 CatalogSourceFinalizationTest x;CatalogSourceFinalProofTest f;JdbcCatalogSourceFinalAvailability store;CatalogSourceFinalAvailabilityService service;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category,boolean empty)throws Exception{x=new CatalogSourceFinalizationTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceFinalAvailabilityTest.this.database();}};x.install(category,empty);f=x.f;store=new JdbcCatalogSourceFinalAvailability(f.f.source);service=observer(f.f.clock,f.f.writes);}
 @BeforeEach void setup()throws Exception{install("agent",false);}
 CatalogSourceFinalAvailabilityService observer(Clock clock,SharedWriteExecutor writes){return new CatalogSourceFinalAvailabilityService(x.service,store,writes,new DataSourceTransactionManager(f.f.source),clock);}
 int observations(){return f.f.count("target_type='catalog_final_availability'");}
 Instant available(CatalogSourceFinalizationService.Committed proof,Instant cutoff){return f.results.readonly(()->service.availableAt(proof,cutoff));}
 @Test void fourActualFinalReceiptsRequiredBeforeFirstObservationAndFullCutoffBoundary()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,false);assertNull(service.capture(f.ref));f.complete();x.archives.stage(f.ref);assertNull(service.capture(f.ref));assertEquals(0,observations());x.service.finish(f.ref);var proof=x.read();assertNull(available(proof,f.f.clock.instant().plusSeconds(100)));Instant observed=observer(Clock.fixed(f.f.clock.instant().plusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref);assertEquals(f.f.clock.instant().plusNanos(1000),observed);assertNull(available(proof,f.f.clock.instant()));assertEquals(observed,available(proof,observed));assertEquals(observed,observer(Clock.fixed(observed.plusSeconds(100),ZoneOffset.UTC),f.f.writes).capture(f.ref));assertEquals(1,observations());assertEquals(1,x.receipts());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}
 }
 @Test void actualZeroNeedsOriginalFinalCommitAndNeverCreatesAnAiRoot()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,true);assertNull(service.capture(f.ref));x.service.finish(f.ref);Instant observed=service.capture(f.ref);assertEquals(observed,available(x.read(),observed));assertEquals(1,observations());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals("no_input",x.intentState());}
 }
 @Test void failedBudgetPartialNeedsActualDurableSealAndFinalCommit(){
  f.f.jdbc.update("UPDATE jobs SET state='failed',call_count=3 WHERE id=?",f.results.chunk.rootId());assertNull(service.capture(f.ref));assertNotNull(f.partialArchive.stage(f.ref));assertNotNull(f.seals.seal(f.ref));assertNull(service.capture(f.ref));x.service.finish(f.ref);assertEquals("partial",x.sourceText("analysis_state"));assertNotNull(service.capture(f.ref));assertEquals(1,observations());assertEquals(3,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void badRoleRehashedEarlierTimeAndForeignCategoryStageCannotGrantAvailability(){
  f.complete();x.service.finish(f.ref);service.capture(f.ref);var proof=x.read();String id=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='catalog_final_availability'",String.class);String saved=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);f.f.jdbc.update("UPDATE jobs SET operator_id='catalog-source-structural-observer' WHERE id=?",id);assertThrows(RuntimeException.class,()->available(proof,f.f.clock.instant()));f.f.jdbc.update("UPDATE jobs SET operator_id='catalog-source-final-observer' WHERE id=?",id);var payload=(ObjectNode)CanonicalJson.parse(saved);Instant early=f.f.clock.instant().minusSeconds(1);payload.put("observed_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(early));String raw=JdbcFrozenInputs.canonical(payload);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,acted_at=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),java.sql.Timestamp.from(early),id);assertThrows(RuntimeException.class,()->available(proof,f.f.clock.instant()));assertThrows(RuntimeException.class,()->service.capture(f.ref));
  for(int n=0;n<4;n++){var key=(ObjectNode)CanonicalJson.parse(proof.observationKeyJson());switch(n){case 0->key.put("category","skill");case 1->key.put("schema_version","source-dedup-stage-key-v1");case 2->key.put("stage","news_value");case 3->key.put("category","AGENT");}String bad=JdbcFrozenInputs.canonical(key);assertThrows(RuntimeException.class,()->f.results.readonly(()->store.read(bad)));}assertThrows(RuntimeException.class,()->available(proof,f.f.clock.instant().plusNanos(1)));
 }
 @Test void actualAppendRollbackAndAfterCommitAckLossPreserveOneObservation(){
  f.complete();x.service.finish(f.ref);f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_observation_failure CHECK(target_type IS NULL OR target_type<>'catalog_final_availability')");assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(0,observations());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_catalog_observation_failure":"ALTER TABLE jobs DROP CHECK fictional_catalog_observation_failure");var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional catalog final observation ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});Instant observed=observer(f.f.clock,writes).capture(f.ref);assertEquals(observed,service.capture(f.ref));assertEquals(1,observations());assertEquals(1,x.receipts());
 }
 @Test void observerClockRunsOutsideTransactionsAndCannotPrecedeActualOriginalCommit(){
  f.complete();x.service.finish(f.ref);Clock guarded=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return f.f.clock.instant();}};assertThrows(RuntimeException.class,()->f.prepared.tx(()->observer(guarded,f.f.writes).capture(f.ref)));assertThrows(RuntimeException.class,()->observer(Clock.fixed(f.f.clock.instant().minusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref));assertEquals(0,observations());assertNotNull(observer(guarded,f.f.writes).capture(f.ref));
 }
}
