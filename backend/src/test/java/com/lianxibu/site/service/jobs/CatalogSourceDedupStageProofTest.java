package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CatalogSourceDedupStageProofTest {
 CatalogSourceDedupResultProofServiceTest f;SourceDedupStageProofService service;FrozenSourceStagePlan.Reference ref;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category,int count){
  f=new CatalogSourceDedupResultProofServiceTest(){
   @Override GenerationJdbcStoreTest fixture(){return CatalogSourceDedupStageProofTest.this.database();}
   @Override void setup(){
  f=fixture();f.clock=java.time.Clock.fixed(java.time.Instant.parse("2026-01-02T00:00:01Z"),java.time.ZoneOffset.UTC);f.fixture();boolean mysql=!(f.source instanceof org.h2.jdbcx.JdbcDataSource);String type=mysql?"JSON":"CLOB";
  if(!mysql)f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");
  f.jdbc.execute("ALTER TABLE jobs ADD result_hash BINARY(32)");f.jdbc.execute("ALTER TABLE jobs ADD result_json "+type);f.jdbc.execute("ALTER TABLE jobs ADD application_state VARCHAR(32)");
  f.jdbc.execute(mysql?"ALTER TABLE jobs MODIFY applied_at TIMESTAMP(6) NULL":"ALTER TABLE jobs ALTER COLUMN applied_at TIMESTAMP(6)");
  pure=new CatalogSourceDedupFragmentProofTest();catalog=pure.fixture(category,count);original=catalog.original;plan=catalog.build();if(plan.scope()==null)plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),plan.chunks(),FrozenSourceStagePlanTest.scope());chunk=plan.chunks().isEmpty()?null:plan.chunks().getFirst();
  var writerUniverses=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->original,f.clock);
  tx(()->{var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent(plan.reference().ownerId(),plan.logicalKey(),plan.generation(),plan.stage().name().toLowerCase(Locale.ROOT),plan.scope().businessDate()),f.clock.instant());
   assertEquals(plan.reference(),writerUniverses.freeze(plan.reference().ownerId(),original.runId()));new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),writerUniverses,f.clock,"fictional-owner").freeze(plan);
   for(var c:plan.chunks()){records.createRoot(new JdbcJobRecords.Root(c.rootId(),plan.reference().ownerId(),plan.logicalKey(),plan.generation(),c.stagePart(),plan.stage().name().toLowerCase(Locale.ROOT),plan.stage().priority()),f.clock.instant());new JdbcFrozenInputs(f.source).save(c.rootId(),c.snapshotJson(),c.inputHash(),f.clock.instant());}return true;});
  universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->{throw new AssertionError("must not read current source");},f.clock);
  archives=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");service=provider();
   }
  };f.category=category;f.setup();service=new SourceDedupStageProofService(f.archives,f.universes,f.service);ref=FrozenSourceStagePlan.encode(f.plan,f.original).reference();
 }
 @BeforeEach void setup(){install("agent",203);}
 SourceDedupStageProofService.Committed read(){return f.readonly(()->service.readCommitted(ref));}
 void complete(int number){f.chunk=f.plan.chunks().get(number);f.complete();}
 @Test void allOriginalCatalogPartsMustHaveActualAppliedProofsBeforeWholeOutput(){
  assertNull(read());complete(0);assertNull(read());complete(1);int rows=f.f.count("1=1");var actual=read();assertEquals(102,actual.proof().members().size());assertEquals(203,actual.proof().decisions().size());assertEquals(ref,actual.proof().planRef());assertEquals(f.f.clock.instant(),actual.minimumObservationTime());assertEquals(actual.proof().json(),read().proof().json());assertEquals(rows,f.f.count("1=1"));assertEquals(2,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void allFourNativeStagesAndEmptyOriginalPlansKeepExactObservationIdentity(){
  for(String category:List.of("agent","skill","workflow","model"))for(int count:List.of(0,5)){
   install(category,count);for(int n=0;n<f.plan.chunks().size();n++)complete(n);int rows=f.f.count("1=1");var result=read();assertNotNull(result);assertEquals(count,result.proof().decisions().size());assertEquals(category+"_dedup",CanonicalJson.parse(result.proof().observationKeyJson()).path("stage").asText());assertEquals(rows,f.f.count("1=1"));if(count==0){assertTrue(result.proof().members().isEmpty());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(f.plan.scope().cutoff(),result.minimumObservationTime());}
  }
 }
 @Test void oneDamagedRetainedFragmentOrOriginalArchiveCannotBecomeWholeOrPartialSuccess(){
  complete(0);complete(1);assertNotNull(read());f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.receipt());assertThrows(RuntimeException.class,this::read);
  String raw=JdbcFrozenInputs.canonical(f.pure.handoff(f.catalog,f.plan,f.chunk));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",JdbcGenerationLineage.hash(raw),f.receipt());assertNotNull(read());f.f.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenSourcePlans.receiptId(ref.ownerId()));assertThrows(RuntimeException.class,this::read);
 }
 @Test void newerCurrentIntentNeverReplacesOriginalAndExternalReferencesAreExact(){
  complete(0);complete(1);var original=read();f.tx(()->new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",f.plan.logicalKey()+":2"),f.plan.logicalKey(),2,"agent_dedup",f.plan.scope().businessDate()),f.f.clock.instant()));assertEquals(original.proof().json(),read().proof().json());
  assertThrows(RuntimeException.class,()->f.readonly(()->service.read(new FrozenSourceStagePlan.Reference(ref.ownerId(),ref.planHash(),"b".repeat(64)))));assertThrows(RuntimeException.class,()->f.readonly(()->service.read(new FrozenSourceStagePlan.Reference(ref.ownerId().toUpperCase(Locale.ROOT),ref.planHash(),ref.indexHash()))));
 }
 @Test void wholeReadRequiresActualReadonlyAndCannotTreatMissingOrFailedRootAsEmpty(){
  assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->f.tx(()->service.read(ref)));f.f.jdbc.update("UPDATE jobs SET state='failed',call_count=3 WHERE id=?",f.plan.chunks().getFirst().rootId());assertNull(read());f.f.jdbc.update("DELETE FROM jobs WHERE id=?",f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,this::read);
 }
}
