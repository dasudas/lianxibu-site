package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

/** Reuses the exact loopback-only fixture and real frozen-result business provider. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class ResultRecoveryCompetitionMySqlTest {
 @Test void hundredDualBarrierSeedsRollbackAndAckRecoveryNeverResendProvider() throws Exception {
  for(int seed=0;seed<100;seed++){try(var f=new PooledResultJdbcMySqlFixture()){
   f.fixture();var tx=new RetryFault(f.source);f.tx=tx;
   f.writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.source),tx,()->25,n->{});f.restart();
   tx.rollback.set(true);tx.ackLost=seed%2!=0;
   String preferred=seed%2==0?"fictional-forward":"fictional-reverse";
   var gate=new GatedStore(f.store,preferred);f.service=f.applicationService(gate);
   var input=f.store.input();String raw=f.raw();var action=f.action("fictional-result-seed-"+seed);
   try(var workers=Executors.newFixedThreadPool(2)){
    var a=workers.submit(()->{Thread.currentThread().setName("fictional-forward");return replayOriginal(()->f.service.applyAutomatic(input,raw,action),()->{});});
    var b=workers.submit(()->{Thread.currentThread().setName("fictional-reverse");return replayOriginal(()->f.service.applyAutomatic(input,raw,action),()->{});});
    var one=a.get(15,TimeUnit.SECONDS);var two=b.get(15,TimeUnit.SECONDS);
    assertEquals(Status.APPLIED,one.status(),"seed "+seed);assertEquals(one,two,"same permanent receipt seed "+seed);
   }catch(Exception e){throw new AssertionError("result recovery seed "+seed,e);}
   assertEquals(0,gate.initialReads.getCount());assertEquals(0,gate.firstWork.getCount());
   assertEquals(1,f.sends,"provider request count seed "+seed);assertEquals(2,f.handlerCalls,"one rollback plus one committed business application seed "+seed);
   assertEquals(1,f.count("contents"));assertEquals(1L,f.jdbc.queryForObject("SELECT body_revision FROM contents",Long.class));
   assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id='fictional-downstream'",Integer.class));
   assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='result_application'",Integer.class));
   assertNotNull(new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).appliedAt());
   assertEquals(ResultApplicationService.validate(input,raw).resultHash(),new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).resultHash());
   assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());assertEquals("free",new JdbcWorkerState(f.source).slot().state());
   f.restart();assertEquals(Status.APPLIED,f.service.applyAutomatic(input,raw,action).status());
   assertEquals(2,f.handlerCalls);assertEquals(1,f.sends);}
  }
 }
 @Test void BusyAdmissionReplaysTheSameOriginalResultWithoutAnotherProviderSend()throws Exception{
  try(var f=new PooledResultJdbcMySqlFixture()){
   f.fixture();var input=f.store.input();String raw=f.raw();var action=f.action("fictional-busy-result-replay");
   var busyObserved=new CountDownLatch(1);
   try(var workers=Executors.newSingleThreadExecutor()){
    Future<Result> outcome;
    try(var admission=new JdbcSharedWriteEntry(f.source).admit()){
     outcome=workers.submit(()->replayOriginal(()->f.service.applyAutomatic(input,raw,action),busyObserved::countDown));
     assertTrue(busyObserved.await(3,TimeUnit.SECONDS),"fixture must observe the original bounded busy result before release");
     assertEquals(0,f.count("contents"));assertEquals(1,f.sends);
     assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());
    }
    var result=outcome.get(5,TimeUnit.SECONDS);assertEquals(Status.APPLIED,result.status());
    assertEquals(result,f.service.applyAutomatic(input,raw,action));
    assertEquals(1,f.sends);assertEquals(1,f.handlerCalls);assertEquals(1,f.count("contents"));
    assertEquals(1L,f.jdbc.queryForObject("SELECT body_revision FROM contents",Long.class));
    assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='result_application'",Integer.class));
    assertEquals(1,new JdbcJobRecords(f.source).byId(ResultJdbcStoreTest.ROOT).callCount());assertEquals("free",new JdbcWorkerState(f.source).slot().state());
   }
  }
 }
 private static Result replayOriginal(java.util.function.Supplier<Result> original,Runnable busyObserved){
  for(int attempt=0;attempt<3;attempt++){
   try{return original.get();}
   catch(SharedWriteExecutor.WriteUnavailable busy){
    if(attempt==2)throw busy;
    busyObserved.run();
    try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("fictional result replay interrupted",interrupted);}
   }
  }
  throw new IllegalStateException("fictional unreachable replay state");
 }
 private static final class RetryFault extends ResultJdbcStoreTest.FaultManager {
  final AtomicBoolean rollback=new AtomicBoolean();RetryFault(DataSource source){super(source);}
  @Override protected void doCommit(DefaultTransactionStatus s){
   if(!s.isReadOnly()&&rollback.getAndSet(false)){super.doRollback(s);throw new TransactionSystemException("fictional result rollback",new SQLException("fictional deadlock","40001",1213));}
   super.doCommit(s);
  }
 }
 private static final class GatedStore implements Store {
  final Store original;final String preferred;final CountDownLatch initialReads=new CountDownLatch(2),firstWork=new CountDownLatch(1);final ThreadLocal<Boolean> read=ThreadLocal.withInitial(()->false);
  GatedStore(Store original,String preferred){this.original=original;this.preferred=preferred;}
  private static void await(CountDownLatch barrier){try{if(!barrier.await(5,TimeUnit.SECONDS))throw new AssertionError("fictional barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
  public void verifyInput(Input input){original.verifyInput(input);}
  public Result committed(String key,String hash){var result=original.committed(key,hash);if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&!read.get()){
   read.set(true);initialReads.countDown();await(initialReads);if(!Thread.currentThread().getName().equals(preferred))await(firstWork);
  }return result;}
  public Current current(String root){if(firstWork.getCount()!=0){assertEquals(preferred,Thread.currentThread().getName());firstWork.countDown();}return original.current(root);}
  public void retain(String root,String attempt,Validated result){original.retain(root,attempt,result);}
  public void defer(String root,Status reason,String hash,String key,String command){original.defer(root,reason,hash,key,command);}
  public void markApplied(String root,String hash,String id,Long revision,String key,String command){original.markApplied(root,hash,id,revision,key,command);}
  public Validated retained(String root){return original.retained(root);}
 }
}
