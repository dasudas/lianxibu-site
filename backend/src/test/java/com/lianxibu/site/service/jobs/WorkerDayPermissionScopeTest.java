package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original P1/P2, worker reservation and receipts are SQL; day authority is an explicit double. */
class WorkerDayPermissionScopeTest {
    SourceDayDispatchReadinessTest f;GenerationJdbcStoreTest db;JdbcWorkerState state;JdbcJobRecords records;
    DispatchReadinessService readiness;WorkerJdbcStore store;WorkerAuthorizationService worker;Clock clock;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{
        f=new SourceDayDispatchReadinessTest(){@Override GenerationJdbcStoreTest database(){return WorkerDayPermissionScopeTest.this.database();}};f.setup();db=f.db;
        WorkerCandidateSelectionScopeTest.installWorker(db);state=new JdbcWorkerState(db.source);records=new JdbcJobRecords(db.source);clock=f.q.f.f.clock;
        readiness=spy(f.service);restart();
    }
    void restart(){store=new WorkerJdbcStore(state,records,"fictional-day-worker",()->false,readiness,clock,new DataSourceTransactionManager(db.source));worker=new WorkerAuthorizationService(db.writes,store,clock,n->0,()->true);}
    SharedWriteExecutor.ActionContext action(){return new SharedWriteExecutor.ActionContext("fictional-day-worker-claim","fictional-day-worker-receipt",null);}
    @Test void ClaimAndSendCaptureTheOriginalDaySeparatelyAndRestartDoesNotRepeatTheSend()throws Exception{
        setup();var claim=worker.claim(action());assertEquals(State.CLAIMED,claim.state());var identity=claim.permit().identity();assertEquals(f.root,identity.rootId());AtomicInteger sends=new AtomicInteger();
        assertEquals(State.SENT,worker.dispatch(claim.permit(),json->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(identity.frozenRequestJson(),json);sends.incrementAndGet();return "fictional-day-result";}).state());
        verify(readiness,times(2)).captureSource(f.root);verify(readiness,times(2)).readyCaptured(eq(f.root),any());verify(readiness,never()).capturePartial(any());verify(readiness,never()).readyPartial(any(),any());
        assertEquals(1,sends.get());assertEquals(1,records.byId(f.root).callCount());assertEquals(1,db.count("target_type='worker_claim'"));assertEquals(1,db.count("target_type='worker_send'"));restart();var replay=worker.claim(action());assertEquals(identity,replay.permit().identity());assertEquals(State.UNKNOWN,worker.dispatch(replay.permit(),json->{fail("durable send cannot repeat");return null;}).state());assertEquals(1,sends.get());assertEquals(1,records.byId(f.root).callCount());
    }
    @Test void DayLostAfterCandidateCaptureCannotReserveBudgetOrTheSlot()throws Exception{
        setup();var scope=store.captureClaim(action(),clock.instant());var candidate=db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-day-candidate-read",f.root,null),key->null,c->scope.candidates(clock.instant())).getFirst();
        when(f.q.days.verify(eq(f.q.day),any())).thenThrow(new IllegalStateException("fictional original day lost"));
        assertThrows(RuntimeException.class,()->db.writes.execute(action(),key->null,c->scope.reserve(candidate,"fictional-hint",clock.instant().plusSeconds(150),clock.instant(),c.actionKey())));
        assertEquals(0,records.byId(f.root).callCount());assertEquals("queued",records.byId(f.root).state());assertEquals("free",state.slot().state());assertEquals(0,db.count("target_type='worker_claim'"));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void DayLostAfterClaimCannotAuthorizeOrCallTheSender()throws Exception{
        setup();var claim=worker.claim(action());assertEquals(State.CLAIMED,claim.state());when(f.q.days.verify(eq(f.q.day),any())).thenThrow(new IllegalStateException("fictional original day lost"));
        assertEquals(State.UNKNOWN,worker.dispatch(claim.permit(),json->{fail("lost day cannot send");return null;}).state());assertFalse(state.authorized(claim.permit().attemptId()));assertEquals(0,db.count("target_type='worker_send'"));assertEquals(1,records.byId(f.root).callCount());assertEquals("occupied",state.slot().state());
    }
    @Test void SendReceiptFailureRollsBackAndTheOriginalClaimCanRecover()throws Exception{
        setup();var claim=worker.claim(action());db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_day_send_receipt CHECK(target_type IS NULL OR target_type<>'worker_send')");
        assertEquals(State.UNKNOWN,worker.dispatch(claim.permit(),json->{fail("failed receipt cannot send");return null;}).state());assertFalse(state.authorized(claim.permit().attemptId()));assertEquals(0,db.count("target_type='worker_send'"));assertEquals(1,records.byId(f.root).callCount());
        db.jdbc.execute("ALTER TABLE jobs DROP "+(db.source instanceof org.h2.jdbcx.JdbcDataSource?"CONSTRAINT ":"CHECK ")+"fictional_day_send_receipt");restart();var replay=worker.claim(action());assertEquals(claim.permit().identity(),replay.permit().identity());assertEquals(State.SENT,worker.dispatch(replay.permit(),json->"fictional-recovered-result").state());assertEquals(1,db.count("target_type='worker_send'"));assertEquals(1,records.byId(f.root).callCount());
    }
}
