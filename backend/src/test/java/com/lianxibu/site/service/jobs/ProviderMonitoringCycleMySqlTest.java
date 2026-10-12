package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.*;import com.lianxibu.site.repository.jobs.*;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class ProviderMonitoringCycleMySqlTest {
 @Test void nativeUnknownReleaseAndConfigurationFailureDrivePermanentMysqlState()throws Exception{
  var f=new WorkerNativeSnapshotMySqlTest();f.setup();try{
   f.f.db.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.f.db.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
   var clock=new OperationalPersistenceFixture.MutableClock();clock.now=f.now;var records=new JdbcJobRecords(f.f.db.source);var calls=new JdbcProviderCallTelemetry(f.f.db.source,f.f.h.newsFlow.day.manager,clock);var store=new OperationalJdbcStore(new JdbcOperationalSummaries(f.f.db.source),records,clock);var config=(OperationalMonitoringService.ConfigurationSource)()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete());var monitor=new OperationalMonitoringService(store,config,f.f.db.writes,clock);
   java.util.function.Supplier<ProviderMonitoringCycle> cycle=()->{var value=new ProviderMonitoringCycle(ProviderMonitoringCycleTest.KEY,()->new ProviderReadinessTelemetry.Evidence(clock.now,ProviderReadinessTelemetry.Status.READY),calls,new ProviderEvidence(),store,monitor,config,clock);value.start();return value;};
   assertEquals(ProviderMonitoringCycle.Outcome.SAMPLED,cycle.get().tick());assertEquals(Status.NORMAL,store.read(ProviderMonitoringCycleTest.KEY).status());
   var claim=f.f.worker.claim(new SharedWriteExecutor.ActionContext("fictional-native-snapshot-claim",f.f.prepared.input().ownerId(),f.f.prepared.input().inputHash()));var id=claim.permit().identity();assertEquals(WorkerAuthorizationService.State.SENT,f.f.worker.dispatch(claim.permit(),request->"fictional-configuration-failure").state());new AttemptFailureService(f.f.db.writes,new JdbcAttemptFailure(f.f.db.source),f.f.clock,f.rows.attempt(id.attemptId()).owner()).fail(id,AttemptFailureService.Failure.CONFIGURATION,null,new SharedWriteExecutor.ActionContext("fictional-provider-monitor-failure",id.attemptId(),null));
   clock.now=clock.now.plusSeconds(60);cycle.get().tick();assertEquals(java.util.Set.of(Guard.DISPATCH_AI),store.read(ProviderMonitoringCycleTest.KEY).guards());assertEquals(ProviderMonitoringCycle.Outcome.ALREADY_SAMPLED,cycle.get().tick());assertEquals("free",f.state.slot().state());assertEquals(1,records.byId(f.f.prepared.input().ownerId()).callCount());assertEquals(2,f.f.h.newsFlow.sends);
   f.f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim' AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.attempt_id'))=?",new byte[32],id.attemptId());clock.now=clock.now.plusSeconds(60);cycle.get().tick();assertEquals(Status.TELEMETRY_UNKNOWN,store.read(ProviderMonitoringCycleTest.KEY).status());assertFalse(store.read(ProviderMonitoringCycleTest.KEY).guards().isEmpty());
  }finally{f.restore();}
 }
}
