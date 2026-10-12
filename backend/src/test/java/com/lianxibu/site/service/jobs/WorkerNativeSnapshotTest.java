package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry.Snapshot;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
class WorkerNativeSnapshotTest {
 TransportUnknownServiceTest fixture;ResultJdbcStoreTest f;JdbcWorkerRecovery rows;
 @BeforeEach void setup(){fixture=new TransportUnknownServiceTest();fixture.setup();f=fixture.fixture;rows=new JdbcWorkerRecovery(f.source,new JdbcWorkerState(f.source));}
 Snapshot read(Instant now){var tx=new TransactionTemplate(f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->rows.operationalSnapshot(now));}
 @Test void actualOriginalClaimBindsLiveSlotAndNativeDeadlineWithoutWriting(){var before=new JdbcWorkerState(f.source).slot();assertEquals(new Snapshot(0,1,false),read(f.clock.instant()));assertEquals(new Snapshot(10,1,false),read(f.clock.instant().plusSeconds(100)));assertEquals(before,new JdbcWorkerState(f.source).slot());assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());}
 @Test void actualRecoveryProducesVerifiedFreeScanWithoutRefundOrResend(){var state=new JdbcWorkerState(f.source);var store=new UnknownCallJdbcStore(state,rows,new JdbcJobRecords(f.source),"fictional-scan",e->true);var sweep=new WorkerRecoverySweep(rows,store,new UnknownCallRecoveryService(f.writes,store,f.clock),f.clock);assertEquals(1,sweep.scan().recovered());assertEquals(new Snapshot(0,0,true),read(f.clock.instant()));assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());}
 @Test void missingDamagedOrReplacedOriginalProofNeverBecomesHealthy(){f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,()->read(f.clock.instant()));}
 @Test void foreignHolderAndOrphanRunningRowsAreUnavailable(){f.jdbc.update("UPDATE worker_lock SET holder='fictional-foreign-holder'");assertThrows(RuntimeException.class,()->read(f.clock.instant()));f.jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,occupied_since=NULL");assertThrows(RuntimeException.class,()->read(f.clock.instant()));}
 @Test void malformedFreeSlotAndWrongTransactionCannotSupplyEvidence(){assertThrows(RuntimeException.class,()->rows.operationalSnapshot(f.clock.instant()));var tx=new TransactionTemplate(f.tx);assertThrows(RuntimeException.class,()->tx.execute(s->rows.operationalSnapshot(f.clock.instant())));f.jdbc.update("UPDATE worker_lock SET slot_state='free'");assertThrows(RuntimeException.class,()->read(f.clock.instant()));}
}
