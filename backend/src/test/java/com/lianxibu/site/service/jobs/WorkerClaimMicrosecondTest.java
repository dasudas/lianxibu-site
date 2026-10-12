package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.State;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real original P1/P2, native worker and receipts; original day is explicitly doubled. */
class WorkerClaimMicrosecondTest {
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 WorkerDayPermissionScopeTest fixture(int nanos)throws Exception{
  var f=new WorkerDayPermissionScopeTest(){@Override GenerationJdbcStoreTest database(){return WorkerClaimMicrosecondTest.this.database();}};f.setup();f.clock=Clock.fixed(f.clock.instant().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS).plusNanos(nanos),ZoneOffset.UTC);f.restart();return f;
 }
 @Test void actualLeaseAndPermanentIdentityStayExactAtFourClockPrecisions()throws Exception{
  for(int nanos:new int[]{0,123456000,123456789,999999999}){
   var f=fixture(nanos);var claim=f.worker.claim(f.action());assertEquals(State.CLAIMED,claim.state());var identity=claim.permit().identity();assertEquals(f.clock.instant().truncatedTo(ChronoUnit.MICROS).plusSeconds(150),identity.leaseUntil());assertEquals(0,identity.leaseUntil().getNano()%1000);
   assertEquals(identity,f.store.committedClaim(f.action().actionKey()));assertEquals(identity.leaseUntil(),f.state.slot().lease());var sends=new AtomicInteger();assertEquals(State.SENT,f.worker.dispatch(claim.permit(),request->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(identity.frozenRequestJson(),request);sends.incrementAndGet();return "fictional-microsecond-result";}).state());
   assertEquals(1,sends.get());assertEquals(1,f.records.byId(identity.rootId()).callCount());f.restart();var replay=f.worker.claim(f.action());assertEquals(identity,replay.permit().identity());assertEquals(State.UNKNOWN,f.worker.dispatch(replay.permit(),request->{fail("original send cannot repeat");return null;}).state());assertEquals(1,f.db.count("target_type='worker_claim'"));assertEquals(1,f.db.count("target_type='worker_send'"));assertEquals(1,f.records.byId(identity.rootId()).callCount());
  }
 }
 @Test void exactOriginalIdentityDoesNotBypassDayProofOrAnExpiredNativeLease()throws Exception{
  var f=fixture(123456789);var claimed=f.worker.claim(f.action());assertEquals(State.CLAIMED,claimed.state());assertEquals(claimed.permit().identity(),f.store.committedClaim(f.action().actionKey()));org.mockito.Mockito.when(f.f.q.days.verify(org.mockito.Mockito.eq(f.f.q.day),org.mockito.Mockito.any())).thenThrow(new IllegalStateException("fictional day proof lost"));
  assertEquals(State.UNKNOWN,f.worker.dispatch(claimed.permit(),request->{fail("lost day cannot send");return null;}).state());assertEquals(0,f.db.count("target_type='worker_send'"));assertFalse(f.state.authorized(claimed.permit().attemptId()));assertEquals(1,f.records.byId(claimed.permit().identity().rootId()).callCount());
  var expired=fixture(999999999);var claim=expired.worker.claim(expired.action());assertEquals(State.CLAIMED,claim.state());expired.clock=Clock.fixed(claim.permit().identity().leaseUntil(),ZoneOffset.UTC);expired.restart();var replay=expired.worker.claim(expired.action());assertEquals(claim.permit().identity(),replay.permit().identity());assertEquals(State.LOST,expired.worker.dispatch(replay.permit(),request->{fail("expired native lease cannot send");return null;}).state());assertEquals(0,expired.db.count("target_type='worker_send'"));assertEquals(1,expired.records.byId(replay.permit().identity().rootId()).callCount());
 }
}
