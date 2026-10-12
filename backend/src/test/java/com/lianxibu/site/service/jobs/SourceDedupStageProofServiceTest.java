package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class SourceDedupStageProofServiceTest {
 SourceDedupResultProofServiceTest f;SourceDedupStageProofService service;FrozenSourceStagePlan.Reference ref;
 GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
 void install(int count){
  f=new SourceDedupResultProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceDedupStageProofServiceTest.this.fixture();}@Override MemberUniverse.Universe universe(){return FrozenSourceUniverseTest.universe(count,false);}};f.setup();
  service=new SourceDedupStageProofService(f.archives,f.universes,f.service);ref=FrozenSourceStagePlan.encode(f.plan,f.original).reference();
 }
 @BeforeEach void setup(){install(203);}
 SourceDedupStageResult read(){return f.readonly(()->service.read(ref));}
 void complete(int n){f.chunk=f.plan.chunks().get(n);f.complete();}
 @Test void everyOriginalRootMustBeAppliedBeforeActualWholeSourceOutputExists(){
  assertNull(read());complete(0);assertNull(read());complete(1);int count=f.f.count("1=1");var result=read();assertEquals(102,result.members().size());assertEquals(203,result.decisions().size());assertEquals(ref,result.planRef());assertEquals(result.json(),read().json());assertEquals(count,f.f.count("1=1"));assertEquals(2,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void theActualEmptyOriginalPlanGivesStructuralZeroWithoutAnyAiRoot(){
  install(0);int count=f.f.count("1=1");var result=read();assertTrue(result.members().isEmpty());assertTrue(result.decisions().isEmpty());assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.count("record_kind='logical_task'"));assertEquals(0,f.f.count("record_kind='ai_attempt'"));
 }
 @Test void newerIntentNeverReplacesTheOriginalWholePlanAndExternalRefsAreExact(){
  complete(0);complete(1);var result=read();f.tx(()->new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",f.plan.logicalKey()+":2"),f.plan.logicalKey(),2,"news_dedup",f.plan.scope().businessDate()),f.f.clock.instant()));assertEquals(result.json(),read().json());
  var wrong=new FrozenSourceStagePlan.Reference(ref.ownerId(),ref.planHash(),"b".repeat(64));assertThrows(RuntimeException.class,()->f.readonly(()->service.read(wrong)));
  var alias=new FrozenSourceStagePlan.Reference(ref.ownerId().toUpperCase(java.util.Locale.ROOT),ref.planHash(),ref.indexHash());assertThrows(RuntimeException.class,()->f.readonly(()->service.read(alias)));
 }
 @Test void damagedOneFragmentOrOriginalArchiveCannotBecomePartialSuccess(){
  complete(0);complete(1);assertNotNull(read());f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],f.receipt());assertThrows(RuntimeException.class,this::read);
  String original=JdbcFrozenInputs.canonical(f.pure.handoff(f.plan,f.original,f.chunk));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",JdbcGenerationLineage.hash(original),f.receipt());assertNotNull(read());
  f.f.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenSourcePlans.receiptId(ref.ownerId()));assertThrows(RuntimeException.class,this::read);
 }
 @Test void aRealReadonlyTransactionIsRequiredAndMissingRootIsNeverEmptyInput(){
  assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->f.tx(()->service.read(ref)));
  f.f.jdbc.update("DELETE FROM jobs WHERE id=?",f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,this::read);
 }
}
