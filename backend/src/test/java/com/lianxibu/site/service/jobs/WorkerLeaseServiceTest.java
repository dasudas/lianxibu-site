package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;

class WorkerLeaseServiceTest {
    ResultJdbcStoreTest fixture;
    MutableClock clock;
    WorkerLeaseService service;
    @BeforeEach void setup() {
        fixture=new ResultJdbcStoreTest();fixture.fixture();clock=new MutableClock(fixture.clock.instant());
        service=new WorkerLeaseService(fixture.writes,new JdbcWorkerLease(fixture.source),clock,"fictional-epoch");
    }
    SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,"fictional-heartbeat",null);}

    @Test void renewalAndDurableReplayPreserveFencingBudgetAndOriginalClaim() {
        var worker=new JdbcWorkerState(fixture.source);var original=worker.slot();
        clock.now=clock.now.plusSeconds(20);
        Instant renewed=service.renew(fixture.identity,action("heartbeat-one"));
        assertEquals(clock.now.plusSeconds(150),renewed);
        assertEquals(original.revision(),worker.slot().revision());assertEquals(original.fencing(),worker.slot().fencing());
        assertEquals(1,new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).callCount());
        clock.now=clock.now.plusSeconds(20);
        var restarted=new WorkerLeaseService(fixture.writes,new JdbcWorkerLease(fixture.source),clock,"fictional-epoch");
        assertEquals(renewed,restarted.renew(fixture.identity,action("heartbeat-one")));assertEquals(renewed,worker.slot().lease());
        assertTrue(restarted.renew(fixture.identity,action("heartbeat-two")).isAfter(renewed));
        fixture.apply();assertEquals("free",worker.slot().state());
        assertThrows(RuntimeException.class,()->service.renew(fixture.identity,action("after-complete")));
        assertEquals(1,fixture.sends);
    }
    @Test void expiredDeadlineWrongProcessAndReplacedSlotCannotRenew() {
        var before=new JdbcWorkerState(fixture.source).slot();
        var wrong=new WorkerLeaseService(fixture.writes,new JdbcWorkerLease(fixture.source),clock,"fictional-other");
        assertThrows(RuntimeException.class,()->wrong.renew(fixture.identity,action("wrong-process")));
        clock.now=clock.now.plusSeconds(90);
        assertThrows(RuntimeException.class,()->service.renew(fixture.identity,action("deadline")));
        clock.now=fixture.clock.instant().plusSeconds(20);
        fixture.jdbc.update("UPDATE worker_lock SET fencing_token=fencing_token+1");
        assertThrows(RuntimeException.class,()->service.renew(fixture.identity,action("replaced")));
        assertEquals(before.lease(),new JdbcWorkerState(fixture.source).slot().lease());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_heartbeat'",Integer.class));
    }
    @Test void missingSendAuthorizationAndReceiptFailureRollBackTheLease() {
        var before=new JdbcWorkerState(fixture.source).slot();clock.now=clock.now.plusSeconds(20);
        fixture.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",fixture.identity.attemptId());
        assertThrows(RuntimeException.class,()->service.renew(fixture.identity,action("unsent")));
        fixture.jdbc.update("UPDATE jobs SET send_authorized_at=? WHERE id=?",java.sql.Timestamp.from(fixture.clock.instant()),fixture.identity.attemptId());
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_heartbeat_fail CHECK(target_type IS NULL OR target_type<>'worker_heartbeat')");
        assertThrows(RuntimeException.class,()->service.renew(fixture.identity,action("receipt-fails")));
        assertEquals(before,new JdbcWorkerState(fixture.source).slot());
    }
    static class MutableClock extends Clock {
        Instant now;MutableClock(Instant now){this.now=now;}
        public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
}
