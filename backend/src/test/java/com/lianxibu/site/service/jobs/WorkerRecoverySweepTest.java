package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;

class WorkerRecoverySweepTest {
    TransportUnknownServiceTest fixture;ResultJdbcStoreTest f;WorkerLeaseServiceTest.MutableClock clock;
    @BeforeEach void setup(){fixture=new TransportUnknownServiceTest();fixture.setup();f=fixture.fixture;clock=new WorkerLeaseServiceTest.MutableClock(f.clock.instant());}
    WorkerRecoverySweep sweep(Predicate<String> stopped) {
        var state=new JdbcWorkerState(f.source);var query=new JdbcWorkerRecovery(f.source,state);
        var store=new UnknownCallJdbcStore(state,query,new JdbcJobRecords(f.source),"fictional-recovery",stopped);
        return new WorkerRecoverySweep(query,store,new UnknownCallRecoveryService(f.writes,store,clock),clock);
    }
    @Test void leavesLiveHolderThenRecoversOnlyAfterOriginalDeadline() {
        var service=sweep(epoch->false);var before=new JdbcWorkerState(f.source).slot();
        var live=service.scan();assertEquals(1,live.pending());assertEquals(0,live.recovered());assertEquals(before,new JdbcWorkerState(f.source).slot());
        clock.now=clock.now.plusSeconds(90);var expired=service.scan();assertEquals(1,expired.recovered());assertEquals(0,expired.failed());
        assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());
        assertEquals(0,service.scan().scanned());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_recovery'",Integer.class));
    }
    @Test void trustedStopProofRecoversEarlyButDoesNotReleaseANewerHolder() {
        f.jdbc.update("UPDATE worker_lock SET job_id='fictional-new',holder='fictional-new-epoch',fencing_token=fencing_token+1,slot_revision=slot_revision+1");
        var current=new JdbcWorkerState(f.source).slot();var result=sweep("fictional-epoch"::equals).scan();
        assertEquals(1,result.recovered());assertEquals(0,result.failed());assertEquals(current,new JdbcWorkerState(f.source).slot());
        assertEquals("unknown_external",new JdbcJobRecords(f.source).byId(f.identity.rootId()).state());
    }
    @Test void corruptProofAndReceiptFailureAreNotReportedAsRecovered() {
        clock.now=clock.now.plusSeconds(90);var before=new JdbcWorkerState(f.source).slot();
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_recovery_fail CHECK(target_type IS NULL OR target_type<>'worker_recovery')");
        var failed=sweep(epoch->false).scan();assertEquals(1,failed.failed());assertEquals(0,failed.recovered());assertEquals(before,new JdbcWorkerState(f.source).slot());
        f.jdbc.execute("ALTER TABLE jobs DROP CONSTRAINT fictional_recovery_fail");
        f.jdbc.update("DELETE FROM jobs WHERE target_type='worker_claim'");
        failed=sweep(epoch->false).scan();assertEquals(1,failed.failed());assertEquals(0,failed.recovered());assertEquals(before,new JdbcWorkerState(f.source).slot());
    }
}
