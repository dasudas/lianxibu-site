package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.net.*;import java.time.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.*;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.transaction.support.DefaultTransactionStatus;import org.springframework.transaction.TransactionSystemException;

/** Fictional, isolated real-MySQL worker persistence verification. */
@EnabledIfEnvironmentVariable(named="V03_MYSQL",matches="true")
class WorkerJdbcMySqlTest {
    private HikariDataSource pool;
    @org.junit.jupiter.api.AfterEach void closePool(){if(pool!=null)pool.close();}
    private HikariDataSource pooled(String url){pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername(System.getenv().getOrDefault("V03_MYSQL_USER","root"));pool.setPassword(System.getenv().getOrDefault("V03_MYSQL_PASSWORD",""));pool.setMaximumPoolSize(5);pool.setMinimumIdle(1);pool.setConnectionTimeout(5000);return pool;}

 private JdbcTemplate jdbc;private JdbcWorkerState state;private JdbcJobRecords records;private SharedWriteExecutor writes;private FaultManager manager;private javax.sql.DataSource db;
 private final MutableClock clock=new MutableClock();private volatile boolean paused,ready=true;
 @BeforeEach void fixture() throws Exception {
  String url=System.getenv("B40_MYSQL_URL");if(url==null||!url.startsWith("jdbc:"))throw new IllegalStateException("test database required");var endpoint=URI.create(url.substring(5));
  if(endpoint.getPort()!=13336||!"mysql".equals(endpoint.getScheme())||endpoint.getHost()==null||!InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()||!"/fictional_v03_worker".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback database required");
  db=pooled(url);jdbc=new JdbcTemplate(db);
  assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("utf8mb4_0900_ai_ci",jdbc.queryForObject("SELECT @@collation_database",String.class));assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
  jdbc.execute("DROP TABLE IF EXISTS worker_lock");jdbc.execute("DROP TABLE IF EXISTS jobs");
  jdbc.execute("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) NOT NULL UNIQUE,task_kind VARCHAR(32) NOT NULL,target_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,priority_no INT NOT NULL,eligible_at DATETIME(6) NOT NULL,revision BIGINT NOT NULL,created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,record_kind VARCHAR(24) NOT NULL,record_identity_hash BINARY(32) NOT NULL UNIQUE,call_count INT NOT NULL,max_attempts INT,root_job_id VARCHAR(64),attempt_no INT,logical_key TEXT,logical_key_hash BINARY(32),generation BIGINT,stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,input_state VARCHAR(16),request_json JSON,snapshot_owner_id VARCHAR(64),input_hash BINARY(32),payload_json JSON,owner_job_id VARCHAR(64),purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_hash BINARY(32),action_key_hash BINARY(32) UNIQUE,target_type VARCHAR(32),target_key_json JSON,target_key_hash BINARY(32),operator_id VARCHAR(64),reason VARCHAR(2048),acted_at DATETIME(6),action_payload JSON,action_payload_hash BINARY(32),worker_instance_id VARCHAR(64),dispatch_token BIGINT UNSIGNED,request_deadline DATETIME(6),send_authorized_at DATETIME(6),slot_released_at DATETIME(6),release_reason VARCHAR(32),dependency_id VARCHAR(64),applied_at DATETIME(6),error_code VARCHAR(64),provider_request_id VARCHAR(256),usage_json JSON,cost_decimal DECIMAL(20,8)) ENGINE=InnoDB");
  jdbc.execute("CREATE TABLE worker_lock(id TINYINT UNSIGNED PRIMARY KEY,slot_state VARCHAR(16) NOT NULL,slot_revision BIGINT UNSIGNED NOT NULL,fencing_token BIGINT UNSIGNED NOT NULL,job_id VARCHAR(64),holder VARCHAR(64),lease_until DATETIME(6),request_deadline DATETIME(6),occupied_since DATETIME(6)) ENGINE=InnoDB");
  manager=new FaultManager(db);state=new JdbcWorkerState(db);records=new JdbcJobRecords(db);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(db),manager,()->25,ignored->{});
  reset();seedBoth();
 }
 private void reset(){jdbc.update("DELETE FROM worker_lock");jdbc.update("DELETE FROM jobs");jdbc.update("INSERT INTO worker_lock(id,slot_state,slot_revision,fencing_token) VALUES(7,'free',0,0)");clock.now=Instant.parse("2026-01-01T18:00:00Z");paused=false;ready=true;}
 private void seed(String label){String hash="a".repeat(64);String request="{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional fixture\"}],\"parameters\":{},\"response_format\":{\"type\":\"json_object\"},\"schema_version\":\"fixture\",\"prompt_version\":\"fixture\",\"input_hash\":\""+hash+"\"}";
  records.ensureIntent(new JdbcJobRecords.Intent("intent-"+label,"fictional-"+label,0,"fixture",LocalDate.of(2026,1,1)),clock.instant());records.createRoot(new JdbcJobRecords.Root("root-"+label,"intent-"+label,"fictional-"+label,0,0,"fixture",1),clock.instant());records.prepareAttempt(new JdbcJobRecords.Attempt("attempt-"+label,"root-"+label,1,request,"root-"+label,hash),clock.instant());jdbc.update("UPDATE jobs SET state='queued' WHERE id=?","root-"+label);
 }
 private void seedBoth(){new TransactionTemplate(manager).execute(status->{seed("first");seed("second");return null;});}
 private WorkerAuthorizationService worker(String epoch){return new WorkerAuthorizationService(writes,new WorkerJdbcStore(state,records,epoch,()->paused,root->ready,clock),clock,()->true);}
 @Test void persistedClaimBudgetAndOneAuthorizationUseNumericFencingAndActualWorkerId(){
  var worker=worker("fictional-epoch-a");var claim=worker.claim(action("claim-first"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());var id=claim.permit().identity();assertEquals("1",id.dispatchToken());assertEquals(1,id.fencing());assertEquals(7,state.slot().id());assertEquals(1,records.byId(id.rootId()).callCount());
  assertEquals(id,worker.claim(action("claim-first")).permit().identity());var calls=new java.util.concurrent.atomic.AtomicInteger();assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{calls.incrementAndGet();return "fictional-response";}).state());assertEquals(1,calls.get());
  var restarted=worker("fictional-epoch-b");var replay=restarted.claim(action("claim-first"));assertEquals(WorkerAuthorizationService.State.UNKNOWN,restarted.dispatch(replay.permit(),request->{calls.incrementAndGet();return "must not send";}).state());assertEquals(1,calls.get());assertEquals(1,records.byId(id.rootId()).callCount());
 }
 @Test void liveProofRefundsAtWindowOrPauseButChangedSlotIsNeverReleased(){
  var worker=worker("fictional-epoch-a");clock.now=Instant.parse("2026-01-01T22:59:59Z");var claim=worker.claim(action("claim-window"));clock.now=Instant.parse("2026-01-01T23:00:00Z");assertEquals(WorkerAuthorizationService.State.NOT_SENT,worker.dispatch(claim.permit(),request->{throw new AssertionError("no HTTP allowed");}).state());assertEquals(0,records.byId(claim.permit().identity().rootId()).callCount());assertEquals("prepared",records.byId(claim.permit().attemptId()).state());assertEquals("free",state.slot().state());
  clock.now=Instant.parse("2026-01-02T18:00:00Z");var next=worker.claim(action("claim-paused"));paused=true;assertEquals(WorkerAuthorizationService.State.NOT_SENT,worker.dispatch(next.permit(),request->{throw new AssertionError();}).state());assertEquals("free",state.slot().state());
  paused=false;clock.now=Instant.parse("2026-01-03T18:00:00Z");var replaced=worker.claim(action("claim-replaced"));jdbc.update("UPDATE worker_lock SET holder='fictional-new-epoch',job_id='attempt-second',slot_revision=99,fencing_token=99");assertEquals(WorkerAuthorizationService.State.LOST,worker.dispatch(replaced.permit(),request->{throw new AssertionError();}).state());assertEquals(99,state.slot().fencing());assertEquals("attempt-second",state.slot().attempt());assertEquals(1,records.byId(replaced.permit().identity().rootId()).callCount());
 }
 @Test void missingDependencyOrChangedFrozenRequestCannotSend(){
  ready=false;assertEquals(WorkerAuthorizationService.State.IDLE,worker("fictional-epoch-a").claim(action("not-ready")).state());ready=true;
  jdbc.update("UPDATE jobs SET dependency_id='fictional-missing' WHERE record_kind='logical_task'");assertEquals(WorkerAuthorizationService.State.IDLE,worker("fictional-epoch-a").claim(action("missing-dependency")).state());jdbc.update("UPDATE jobs SET dependency_id=NULL");
  var worker=worker("fictional-epoch-a");var claim=worker.claim(action("claim-change"));jdbc.update("UPDATE jobs SET input_hash=UNHEX(?) WHERE id=?","b".repeat(64),claim.permit().attemptId());assertEquals(WorkerAuthorizationService.State.LOST,worker.dispatch(claim.permit(),request->{throw new AssertionError();}).state());assertEquals("occupied",state.slot().state());
 }
 @Test void twoProcessesContendForActualSlotForOneHundredBothDirectionSeeds() throws Exception {
  for(int seed=0;seed<100;seed++){
   reset();seedBoth();String preferred=seed%2==0?"fictional-a":"fictional-b",other=seed%2==0?"fictional-b":"fictional-a";
   var firstWorker=worker(preferred);var secondWorker=worker(other);manager.holding=preferred;manager.ready=new CountDownLatch(1);manager.release=new CountDownLatch(1);
   try(var pool=Executors.newFixedThreadPool(2)){
    var first=pool.submit(()->{Thread.currentThread().setName(preferred);return firstWorker.claim(action("claim-first-"+preferred));});assertTrue(manager.ready.await(5,TimeUnit.SECONDS),"commit barrier seed "+seed);
    var second=pool.submit(()->{Thread.currentThread().setName(other);return secondWorker.claim(action("claim-second-"+other));});
    try{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800);boolean waiting=false;while(System.nanoTime()<deadline){if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE r.OBJECT_SCHEMA='fictional_v03_worker' AND r.OBJECT_NAME='worker_lock'",Integer.class)>0){waiting=true;break;}Thread.sleep(5);}assertTrue(waiting,"actual writer wait seed "+seed);}finally{manager.release.countDown();}
    assertEquals(WorkerAuthorizationService.State.CLAIMED,first.get(5,TimeUnit.SECONDS).state());assertEquals(WorkerAuthorizationService.State.IDLE,second.get(5,TimeUnit.SECONDS).state());
   }catch(Throwable failure){throw new AssertionError("B40 seed="+seed+", preferred="+preferred,failure);}finally{manager.release.countDown();manager.holding=null;}
   assertEquals(preferred,state.slot().holder());assertEquals(1,state.slot().fencing());assertEquals(1,jdbc.queryForObject("SELECT SUM(call_count) FROM jobs WHERE record_kind='logical_task'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_claim'",Integer.class));
  }
 }
 @Test void authorizationCommitAckLossKeepsUsedBudgetAndNeverInvokesSender(){
  var worker=worker("fictional-epoch-a");var claim=worker.claim(action("claim-ack-lost"));manager.ackLost=true;
  assertEquals(WorkerAuthorizationService.State.UNKNOWN,worker.dispatch(claim.permit(),request->{throw new AssertionError("no HTTP on uncertain authorization");}).state());assertTrue(state.authorized(claim.permit().attemptId()));assertEquals(1,records.byId(claim.permit().identity().rootId()).callCount());assertEquals("occupied",state.slot().state());
 }
 @Test void reserveRollbackRetriesDatabaseOnlyAndPersistsOneClaim(){
  manager.rollback=true;var claim=worker("fictional-epoch-a").claim(action("claim-rollback"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());assertEquals(1,records.byId(claim.permit().identity().rootId()).callCount());assertEquals(1,state.slot().fencing());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_claim'",Integer.class));
 }
 private UnknownCallRecoveryService recovery(java.util.function.Predicate<String> stopped){return new UnknownCallRecoveryService(writes,new UnknownCallJdbcStore(state,new JdbcWorkerRecovery(db,state),records,"fictional-recovery",stopped),clock);}
 @Test void timeoutUnknownReleaseLateAndCloseKeepNewSlotAndOriginalBudget(){
  var recovery=recovery(epoch->false);var first=worker("fictional-epoch-a").claim(action("claim-unknown"));String old=first.permit().attemptId(),root=first.permit().identity().rootId();
  assertThrows(UnknownCallRecoveryService.Rejected.class,()->recovery.release(old,UnknownCallRecoveryService.Cause.TIMEOUT,action("too-early")));assertEquals("running",records.byId(root).state());
  clock.now=clock.now.plusSeconds(90);var result=recovery.release(old,UnknownCallRecoveryService.Cause.TIMEOUT,action("release-unknown"));assertTrue(result.slotReleased());assertEquals("unknown",result.usageState());assertEquals(1,records.byId(root).callCount());assertEquals("unknown_external",records.byId(root).state());assertEquals("free",state.slot().state());assertEquals(2,state.slot().fencing());
  var next=worker("fictional-epoch-b").claim(action("claim-new"));assertEquals(WorkerAuthorizationService.State.CLAIMED,next.state());var slot=state.slot();
  var evidence=new UnknownCallRecoveryService.LateEvidence("fictional-provider",12L,null,"a".repeat(64),"Fictional late evidence");assertTrue(recovery.appendLate(old,evidence,action("late-old")));assertTrue(recovery.appendLate(old,evidence,action("late-old")));assertEquals(slot,state.slot());assertNull(jdbc.queryForObject("SELECT cost_decimal FROM jobs WHERE id=?",java.math.BigDecimal.class,old));
  assertThrows(UnknownCallRecoveryService.Rejected.class,()->recovery.appendLate(old,new UnknownCallRecoveryService.LateEvidence("changed-provider",12L,null,"a".repeat(64),"Fictional late evidence"),action("late-old")));
  long revision=records.byId(root).revision();assertThrows(UnknownCallRecoveryService.Rejected.class,()->recovery.closeUnresolved(old,revision,"fictional-owner","Fictional close",false,action("close-denied")));assertThrows(UnknownCallRecoveryService.Rejected.class,()->recovery.closeUnresolved(old,revision-1,"fictional-owner","Fictional close",true,action("close-stale")));
  var closed=recovery.closeUnresolved(old,revision,"fictional-owner","Fictional close",true,action("close-old"));assertFalse(closed.slotReleased());assertEquals("blocked",records.byId(root).state());assertEquals(slot,state.slot());assertEquals(1,records.byId(root).callCount());assertEquals(closed,recovery.closeUnresolved(old,revision,"fictional-owner","Fictional close",true,action("close-old")));
 }
 @Test void restartProofAndLeaseExpiryNeverRefundAndUnknownCommitFindsDurableRecovery(){
  var first=worker("fictional-epoch-a").claim(action("claim-stop"));var recovery=recovery(epoch->"fictional-epoch-a".equals(epoch));manager.ackLost=true;
  var released=recovery.release(first.permit().attemptId(),UnknownCallRecoveryService.Cause.STOPPED_PROCESS,action("stopped-proof"));assertTrue(released.slotReleased());assertEquals(1,released.callCount());assertEquals(released,recovery.release(first.permit().attemptId(),UnknownCallRecoveryService.Cause.STOPPED_PROCESS,action("stopped-proof")));
  var next=worker("fictional-epoch-b").claim(action("claim-expiry"));clock.now=clock.now.plusSeconds(150);manager.rollback=true;
  var expired=recovery(epoch->false).release(next.permit().attemptId(),UnknownCallRecoveryService.Cause.LEASE_EXPIRED,action("lease-expired"));assertTrue(expired.slotReleased());assertEquals(1,expired.callCount());assertEquals("unknown_external",records.byId(next.permit().identity().rootId()).state());assertEquals("free",state.slot().state());assertEquals(4,state.slot().fencing());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_recovery' AND reason='LEASE_EXPIRED'",Integer.class));
 }
 @Test void additionalLateEvidenceCannotEraseKnownUsageOrReplaceProviderIdentity(){
  var recovery=recovery(epoch->true);var claim=worker("fictional-epoch-a").claim(action("claim-late-known"));String attempt=claim.permit().attemptId();recovery.release(attempt,UnknownCallRecoveryService.Cause.STOPPED_PROCESS,action("release-late-known"));
  recovery.appendLate(attempt,new UnknownCallRecoveryService.LateEvidence("fictional-provider",12L,null,null,"Fictional known prompt"),action("late-known"));
  recovery.appendLate(attempt,new UnknownCallRecoveryService.LateEvidence(null,null,5L,null,"Fictional completion only"),action("late-completion"));
  assertEquals(12,jdbc.queryForObject("SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(usage_json,'$.prompt_tokens')) AS UNSIGNED) FROM jobs WHERE id=?",Integer.class,attempt));assertEquals(5,jdbc.queryForObject("SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(usage_json,'$.completion_tokens')) AS UNSIGNED) FROM jobs WHERE id=?",Integer.class,attempt));
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_late'",Integer.class);assertThrows(RuntimeException.class,()->recovery.appendLate(attempt,new UnknownCallRecoveryService.LateEvidence("fictional-other-provider",null,null,null,"Fictional contradictory provider"),action("late-conflicting-provider")));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_late'",Integer.class));
  assertThrows(RuntimeException.class,()->recovery.appendLate(attempt,new UnknownCallRecoveryService.LateEvidence(null,13L,null,null,"Fictional contradictory usage"),action("late-conflicting-usage")));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_late'",Integer.class));
 }
 private static ActionContext action(String key){return new ActionContext(key,"receipt-"+key,null);}
 private static final class MutableClock extends Clock{volatile Instant now;public ZoneId getZone(){return ZoneId.of("UTC");}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
 private static final class FaultManager extends DataSourceTransactionManager{
  volatile String holding;volatile CountDownLatch ready,release;boolean ackLost,rollback;
  FaultManager(javax.sql.DataSource source){super(source);}
  @Override protected void doCommit(DefaultTransactionStatus status){
   if(!status.isReadOnly()&&holding!=null&&holding.equals(Thread.currentThread().getName())){ready.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("worker commit barrier timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
   if(!status.isReadOnly()&&rollback){rollback=false;super.doRollback(status);throw new TransactionSystemException("fictional rollback",new java.sql.SQLException("fictional deadlock","40001",1213));}
   super.doCommit(status);if(!status.isReadOnly()&&ackLost){ackLost=false;throw new TransactionSystemException("fictional commit ACK loss",new java.sql.SQLException("fictional lost ACK","08006"));}
  }
 }
}
