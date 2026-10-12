package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

public class TransportUnknownServiceTest {
    ResultJdbcStoreTest fixture;
    TransportUnknownService service;
    @BeforeEach void setup() {
        fixture=new ResultJdbcStoreTest();fixture.fixture();
        fixture.jdbc.execute("ALTER TABLE jobs ADD error_code VARCHAR(64)");
        fixture.jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");
        fixture.jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");
        service=service("fictional-epoch");
    }
    TransportUnknownService service(String owner) {
        return new TransportUnknownService(fixture.writes,new JdbcTransportUnknown(fixture.source),fixture.clock,owner);
    }
    SharedWriteExecutor.ActionContext action(String key) {return fixture.action(key);}
    public static String extract(String json,String path) {
        return com.lianxibu.site.domain.protocol.CanonicalJson.parse(json).path(path.substring(2)).toString();
    }
    public static String unquote(String json) {return com.lianxibu.site.domain.protocol.CanonicalJson.parse(json).asText();}
    @Test void immediateReleaseKeepsBudgetAndReplayCannotReleaseANewerSlot() {
        fixture.tx.ackLost=true;
        var result=service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("transport-ended"));
        assertTrue(result.slotReleased());assertEquals(1,result.callCount());
        assertEquals("unknown_external",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals("unknown_external",new JdbcJobRecords(fixture.source).byId(fixture.identity.attemptId()).state());
        assertEquals("free",new JdbcWorkerState(fixture.source).slot().state());
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
        fixture.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new',holder='fictional-new-epoch'");
        var newer=new JdbcWorkerState(fixture.source).slot();
        assertEquals(result,service("fictional-epoch").release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("transport-ended")));
        assertEquals(newer,new JdbcWorkerState(fixture.source).slot());
        assertThrows(RuntimeException.class,()->service.release(fixture.identity,TransportUnknownService.Cause.LOCAL_CANCELLED,action("transport-ended")));
        assertThrows(RuntimeException.class,fixture::apply);
    }
    @Test void oldAttemptCanBeClosedAfterLosingItsSlotWithoutTouchingTheNewOccupant() {
        fixture.jdbc.update("UPDATE worker_lock SET job_id='fictional-new',holder='fictional-new-epoch',slot_revision=slot_revision+1,fencing_token=fencing_token+1");
        var newer=new JdbcWorkerState(fixture.source).slot();
        var result=service.release(fixture.identity,TransportUnknownService.Cause.LOCAL_CANCELLED,action("local-ended"));
        assertFalse(result.slotReleased());assertEquals(newer,new JdbcWorkerState(fixture.source).slot());
        assertEquals(1,result.callCount());
    }
    @Test void wrongProcessUnsentAndAlreadyCompletedReject() {
        assertThrows(RuntimeException.class,()->service("fictional-other").release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("wrong-owner")));
        fixture.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",fixture.identity.attemptId());
        assertThrows(RuntimeException.class,()->service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("unsent")));
        fixture.jdbc.update("UPDATE jobs SET send_authorized_at=? WHERE id=?",java.sql.Timestamp.from(fixture.clock.instant()),fixture.identity.attemptId());
        fixture.apply();
        assertThrows(RuntimeException.class,()->service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("completed")));
    }
    @Test void receiptFailureRollsBackAndTamperedReceiptRejects() {
        var before=new JdbcWorkerState(fixture.source).slot();
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_transport_fail CHECK(target_type IS NULL OR target_type<>'transport_unknown')");
        assertThrows(RuntimeException.class,()->service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("rollback")));
        assertEquals("running",new JdbcJobRecords(fixture.source).byId(fixture.identity.rootId()).state());
        assertEquals(before,new JdbcWorkerState(fixture.source).slot());
        fixture.jdbc.execute("ALTER TABLE jobs DROP CONSTRAINT fictional_transport_fail");
        service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("recorded"));
        fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='transport_unknown'",new byte[32]);
        assertThrows(RuntimeException.class,()->service.release(fixture.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action("recorded")));
    }
}
