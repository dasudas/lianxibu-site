package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerRecoveryMonitoringCycleMySqlTest {
 @Test void actualNativeRollbackScansAndRestoredRecoveryDrivePermanentGuard()throws Exception{
  var f=new WorkerNativeSnapshotMySqlTest();f.setup();try{
   f.f.db.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.db.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
   var clock=new OperationalPersistenceFixture.MutableClock();clock.now=f.now.plusSeconds(180);var records=new JdbcJobRecords(f.f.db.source);var recoveryStore=new UnknownCallJdbcStore(f.state,f.rows,records,"fictional-actual-monitor",e->false);
   var sweep=new WorkerRecoverySweep(f.rows,recoveryStore,new UnknownCallRecoveryService(f.f.db.writes,recoveryStore,clock),clock);
   var facts=new WorkerRecoveryMonitor(sweep,()->{var tx=new TransactionTemplate(f.f.h.newsFlow.day.manager);tx.setReadOnly(true);return tx.execute(s->f.rows.operationalSnapshot(clock.now));},clock);
   var store=new OperationalJdbcStore(new JdbcOperationalSummaries(f.f.db.source),records,clock);var config=(OperationalMonitoringService.ConfigurationSource)()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete());var monitor=new OperationalMonitoringService(store,config,f.f.db.writes,clock);
   java.util.function.Supplier<WorkerRecoveryMonitoringCycle> cycle=()->{var value=new WorkerRecoveryMonitoringCycle(WorkerRecoveryMonitoringCycleTest.KEY,facts,new WorkerRecoveryEvidence(),store,monitor,config,clock);value.start();return value;};
   f.f.db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_ops_scan_fail CHECK(target_type IS NULL OR target_type<>'worker_recovery')");
   for(int n=1;n<=3;n++){assertEquals(1,facts.scan().failed());cycle.get().tick();assertEquals(n,facts.read().consecutiveFailedScans());if(n<3)assertEquals(Status.TELEMETRY_UNKNOWN,store.read(WorkerRecoveryMonitoringCycleTest.KEY).status());if(n<3)clock.now=clock.now.plusSeconds(60);}
   assertEquals(java.util.Set.of(Guard.DISPATCH_AI),store.read(WorkerRecoveryMonitoringCycleTest.KEY).guards());assertEquals("occupied",f.state.slot().state());assertEquals(0,f.f.db.count("target_type='worker_recovery'"));assertEquals(WorkerRecoveryMonitoringCycle.Outcome.ALREADY_SAMPLED,cycle.get().tick());
   f.f.db.jdbc.execute("ALTER TABLE jobs DROP CHECK fictional_ops_scan_fail");assertEquals(1,facts.scan().recovered());assertEquals("free",f.state.slot().state());
   for(int n=0;n<4;n++){clock.now=clock.now.plusSeconds(60);facts.scan();cycle.get().tick();if(n<3)assertFalse(store.read(WorkerRecoveryMonitoringCycleTest.KEY).guards().isEmpty());}
   assertTrue(store.read(WorkerRecoveryMonitoringCycleTest.KEY).guards().isEmpty());assertEquals(1,new JdbcJobRecords(f.f.db.source).byId(f.f.prepared.input().ownerId()).callCount());assertEquals(2,f.f.h.newsFlow.sends);assertEquals(1,f.f.db.count("target_type='worker_recovery'"));
  }finally{f.restore();}
 }
}
