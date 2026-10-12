package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionSystemException;

/** Real original partial INPUT and claim; all sends are fictional callbacks. */
class WorkerPartialSendScopeTest {
    SourcePartialDispatchReadinessTest f;GenerationJdbcStoreTest db;JdbcWorkerState nativeState;
    JdbcJobRecords records;WorkerJdbcStore store;WorkerAuthorizationService worker;Identity identity;Clock clock;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SharedWriteExecutor.ActionContext claimAction(){return new SharedWriteExecutor.ActionContext("fictional-original-partial-claim",identity==null?"fictional-receipt":identity.rootId(),null);}
    void setup()throws Exception{
        f=new SourcePartialDispatchReadinessTest(){@Override GenerationJdbcStoreTest database(){return WorkerPartialSendScopeTest.this.database();}};f.setup();db=f.db;clock=f.q.clock;if(db.source instanceof org.h2.jdbcx.JdbcDataSource)db.jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");
        String time=db.source instanceof org.h2.jdbcx.JdbcDataSource?"TIMESTAMP(6)":"DATETIME(6)";
        for(String column:new String[]{"slot_state VARCHAR(16) DEFAULT 'free'","slot_revision BIGINT DEFAULT 0","fencing_token BIGINT DEFAULT 0","job_id VARCHAR(64)","holder VARCHAR(64)","lease_until "+time,"request_deadline "+time,"occupied_since "+time})db.jdbc.execute("ALTER TABLE worker_lock ADD "+column);
        for(String column:new String[]{"worker_instance_id VARCHAR(64)","dispatch_token BIGINT","request_deadline "+time,"send_authorized_at "+time,"slot_released_at "+time,"release_reason VARCHAR(32)"})db.jdbc.execute("ALTER TABLE jobs ADD "+column);
        nativeState=new JdbcWorkerState(db.source);records=new JdbcJobRecords(db.source);String root=f.roots.getFirst();var proof=f.service.capturePartial(root);
        // This task starts from an actual persisted reservation, with its real writer proof.
        var old=new WorkerJdbcStore(nativeState,records,"fictional-owner",()->false,id->id.equals(root)&&f.service.readyPartial(id,proof),clock);
        identity=db.writes.execute(claimAction(),old::committedClaim,c->{var p=nativeState.candidates(clock.instant()).stream().filter(row->row.root().equals(root)).findFirst().orElseThrow();return old.reserve(new Candidate(p.root(),p.attempt(),p.priority(),p.eligible(),p.rootState(),p.attemptState(),true,p.calls(),p.maximum(),p.request(),p.inputHash(),p.revision()),"fictional-hint",clock.instant().plusSeconds(150),clock.instant(),c.actionKey());});
        store=provider(f.service);worker=new WorkerAuthorizationService(db.writes,store,clock,n->0,()->true);
    }
    WorkerJdbcStore provider(DispatchReadinessService readiness){return new WorkerJdbcStore(nativeState,records,"fictional-owner",()->false,readiness,clock);}
    Permit replay(){var claim=worker.claim(claimAction());assertEquals(State.CLAIMED,claim.state());assertEquals(identity,claim.permit().identity());return claim.permit();}
    Authorization authorize(Store scope,Identity id){return db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-direct-partial-authorize",id.rootId(),id.inputHash()),k->null,c->scope.authorize(id,clock.instant(),c.actionKey()));}
    @Test void OriginalPartialInputCapturesAgainAndAuthorizesOneSendAcrossRestart()throws Exception{
        setup();AtomicInteger sends=new AtomicInteger();var permit=replay();assertEquals(State.SENT,worker.dispatch(permit,json->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(CanonicalJson.parse(identity.frozenRequestJson()),CanonicalJson.parse(json));sends.incrementAndGet();return "fictional-result";}).state());assertEquals(1,sends.get());assertTrue(nativeState.authorized(identity.attemptId()));assertEquals(1,records.byId(identity.rootId()).callCount());assertEquals(1,db.count("target_type='worker_claim'"));assertEquals(1,db.count("target_type='worker_send'"));worker=new WorkerAuthorizationService(db.writes,provider(f.service),clock,n->0,()->true);assertEquals(State.UNKNOWN,worker.dispatch(replay(),json->{sends.incrementAndGet();return "must not send";}).state());assertEquals(1,sends.get());assertEquals("occupied",nativeState.slot().state());
    }
    @Test void NativeP1ChangeInsideWriterRollsBackAndCurrentMemberChangeNeverSends()throws Exception{
        setup();String p1=f.q.q.a.f.plan.chunks().getLast().rootId();var permit=spy(f.q.q.a.service);doAnswer(c->{db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);return c.callRealMethod();}).when(permit).verify(any(),any());store=provider(f.provider(permit));var scope=store.captureAuthorization(identity);assertThrows(RuntimeException.class,()->authorize(scope,identity));assertFalse(nativeState.authorized(identity.attemptId()));assertEquals(0,db.count("target_type='worker_send'"));store=provider(f.service);assertNotNull(store.captureAuthorization(identity));String member=f.plan.chunks().getFirst().itemIds().getFirst();db.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id=?",member);worker=new WorkerAuthorizationService(db.writes,store,clock,n->0,()->true);assertEquals(State.NOT_SENT,worker.dispatch(replay(),json->{fail("changed member cannot send");return null;}).state());assertEquals(0,records.byId(identity.rootId()).callCount());assertEquals("free",nativeState.slot().state());assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void ScopeIdentityTransactionsDefaultProviderAndCapacityCannotAuthorize()throws Exception{
        setup();Store scope=store.captureAuthorization(identity);assertThrows(RuntimeException.class,()->scope.authorize(identity,clock.instant(),"fictional-outside"));assertThrows(RuntimeException.class,()->f.q.q.h.f.readonly(()->scope.authorize(identity,clock.instant(),"fictional-readonly")));assertThrows(RuntimeException.class,()->f.q.q.h.f.p1.tx(()->store.captureAuthorization(identity)));assertThrows(RuntimeException.class,()->authorize(store,identity));var wrong=new Identity(f.roots.getLast(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());assertThrows(RuntimeException.class,()->authorize(scope,wrong));assertThrows(RuntimeException.class,()->provider(f.provider(null)).captureAuthorization(identity));var capped=spy(f.q.q.a.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).capture(any(),any());assertThrows(GenerationFlowService.CapacityGuarded.class,()->provider(f.provider(capped)).captureAuthorization(identity));assertThrows(RuntimeException.class,()->scope.candidates(clock.instant()));assertEquals(1,records.byId(identity.rootId()).callCount());assertFalse(nativeState.authorized(identity.attemptId()));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void SendReceiptFailureRollsBackAuthorizationWithoutRefundAndFreshReplayCanRecover()throws Exception{
        setup();db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_partial_send_receipt CHECK(target_type IS NULL OR target_type<>'worker_send')");assertEquals(State.UNKNOWN,worker.dispatch(replay(),json->{fail("failed receipt cannot send");return null;}).state());assertFalse(nativeState.authorized(identity.attemptId()));assertEquals(1,records.byId(identity.rootId()).callCount());assertEquals("occupied",nativeState.slot().state());assertEquals(0,db.count("target_type='worker_not_sent'"));db.jdbc.execute("ALTER TABLE jobs DROP "+(db.source instanceof org.h2.jdbcx.JdbcDataSource?"CONSTRAINT ":"CHECK ")+"fictional_partial_send_receipt");worker=new WorkerAuthorizationService(db.writes,provider(f.service),clock,n->0,()->true);assertEquals(State.SENT,worker.dispatch(replay(),json->"fictional-recovered").state());assertEquals(1,db.count("target_type='worker_send'"));assertEquals(1,records.byId(identity.rootId()).callCount());
    }
    @Test void LostAuthorizationAckRetainsBudgetAndReceiptWithoutAnyProviderSend()throws Exception{
        setup();var manager=new DataSourceTransactionManager(db.source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional partial ACK loss",new java.sql.SQLException("fictional lost ACK","08006"));}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(db.source),manager,()->25,n->{});worker=new WorkerAuthorizationService(writes,store,clock,n->0,()->true);var permit=replay();assertEquals(State.UNKNOWN,worker.dispatch(permit,json->{fail("uncertain authorization cannot send");return null;}).state());assertTrue(nativeState.authorized(identity.attemptId()));assertEquals(1,db.count("target_type='worker_send'"));assertEquals(1,records.byId(identity.rootId()).callCount());worker=new WorkerAuthorizationService(db.writes,provider(f.service),clock,n->0,()->true);assertEquals(State.UNKNOWN,worker.dispatch(replay(),json->{fail("durable send receipt cannot repeat");return null;}).state());assertEquals("occupied",nativeState.slot().state());
    }
}
