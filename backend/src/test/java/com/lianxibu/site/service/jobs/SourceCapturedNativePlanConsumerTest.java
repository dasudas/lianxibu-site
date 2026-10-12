package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SourceCapturedNativePlanConsumerTest {
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 SourcePartialStagePreparationTest fixture()throws Exception{
  var f=new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return SourceCapturedNativePlanConsumerTest.this.database();}};f.setup(201,true);
  var plans=spy(f.f.plans);
  doThrow(new AssertionError("writer cannot decode the whole original plan again")).when(plans).readOriginal(anyString());
  doAnswer(call->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly(),"complete original native capture is outside the writer");return call.callRealMethod();}).when(plans).readImmutableOriginal(anyString());
  f.service=new SourceStagePreparationService(f.f.db.source,plans,f.f.f.f.f.p1.universes,f.f.f.f.f.p1.collections::read,f.f.clock,"fictional-owner",null,new SourceDedupDependencyService(f.f.f.observations,f.f.f.proven,f.f.f.service));return f;
 }
 @Test void actualOriginal201PartsUseFreshNativeProofInLookupAndWriterWithNoPureDecode()throws Exception{
  var f=fixture();int calls=f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);
  for(int n=0;n<f.plan.chunks().size();n++){
   var original=f.prepare(n);assertEquals(f.plan.chunks().get(n).snapshotJson(),original.snapshotJson());assertEquals(f.plan.chunks().get(n).requestJson(),original.requestJson());assertEquals(original,f.prepare(n));
  }
  assertTrue(f.progress().complete());assertEquals(calls,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
 }
 @Test void nativeChangedLastPlanBlockAfterCaptureRollsBackEveryNewRootAndInput()throws Exception{
  var f=fixture();var captured=f.service.capturePartPartial(f.f.intent,0,f.action(0),f.f.permit);
  var ro=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.db.source));ro.setReadOnly(true);
  String id=ro.execute(status->new JdbcFrozenSourcePlans(f.f.db.source).immutableParts(f.f.intent)).getLast().id();
  int count=f.f.db.count("1=1");f.f.db.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],id);
  assertThrows(RuntimeException.class,()->f.f.db.writes.execute(new SharedWriteExecutor.ActionContext(f.action(0),f.plan.chunks().getFirst().rootId(),f.plan.chunks().getFirst().inputHash()),key->f.service.committedCaptured(captured),action->f.service.prepareCaptured(captured)));
  assertEquals(count,f.f.db.count("1=1"));assertEquals(0,f.f.db.count("record_kind='logical_task' AND task_type='news_value'"));
 }
}
