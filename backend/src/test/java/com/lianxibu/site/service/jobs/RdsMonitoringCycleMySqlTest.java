package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class RdsMonitoringCycleMySqlTest {
    @Test void realMysqlRestartReplayAndUnknownKeepWatermarkAndGuard() {
        try(var f=new PooledResultJdbcMySqlFixture()) {
            f.fixture();f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
            var clock=new OperationalPersistenceFixture.MutableClock();var calls=new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Supplier<RdsMonitoringCycle> factory=()->{
                var store=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
                var monitor=new OperationalMonitoringService(store,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,clock);
                return new RdsMonitoringCycle((r,t)->{calls.incrementAndGet();assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return calls.get()==1?RdsMonitoringCycleTest.evidence(clock.now):null;},new RdsCapacityEvidence(),RdsMonitoringCycleTest.RESOURCE,RdsMonitoringCycleTest.CAPACITY,RdsMonitoringCycleTest.TELEMETRY,store,monitor,clock);
            };
            f.tx.ackLost=true;assertEquals(RdsMonitoringCycle.Outcome.SAMPLED,factory.get().tick());
            var scope=new OperationalAdmissionService.Scopes(java.util.List.of(RdsMonitoringCycleTest.CAPACITY,RdsMonitoringCycleTest.TELEMETRY),java.util.List.of(new Key("OPS-03","fixture","fictional-worker"),new Key("OPS-06","fixture","fictional-worker"),new Key("OPS-08","fixture","fictional-worker")),java.util.Map.of());
            var summaries=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            var gate=new OperationalAdmissionService(()->new OperationalAdmissionService.Snapshot(new com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalConfiguration(1,MonitoringFixtures.complete(),true),summaries.views().stream().map(OperationalJdbcStore.View::state).toList()),scope,clock);
            assertTrue(gate.check(OperationalAdmissionService.Operation.UPLOAD_OBSERVATION,null).allowed());
            assertEquals(RdsMonitoringCycle.Outcome.ALREADY_SAMPLED,factory.get().tick());assertEquals(1,calls.get());
            clock.now=clock.now.plusSeconds(660);factory.get().tick();
            var reloaded=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            assertEquals(Status.TELEMETRY_UNKNOWN,reloaded.read(RdsMonitoringCycleTest.CAPACITY).status());assertEquals(4,reloaded.read(RdsMonitoringCycleTest.TELEMETRY).guards().size());
            assertEquals(clock.now.minusSeconds(660).getEpochSecond(),reloaded.read(RdsMonitoringCycleTest.CAPACITY).counters().get("capacity_verified_at"));
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_rule'",Integer.class));
            assertEquals(OperationalAdmissionService.Reason.CAPACITY_GUARD,gate.check(OperationalAdmissionService.Operation.UPLOAD_OBSERVATION,null).reason());
        }
    }
}
