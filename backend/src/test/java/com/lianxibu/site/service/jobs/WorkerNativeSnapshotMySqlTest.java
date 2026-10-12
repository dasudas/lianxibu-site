package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry.Snapshot;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerNativeSnapshotMySqlTest {
 NewsNativeDailyPublicMySqlTest f;JdbcWorkerState state;JdbcWorkerRecovery rows;Instant now;
 @BeforeEach void setup()throws Exception{f=new NewsNativeDailyPublicMySqlTest();f.utc();f.setup();state=new JdbcWorkerState(f.db.source);assertEquals(WorkerAuthorizationService.State.CLAIMED,f.worker.claim(new SharedWriteExecutor.ActionContext("fictional-native-snapshot-claim",f.prepared.input().ownerId(),f.prepared.input().inputHash())).state());rows=new JdbcWorkerRecovery(f.db.source,state);now=f.clock.instant();}
 @AfterEach void restore(){f.restore();}
 Snapshot read(Instant time){var tx=new TransactionTemplate(f.h.newsFlow.day.manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->rows.operationalSnapshot(time));}
 @Test void originalMysqlJsonClaimAndMicrosGiveExactDeadlineAndNoMutation(){var before=state.slot();assertEquals(new Snapshot(0,1,false),read(now));assertEquals(new Snapshot(10,1,false),read(now.plusSeconds(100)));assertEquals(before,state.slot());assertEquals(1,new JdbcJobRecords(f.db.source).byId(f.prepared.input().ownerId()).callCount());assertEquals(2,f.h.newsFlow.sends);}
 @Test void actualRecoveryAndFreshReadonlyScanProveFreeSlot(){var store=new UnknownCallJdbcStore(state,rows,new JdbcJobRecords(f.db.source),"fictional-scan",e->true);var sweep=new WorkerRecoverySweep(rows,store,new UnknownCallRecoveryService(f.db.writes,store,f.clock),f.clock);assertEquals(1,sweep.scan().recovered());assertEquals(new Snapshot(0,0,true),read(now));assertEquals(1,new JdbcJobRecords(f.db.source).byId(f.prepared.input().ownerId()).callCount());assertEquals(2,f.h.newsFlow.sends);}
 @Test void missingOriginalProofOrForeignSlotIsUnknownWithoutRelease(){var before=state.slot();f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim' AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.attempt_id'))=?",new byte[32],before.attempt());assertThrows(RuntimeException.class,()->read(now));assertEquals(before,state.slot());}
 @Test void partialOldFixtureWithUnprovenRunningRowCannotBeACompleteHealthyScan()throws Exception{var partial=new SourceDayWorkerIntegrationMySqlTest();partial.setup();assertEquals(WorkerAuthorizationService.State.CLAIMED,partial.worker.claim(partial.action()).state());var reader=new JdbcWorkerRecovery(partial.db.source,partial.state);var tx=new TransactionTemplate(partial.f.day.manager);tx.setReadOnly(true);assertThrows(RuntimeException.class,()->tx.execute(s->reader.operationalSnapshot(partial.f.f.clock.instant())));}
}
