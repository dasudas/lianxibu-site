package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerRecoveryMonitorMySqlTest {
 WorkerNativeSnapshotMySqlTest f;WorkerRecoveryMonitor monitor;
 @BeforeEach void setup()throws Exception{f=new WorkerNativeSnapshotMySqlTest();f.setup();var store=new UnknownCallJdbcStore(f.state,f.rows,new JdbcJobRecords(f.f.db.source),"fictional-monitor",e->true);var sweep=new WorkerRecoverySweep(f.rows,store,new UnknownCallRecoveryService(f.f.db.writes,store,f.f.clock),f.f.clock);monitor=new WorkerRecoveryMonitor(sweep,()->f.read(f.now),f.f.clock);}
 @AfterEach void restore(){f.restore();}
 @Test void actualRecoveryCommitAndCompleteOriginalSnapshotPublishOneRealFact(){assertNull(monitor.read());assertEquals(1,monitor.scan().recovered());var fact=monitor.read();assertNotNull(fact);assertTrue(fact.scanSuccessful());assertTrue(fact.snapshot().slotFree());assertEquals(0,fact.snapshot().runningAttempts());assertEquals(1,new JdbcJobRecords(f.f.db.source).byId(f.f.prepared.input().ownerId()).callCount());assertEquals(2,f.f.h.newsFlow.sends);assertEquals(0,monitor.scan().scanned());assertEquals(1,f.f.db.count("target_type='worker_recovery'"));}
 @Test void actualRollbackFailuresCannotPublishSuccessOrReleaseTheSlot(){f.f.db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_monitor_recovery_fail CHECK(target_type IS NULL OR target_type<>'worker_recovery')");var slot=f.state.slot();assertEquals(1,monitor.scan().failed());assertFalse(monitor.read().scanSuccessful());assertEquals(1,monitor.read().consecutiveFailedScans());assertEquals(slot,f.state.slot());assertEquals(1,monitor.scan().failed());assertEquals(2,monitor.read().consecutiveFailedScans());assertEquals(0,f.f.db.count("target_type='worker_recovery'"));f.f.db.jdbc.execute("ALTER TABLE jobs DROP CHECK fictional_monitor_recovery_fail");assertEquals(1,monitor.scan().recovered());assertTrue(monitor.read().scanSuccessful());assertEquals(0,monitor.read().consecutiveFailedScans());assertEquals(2,f.f.h.newsFlow.sends);}
}
