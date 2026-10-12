package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete nonempty native NEWS/daily input; barriers synchronize actual capture/receipt reads,
 * never grant permission. The three model responses remain fictional. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsNativeDailyCompetitionMySqlTest {
 NewsNativeDailyPublicMySqlTest f;
 @BeforeEach void utc(){f=new NewsNativeDailyPublicMySqlTest();f.utc();}
 @AfterEach void restore(){f.restore();}
 static void await(CountDownLatch barrier){try{if(!barrier.await(10,TimeUnit.SECONDS))throw new AssertionError("fictional native competition barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
 static class WorkerBarrier implements WorkerAuthorizationService.Store {
  final WorkerAuthorizationService.Store actual;final CountDownLatch claims,sends;
  WorkerBarrier(WorkerAuthorizationService.Store actual,CountDownLatch claims,CountDownLatch sends){this.actual=actual;this.claims=claims;this.sends=sends;}
  @Override public WorkerAuthorizationService.Store captureClaim(SharedWriteExecutor.ActionContext action,Instant now){var captured=actual.captureClaim(action,now);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());claims.countDown();await(claims);return new WorkerBarrier(captured,claims,sends);}
  @Override public WorkerAuthorizationService.Store captureAuthorization(Identity identity){var captured=actual.captureAuthorization(identity);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());sends.countDown();await(sends);return new WorkerBarrier(captured,claims,sends);}
  public Identity committedClaim(String key){return actual.committedClaim(key);}public Authorization committedAuthorization(String key){return actual.committedAuthorization(key);}public Boolean committedAbort(String key){return actual.committedAbort(key);}public boolean paused(){return actual.paused();}public boolean slotFree(){return actual.slotFree();}public List<Candidate> candidates(Instant now){return actual.candidates(now);}public Identity reserve(Candidate candidate,String token,Instant lease,Instant now,String key){return actual.reserve(candidate,token,lease,now,key);}public Authorization authorize(Identity identity,Instant now,String key){return actual.authorize(identity,now,key);}public Boolean refundNotSent(Identity identity,Instant next,String reason,String key){return actual.refundNotSent(identity,next,reason,key);}
 }
 static class ResultBarrier implements ResultApplicationService.Store {
  final ResultApplicationService.Store actual;final CountDownLatch readers;final ThreadLocal<Boolean> observed=ThreadLocal.withInitial(()->false);
  ResultBarrier(ResultApplicationService.Store actual,CountDownLatch readers){this.actual=actual;this.readers=readers;}
  public void verifyInput(Input input){actual.verifyInput(input);}public boolean retainAfterAutomaticFailure(Input input){return actual.retainAfterAutomaticFailure(input);}
  public Result committed(String key,String hash){var result=actual.committed(key,hash);if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&!observed.get()){observed.set(true);assertNull(result);readers.countDown();await(readers);}return result;}
  public Current current(String root){return actual.current(root);}public void retain(String root,String attempt,Validated value){actual.retain(root,attempt,value);}public void defer(String root,Status status,String hash,String key,String command){actual.defer(root,status,hash,key,command);}public void markApplied(String root,String hash,String content,Long revision,String key,String command){actual.markApplied(root,hash,content,revision,key,command);}public Validated retained(String root){return actual.retained(root);}
 }
 @SuppressWarnings("unchecked") ResultApplicationService gated(AttemptOutcomeService.Application app,CountDownLatch barrier){var service=app.service();var writes=(SharedWriteExecutor)ReflectionTestUtils.getField(service,"writes");var original=(ResultApplicationService.Store)ReflectionTestUtils.getField(service,"store");var handlers=(Map<String,Handler>)ReflectionTestUtils.getField(service,"handlers");return new ResultApplicationService(writes,new ResultBarrier(original,barrier),handlers);}
 String raw(Identity identity){var profile=ReportPreparationInputProfile.complete(CanonicalJson.parse(f.prepared.input().snapshotJson()));return new ReportNativeGenerationIntegrationTest().raw(profile.command().applicationContext().input(identity.rootId(),identity.attemptId(),identity.inputHash(),new JdbcJobRecords(f.db.source).byId(identity.rootId()).revision()));}
 void committed(String root,Result result){assertEquals(Status.APPLIED,result.status());assertEquals(1,new JdbcJobRecords(f.db.source).byId(root).callCount());assertEquals("free",new JdbcWorkerState(f.db.source).slot().state());assertEquals(1L,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE kind='report'",Long.class));assertEquals(1L,f.db.jdbc.queryForObject("SELECT body_revision FROM contents WHERE kind='report'",Long.class));assertEquals(1L,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='report_result_ready'",Long.class));assertNotNull(f.h.rr(()->new JdbcReportCompletionReceipts(f.db.source).read(root)));assertEquals(2,f.h.newsFlow.sends);assertEquals(3,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}
 @Test void BothOriginalNativeCapturesCompeteForOneClaimOneSendAndOneFullResultCommit()throws Exception{
  f.setup();String root=f.prepared.input().ownerId();var original=(WorkerAuthorizationService.Store)ReflectionTestUtils.getField(f.worker,"store");var claims=new CountDownLatch(2);var sends=new CountDownLatch(2);var barrierStore=new WorkerBarrier(original,claims,sends);var first=new WorkerAuthorizationService(f.db.writes,barrierStore,f.clock,()->true);var second=new WorkerAuthorizationService(f.db.writes,barrierStore,f.clock,()->true);var action=new SharedWriteExecutor.ActionContext("fictional-native-dual-claim",root,f.prepared.input().inputHash());var sent=new AtomicInteger();
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->first.claim(action));var b=pool.submit(()->second.claim(action));var one=a.get(20,TimeUnit.SECONDS);var two=b.get(20,TimeUnit.SECONDS);assertEquals(State.CLAIMED,one.state());assertEquals(State.CLAIMED,two.state());assertEquals(one.permit().identity(),two.permit().identity());assertEquals(0,claims.getCount());var identity=one.permit().identity();String raw=raw(identity);
   WorkerAuthorizationService.Sender<String> sender=request->{assertEquals(identity.frozenRequestJson(),request);sent.incrementAndGet();return raw;};var x=pool.submit(()->first.dispatch(one.permit(),sender));var y=pool.submit(()->second.dispatch(two.permit(),sender));var states=List.of(x.get(20,TimeUnit.SECONDS).state(),y.get(20,TimeUnit.SECONDS).state());assertEquals(1,states.stream().filter(state->state==State.SENT).count());assertEquals(1,states.stream().filter(state->state==State.UNKNOWN).count());assertEquals(0,sends.getCount());assertEquals(1,sent.get());
   var app1=f.factory.forAttempt(identity);var app2=f.factory.forAttempt(identity);var readers=new CountDownLatch(2);var service1=gated(app1,readers);var service2=gated(app2,readers);var resultAction=new SharedWriteExecutor.ActionContext("fictional-native-dual-result",root,identity.inputHash());var result1=pool.submit(()->service1.applyAutomatic(app1.input(),raw,resultAction));var result2=pool.submit(()->service2.applyAutomatic(app2.input(),raw,resultAction));var result=result1.get(20,TimeUnit.SECONDS);assertEquals(result,result2.get(20,TimeUnit.SECONDS));assertEquals(0,readers.getCount());committed(root,result);assertEquals(1,sent.get());
  }
 }
 @Test void LostAckAfterActualWholeResultCommitRecoversTheSameNativeReceiptWithoutSendingAgain()throws Exception{
  f.setup();String root=f.prepared.input().ownerId();var claim=f.worker.claim(new SharedWriteExecutor.ActionContext("fictional-native-ack-claim",root,f.prepared.input().inputHash()));assertEquals(State.CLAIMED,claim.state());var identity=claim.permit().identity();String raw=raw(identity);var sent=new AtomicInteger();assertEquals(State.SENT,f.worker.dispatch(claim.permit(),request->{sent.incrementAndGet();return raw;}).state());
  var fault=new ResultJdbcStoreTest.FaultManager(f.db.source);var graph=f.h.newsFlow.day.graph;var catalog=new LinkedHashMap<String,CatalogDayAnalysisResultPermitService>();f.h.catalogGraphs.forEach((category,value)->catalog.put(category,value.result()));var factory=new ResultApplicationFactory(f.db.source,fault,f.clock,graph.dependencies(),graph.dedupPermits(),catalog,new JdbcEntityHeatSnapshots(f.db.source),f.permits);var app=factory.forAttempt(identity);var action=new SharedWriteExecutor.ActionContext("fictional-native-ack-result",root,identity.inputHash());fault.ackLost=true;var result=app.service().applyAutomatic(app.input(),raw,action);assertFalse(fault.ackLost);committed(root,result);int rows=f.db.count("1=1");var restart=factory.forAttempt(identity);assertEquals(result,restart.service().applyAutomatic(restart.input(),raw,action));assertEquals(rows,f.db.count("1=1"));assertEquals(1,sent.get());
 }
}
