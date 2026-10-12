package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;

class AttemptFailureServiceTest {
    ResultJdbcStoreTest f;
    WorkerLeaseServiceTest.MutableClock clock;
    AttemptFailureService service;
    @BeforeEach void setup(){
        f=new ResultJdbcStoreTest();f.fixture();f.jdbc.execute("ALTER TABLE jobs ADD error_code VARCHAR(64)");f.jdbc.execute("ALTER TABLE jobs ADD retry_of_job_id VARCHAR(64)");
        clock=new WorkerLeaseServiceTest.MutableClock(f.clock.instant());
        service=new AttemptFailureService(f.writes,new JdbcAttemptFailure(f.source),clock,"fictional-epoch");
    }
    SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,"fictional-failure",null);}
    WorkerAuthorizationService worker(){return new WorkerAuthorizationService(f.writes,new WorkerJdbcStore(new JdbcWorkerState(f.source),new JdbcJobRecords(f.source),"fictional-epoch",()->false,id->true,clock),clock,()->true);}
    @Test void threeFailuresConsumeExactlyThreeCallsAndFreezeIdenticalRetries() {
        var identity=f.identity;String original=identity.frozenRequestJson();
        for(int n=1;n<=3;n++) {
            var result=service.fail(identity,AttemptFailureService.Failure.TRANSIENT,0,action("failure-"+n));
            assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(n,new JdbcJobRecords(f.source).byId(identity.rootId()).callCount());
            var initial=identity;
            assertEquals(result,service.fail(initial,AttemptFailureService.Failure.TRANSIENT,0,action("failure-"+n)));
            if(n==3) {assertEquals("failed",result.state());assertNull(result.nextAttemptId());assertNull(result.eligibleAt());break;}
            assertEquals("waiting_retry",result.state());assertEquals(original,new JdbcJobRecords(f.source).byId(result.nextAttemptId()).requestJson());
            assertEquals(identity.attemptId(),f.jdbc.queryForObject("SELECT retry_of_job_id FROM jobs WHERE id=?",String.class,result.nextAttemptId()));
            var w=worker();assertEquals(WorkerAuthorizationService.State.IDLE,w.claim(action("early-"+n)).state());
            clock.now=result.eligibleAt();var next=w.claim(action("retry-"+n));assertEquals(WorkerAuthorizationService.State.CLAIMED,next.state());
            assertEquals(WorkerAuthorizationService.State.SENT,w.dispatch(next.permit(),request->{f.sends++;return "fictional-failure";}).state());
            identity=next.permit().identity();
        }
        assertEquals(3,f.sends);assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
        assertEquals("retry_exhausted",f.jdbc.queryForObject("SELECT error_code FROM jobs WHERE id=?",String.class,identity.rootId()));
        assertEquals(WorkerAuthorizationService.State.IDLE,worker().claim(action("no-fourth")).state());
    }
    @Test void configurationFailureBlocksAndMismatchedReplayDoesNotClearANewSlot() {
        var result=service.fail(f.identity,AttemptFailureService.Failure.CONFIGURATION,null,action("configuration"));
        assertEquals("blocked_configuration",result.state());assertNull(result.nextAttemptId());
        f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new',holder='fictional-new',slot_revision=slot_revision+1,fencing_token=fencing_token+1");
        var slot=new JdbcWorkerState(f.source).slot();assertEquals(result,service.fail(f.identity,AttemptFailureService.Failure.CONFIGURATION,null,action("configuration")));assertEquals(slot,new JdbcWorkerState(f.source).slot());
        assertThrows(RuntimeException.class,()->service.fail(f.identity,AttemptFailureService.Failure.TRANSIENT,null,action("configuration")));
        assertThrows(RuntimeException.class,()->service.fail(f.identity,AttemptFailureService.Failure.CONFIGURATION,null,action("new-action")));
    }
    @Test void failedReceiptRollsBackAttemptRootAndSlot() {
        var before=new JdbcWorkerState(f.source).slot();
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_failure_receipt CHECK(target_type IS NULL OR target_type<>'attempt_failure')");
        assertThrows(RuntimeException.class,()->service.fail(f.identity,AttemptFailureService.Failure.INVALID_RESULT,0,action("failure-rollback")));
        assertEquals(before,new JdbcWorkerState(f.source).slot());assertEquals("running",new JdbcJobRecords(f.source).byId(f.identity.rootId()).state());
        assertEquals("running",new JdbcJobRecords(f.source).byId(f.identity.attemptId()).state());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
    @Test void delayHonorsServerAndNextWindowAndNewGenerationDoesNotRetryOldWork() {
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"),AttemptFailureService.nextEligible(Instant.parse("2026-01-01T22:59:40Z"),1,0));
        assertEquals(Instant.parse("2026-01-01T21:02:00Z"),AttemptFailureService.nextEligible(Instant.parse("2026-01-01T21:00:00Z"),2,120));
        f.jdbc.update("UPDATE jobs SET generation=2 WHERE record_kind='generation_intent'");
        var result=service.fail(f.identity,AttemptFailureService.Failure.TRANSIENT,0,action("superseded"));assertEquals("superseded",result.state());assertNull(result.nextAttemptId());assertEquals("free",new JdbcWorkerState(f.source).slot().state());
    }
}
