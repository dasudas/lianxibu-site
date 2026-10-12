package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class JavaMemoryMonitoringCycleMySqlTest {
    static final Key MEMORY=new Key("OPS-06","java","fictional-java"), CAPACITY=new Key("OPS-04","database","fictional-database"),
            HEALTH=new Key("OPS-10","monitor","fictional-monitor"), RECOVERY=new Key("OPS-03","worker","fictional-worker"), PROVIDER=new Key("OPS-08","provider","fictional-provider");
    static final long MIB=JavaMemoryEvidenceTest.MIB;
    static final Instant BIRTH=OperationalRuleEngineTest.START.minusSeconds(120);
    static final class Context implements AutoCloseable {
        final PooledResultJdbcMySqlFixture f=new PooledResultJdbcMySqlFixture();
        final OperationalPersistenceFixture.MutableClock clock=new OperationalPersistenceFixture.MutableClock();
        final AtomicInteger calls=new AtomicInteger(), heap=new AtomicInteger();boolean absent;
        final OperationalJdbcStore store;final OperationalMonitoringService monitor;
        Context() {
            f.fixture();f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");
            f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
            clock.now=clock.now.plusSeconds(10);store=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            monitor=new OperationalMonitoringService(store,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,clock);
        }
        void outside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();}
        JavaMemoryMonitoringCycle cycle() {
            var reloaded=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            var service=new OperationalMonitoringService(reloaded,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,clock);
            var cycle=new JavaMemoryMonitoringCycle(()->{outside();return absent?null:new JavaMemoryTelemetry.Evidence(clock.now,2048*MIB,900*MIB);},
                    ()->{outside();return new JavaProcessTelemetry.Evidence(1234,BIRTH,clock.now,500*MIB);},
                    ()->{outside();return new JavaCgroupTelemetry.Evidence(1234,BIRTH,clock.now,"a".repeat(64),500*MIB,576*MIB,0,0);},
                    ()->{outside();return new JavaVmMemoryTelemetry.Evidence(1234,BIRTH,clock.now,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null);},
                    ()->{outside();return new JavaVmOomTelemetry.Evidence(1234,BIRTH,clock.now,heap.get(),0,0);},new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),MEMORY,reloaded,service,clock);
            cycle.start();return cycle;
        }
        void minute(int index){clock.now=OperationalRuleEngineTest.START.plusSeconds(index*60L+10);}
        void otherResources() {
            Instant minute=clock.now.truncatedTo(ChronoUnit.MINUTES);
            for(var entry:Map.<Key,Reading>of(CAPACITY,new Capacity(java.math.BigDecimal.valueOf(90),minute),HEALTH,new Telemetry(0,0),
                    RECOVERY,new SlotRecovery(0,0,true),PROVIDER,new Provider(false,0,0,true)).entrySet())
                monitor.sample(entry.getKey(),true,entry.getValue(),minute,new SharedWriteExecutor.ActionContext("fictional-control:"+entry.getKey().ruleId()+":"+minute.getEpochSecond(),"fictional-ops",null));
        }
        OperationalAdmissionService gate() {
            var scopes=new OperationalAdmissionService.Scopes(List.of(CAPACITY,HEALTH),List.of(RECOVERY,MEMORY,PROVIDER),Map.of());
            return new OperationalAdmissionService(()->new OperationalAdmissionService.Snapshot(
                    new com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalConfiguration(1,MonitoringFixtures.complete(),true),
                    store.views().stream().map(OperationalJdbcStore.View::state).toList()),scopes,clock);
        }
        @Override public void close(){f.close();}
    }

    @Test void RealMysqlLostCommitAckTwentyCounterJsonAndRestartKeepOriginalReceipt() {
        try(var context=new Context()) {
            context.heap.set(1);context.f.tx.ackLost=true;assertEquals(JavaMemoryMonitoringCycle.Outcome.SAMPLED,context.cycle().tick());
            var stored=context.store.read(MEMORY);assertEquals(20,stored.counters().size());assertEquals(1L,stored.counters().get("java_vm_heap"));assertEquals(Level.CRITICAL,stored.level());
            assertEquals(1L,context.f.jdbc.queryForObject("SELECT revision FROM runtime_settings WHERE setting_key='ops_summary-v1'",Long.class));
            assertEquals(1,context.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_rule' AND JSON_UNQUOTE(JSON_EXTRACT(action_payload,'$.record_type'))='ops_notice_intent'",Integer.class));
            assertEquals(JavaMemoryMonitoringCycle.Outcome.ALREADY_SAMPLED,context.cycle().tick());assertEquals(5,context.calls.get());assertEquals(stored,context.store.read(MEMORY));
        }
    }

    @Test void ActualNewAiGateBlocksNativeOomAndUnknownUntilFullRecoveryWithFreshOtherResources() {
        try(var context=new Context()) {
            assertFalse(context.gate().check(OperationalAdmissionService.Operation.DISPATCH_AI,null).allowed());
            context.otherResources();context.cycle().tick();assertTrue(context.gate().check(OperationalAdmissionService.Operation.DISPATCH_AI,null).allowed());
            context.minute(1);context.heap.set(1);context.cycle().tick();var prior=context.store.read(MEMORY);
            assertEquals(OperationalAdmissionService.Reason.OPERATIONAL_GUARD,context.gate().check(OperationalAdmissionService.Operation.DISPATCH_AI,null).reason());
            context.minute(2);context.absent=true;context.cycle().tick();var unknown=context.store.read(MEMORY);
            assertEquals(Status.TELEMETRY_UNKNOWN,unknown.status());assertEquals(prior.counters(),unknown.counters());assertEquals(prior.guards(),unknown.guards());
            assertFalse(context.gate().check(OperationalAdmissionService.Operation.DISPATCH_AI,null).allowed());context.absent=false;
            for(int minute=3;minute<13;minute++){context.minute(minute);context.cycle().tick();assertEquals(Level.CRITICAL,context.store.read(MEMORY).level());}
            context.minute(13);context.cycle().tick();assertNull(context.store.read(MEMORY).level());context.otherResources();
            assertTrue(context.gate().check(OperationalAdmissionService.Operation.DISPATCH_AI,null).allowed());
            assertEquals(2,context.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_rule' AND JSON_UNQUOTE(JSON_EXTRACT(action_payload,'$.record_type'))='ops_notice_intent'",Integer.class));
        }
    }

    @Test void SameNativeProcessRollbackNeverOverwritesActualMysqlStoredCounters() {
        try(var context=new Context()) {
            context.heap.set(2);context.cycle().tick();var original=context.store.read(MEMORY);context.minute(1);context.heap.set(1);context.cycle().tick();
            var retained=context.store.read(MEMORY);assertEquals(Status.TELEMETRY_UNKNOWN,retained.status());assertEquals(original.counters(),retained.counters());assertEquals(original.guards(),retained.guards());
            context.minute(2);context.heap.set(3);context.cycle().tick();assertEquals(3L,context.store.read(MEMORY).counters().get("java_vm_heap"));assertEquals(Level.CRITICAL,context.store.read(MEMORY).level());
        }
    }
}
