package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.RdsTelemetry.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
class RdsMonitoringCycleTest {
    static final Resource RESOURCE=RdsCapacityEvidenceTest.RESOURCE;
    static final Key CAPACITY=new Key("OPS-04","fixture",RESOURCE.instanceId()), TELEMETRY=new Key("OPS-10","fixture",RESOURCE.instanceId());
    static Evidence evidence(Instant now){return new Evidence(RdsCapacityEvidenceTest.attributes(),RdsCapacityEvidenceTest.metadata(),RdsCapacityEvidenceTest.metrics(now.minusSeconds(120)),now);}
    static RdsMonitoringCycle cycle(OperationalPersistenceFixture f,Api api){var store=f.store();return new RdsMonitoringCycle(api,new RdsCapacityEvidence(),RESOURCE,CAPACITY,TELEMETRY,store,f.monitor(store),f.clock);}
    @Test void validatedSamplesPersistAndRestartSameMinuteSkipsNetworkAndWrites(){
        var f=new OperationalPersistenceFixture();var calls=new AtomicInteger();Api api=(r,t)->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();return evidence(f.clock.now);};
        assertEquals(RdsMonitoringCycle.Outcome.SAMPLED,cycle(f,api).tick());
        assertEquals(Status.NORMAL,f.store().read(CAPACITY).status());assertEquals("90",f.store().read(CAPACITY).observedValue());assertEquals("0",f.store().read(TELEMETRY).observedValue());
        assertEquals(RdsMonitoringCycle.Outcome.ALREADY_SAMPLED,cycle(f,api).tick());assertEquals(1,calls.get());assertEquals(2L,f.jdbc.queryForObject("SELECT revision FROM runtime_settings WHERE setting_key='ops_summary-v1'",Long.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
    @Test void failuresAndDuplicatesUsePermanentVerificationAgeAndCannotReleaseProtection(){
        var f=new OperationalPersistenceFixture();cycle(f,(r,t)->evidence(f.clock.now)).tick();
        f.clock.now=f.clock.now.plusSeconds(60);cycle(f,(r,t)->new Evidence(RdsCapacityEvidenceTest.attributes(),RdsCapacityEvidenceTest.metadata(),RdsCapacityEvidenceTest.metrics(f.clock.now.minusSeconds(180)),f.clock.now)).tick();
        assertEquals(Status.TELEMETRY_UNKNOWN,f.store().read(CAPACITY).status());assertEquals("60",f.store().read(TELEMETRY).observedValue());
        f.clock.now=f.clock.now.plusSeconds(600);cycle(f,(r,t)->{throw new IllegalStateException("fictional transport failure");}).tick();
        assertEquals("660",f.store().read(TELEMETRY).observedValue());assertEquals(4,f.store().read(TELEMETRY).guards().size());
        f.clock.now=f.clock.now.plusSeconds(60);cycle(f,(r,t)->evidence(f.clock.now)).tick();assertEquals(4,f.store().read(TELEMETRY).guards().size());
    }
    @Test void initialMissingCapacityIsUnknownAndGuardedRatherThanHealthy(){
        var f=new OperationalPersistenceFixture();cycle(f,(r,t)->null).tick();assertEquals(Status.TELEMETRY_UNKNOWN,f.store().read(CAPACITY).status());assertEquals(4,f.store().read(TELEMETRY).guards().size());
    }
    @Test void secondCommitFailureResumesFromFirstReceiptWithoutNewNetwork() {
        var f=new OperationalPersistenceFixture();var delegate=f.store();
        var failing=new OperationalMonitoringService.Store(){
            public State committed(String key,String hash){return delegate.committed(key,hash);}
            public State read(Key key){return delegate.read(key);}
            public void save(State previous,State next,java.util.List<Notice> notices,String key,String hash,String actor,String reason){if(next.key().equals(TELEMETRY))throw new IllegalStateException("fictional second commit failure");delegate.save(previous,next,notices,key,hash,actor,reason);}
        };
        var monitor=new OperationalMonitoringService(failing,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,f.clock);
        var cycle=new RdsMonitoringCycle((r,t)->evidence(f.clock.now),new RdsCapacityEvidence(),RESOURCE,CAPACITY,TELEMETRY,failing,monitor,f.clock);
        assertThrows(IllegalStateException.class,cycle::tick);assertNotNull(delegate.read(CAPACITY));assertNull(delegate.read(TELEMETRY));
        assertEquals(RdsMonitoringCycle.Outcome.SAMPLED,cycle(f,(r,t)->{fail("no network on partial minute replay");return null;}).tick());assertNotNull(delegate.read(TELEMETRY));
    }
    @Test void reentryAndStopDuringNetworkProduceNoLateWrites()throws Exception{
        var f=new OperationalPersistenceFixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        var cycle=cycle(f,(r,t)->{entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException stop){Thread.currentThread().interrupt();}return evidence(f.clock.now);});
        try {var pending=executor.submit(cycle::tick);assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(RdsMonitoringCycle.Outcome.BUSY,cycle.tick());cycle.stop();release.countDown();assertEquals(RdsMonitoringCycle.Outcome.STOPPED,pending.get(2,TimeUnit.SECONDS));assertNull(f.store().read(CAPACITY));assertEquals(RdsMonitoringCycle.Outcome.STOPPED,cycle.tick());}
        finally{release.countDown();executor.shutdownNow();}
    }
}
