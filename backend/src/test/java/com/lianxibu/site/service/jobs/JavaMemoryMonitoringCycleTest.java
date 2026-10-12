package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JavaMemoryMonitoringCycleTest {
    static final Key KEY = new Key("OPS-06", "fixture", "fictional-java");
    static final long MIB = JavaMemoryEvidenceTest.MIB;
    static final Instant BIRTH = OperationalRuleEngineTest.START.minusSeconds(120);
    static JavaMemoryMonitoringCycle cycle(OperationalPersistenceFixture f, AtomicInteger calls, AtomicInteger events) {
        var store=f.store();
        return new JavaMemoryMonitoringCycle(()->{outside(calls);return new JavaMemoryTelemetry.Evidence(f.clock.now,2048*MIB,900*MIB);},
                ()->{outside(calls);return new JavaProcessTelemetry.Evidence(1234,BIRTH,f.clock.now,500*MIB);},
                ()->{outside(calls);return new JavaCgroupTelemetry.Evidence(1234,BIRTH,f.clock.now,"a".repeat(64),600*MIB,576*MIB,0,0);},
                ()->{outside(calls);return new JavaVmMemoryTelemetry.Evidence(1234,BIRTH,f.clock.now,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null);},
                ()->{outside(calls);return new JavaVmOomTelemetry.Evidence(1234,BIRTH,f.clock.now,events.get(),0,0);},
                new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,f.monitor(store),f.clock);
    }
    static void outside(AtomicInteger calls) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet(); }

    @Test void FiveActualFactsPersistTwentyFieldsAndRestartSkipsSameMinuteAcquisition() {
        var f=new OperationalPersistenceFixture(); f.clock.now=f.clock.now.plusSeconds(10); var calls=new AtomicInteger(); var events=new AtomicInteger();
        var first=cycle(f,calls,events); assertEquals(JavaMemoryMonitoringCycle.Outcome.STOPPED,first.tick()); assertEquals(0,calls.get());
        first.start(); assertEquals(JavaMemoryMonitoringCycle.Outcome.SAMPLED,first.tick()); assertEquals(5,calls.get());
        var stored=f.store().read(KEY); assertEquals(Status.NORMAL,stored.status()); assertEquals(20,stored.counters().size()); assertEquals(0L,stored.counters().get("java_vm_heap"));
        var restarted=cycle(f,calls,events); restarted.start(); assertEquals(JavaMemoryMonitoringCycle.Outcome.ALREADY_SAMPLED,restarted.tick()); assertEquals(5,calls.get());
        assertEquals(1L,f.jdbc.queryForObject("SELECT revision FROM runtime_settings WHERE setting_key='ops_summary-v1'",Long.class));
        f.clock.now=f.clock.now.plusSeconds(60); events.set(1); assertEquals(JavaMemoryMonitoringCycle.Outcome.SAMPLED,restarted.tick());
        assertEquals(Level.CRITICAL,f.store().read(KEY).level()); assertEquals(1L,f.store().read(KEY).counters().get("java_vm_heap"));
        assertTrue(f.jdbc.queryForList("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class).stream()
                .anyMatch(payload->OperationalStateCodec.JSON.readTree(payload).path("record_type").asText().equals("ops_notice_intent")));
    }

    @Test void MissingOrFailedAcquisitionRecordsUnknownAndRetainsExistingCriticalWatermarks() {
        var f=new OperationalPersistenceFixture(); f.clock.now=f.clock.now.plusSeconds(10); var calls=new AtomicInteger(); var events=new AtomicInteger(1);
        var initial=cycle(f,calls,events); initial.start(); initial.tick(); var prior=f.store().read(KEY); f.clock.now=f.clock.now.plusSeconds(60);
        var store=f.store(); var absent=new JavaMemoryMonitoringCycle(()->{throw new IllegalStateException("fictional source unavailable");},()->null,()->null,()->null,()->null,
                new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,f.monitor(store),f.clock); absent.start(); assertEquals(JavaMemoryMonitoringCycle.Outcome.SAMPLED,absent.tick());
        var after=f.store().read(KEY); assertEquals(Status.TELEMETRY_UNKNOWN,after.status()); assertEquals(prior.guards(),after.guards()); assertEquals(prior.counters(),after.counters());
        var empty=new OperationalPersistenceFixture(); store=empty.store(); var firstMissing=new JavaMemoryMonitoringCycle(()->null,()->null,()->null,()->null,()->null,
                new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,empty.monitor(store),empty.clock); firstMissing.start(); firstMissing.tick();
        assertEquals(Status.TELEMETRY_UNKNOWN,empty.store().read(KEY).status()); assertNull(empty.store().read(KEY).observedValue());
    }

    @Test void AcquisitionCrossingMinuteCannotCommitAQualifiedHealthyReading() {
        var f=new OperationalPersistenceFixture(); f.clock.now=f.clock.now.plusSeconds(59); var store=f.store(); var originalMinute=OperationalRuleEngineTest.START;
        var cycle=new JavaMemoryMonitoringCycle(()->{var fact=new JavaMemoryTelemetry.Evidence(f.clock.now,2048*MIB,900*MIB);f.clock.now=f.clock.now.plusSeconds(2);return fact;},
                ()->new JavaProcessTelemetry.Evidence(1234,BIRTH,f.clock.now,500*MIB),
                ()->new JavaCgroupTelemetry.Evidence(1234,BIRTH,f.clock.now,"a".repeat(64),500*MIB,576*MIB,0,0),
                ()->new JavaVmMemoryTelemetry.Evidence(1234,BIRTH,f.clock.now,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),
                ()->new JavaVmOomTelemetry.Evidence(1234,BIRTH,f.clock.now,0,0,0),new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,f.monitor(store),f.clock);
        cycle.start(); cycle.tick(); var saved=f.store().read(KEY); assertEquals(originalMinute,saved.lastSampleAt()); assertEquals(Status.TELEMETRY_UNKNOWN,saved.status());
        assertTrue(saved.counters().isEmpty());
    }

    @Test void BusyAndStopDuringAcquisitionPreventSubsequentSourcesAndLateWrites() throws Exception {
        var f=new OperationalPersistenceFixture(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var store=f.store(); var executor=Executors.newSingleThreadExecutor();
        var cycle=new JavaMemoryMonitoringCycle(()->{entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException stop){Thread.currentThread().interrupt();}return null;},
                ()->{fail("no source after stop");return null;},()->null,()->null,()->null,new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,f.monitor(store),f.clock);
        cycle.start();
        try {var pending=executor.submit(cycle::tick);assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(JavaMemoryMonitoringCycle.Outcome.BUSY,cycle.tick());
            cycle.stop();release.countDown();assertEquals(JavaMemoryMonitoringCycle.Outcome.STOPPED,pending.get(2,TimeUnit.SECONDS));assertNull(store.read(KEY));assertEquals(JavaMemoryMonitoringCycle.Outcome.STOPPED,cycle.tick());}
        finally{release.countDown();executor.shutdownNow();}
    }

    @Test void WriterFailureCanRetryFromActualPersistentMinuteWithoutPrematureSuccess() {
        var f=new OperationalPersistenceFixture(); var delegate=f.store(); var failures=new AtomicInteger();
        var store=new OperationalMonitoringService.Store(){
            public State committed(String action,String hash){return delegate.committed(action,hash);} public State read(Key key){return delegate.read(key);}
            public void save(State previous,State next,java.util.List<Notice> notices,String action,String hash,String actor,String reason){failures.incrementAndGet();throw new IllegalStateException("fictional write failure");}
        };
        var monitor=new OperationalMonitoringService(store,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,f.clock);
        var broken=new JavaMemoryMonitoringCycle(()->null,()->null,()->null,()->null,()->null,new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),KEY,store,monitor,f.clock);
        broken.start();assertThrows(IllegalStateException.class,broken::tick);assertEquals(1,failures.get());assertNull(delegate.read(KEY));
        var recovered=cycle(f,new AtomicInteger(),new AtomicInteger());recovered.start();assertEquals(JavaMemoryMonitoringCycle.Outcome.SAMPLED,recovered.tick());assertNotNull(delegate.read(KEY));
    }

    @Test void ConstructionIsLazyWrongBindingRejectedAndTransactionsOrClockRollbackCannotAcquire() {
        var f=new OperationalPersistenceFixture();var calls=new AtomicInteger();var cycle=cycle(f,calls,new AtomicInteger());assertEquals(0,calls.get());cycle.start();
        for(boolean readonly:new boolean[]{true,false}) {
            try{TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readonly);
                assertThrows(IllegalStateException.class,cycle::tick);assertEquals(0,calls.get());}finally{TransactionSynchronizationManager.clear();}
        }
        cycle.tick();f.clock.now=f.clock.now.minusSeconds(60);assertThrows(IllegalStateException.class,cycle::tick);assertEquals(5,calls.get());
        var store=f.store();assertThrows(IllegalArgumentException.class,()->new JavaMemoryMonitoringCycle(()->null,()->null,()->null,()->null,()->null,
                new JavaMemoryEvidence(JavaMemoryEvidenceTest.BUDGET),new Key("OPS-10","fixture","fictional-java"),store,f.monitor(store),f.clock));
        try{Thread.currentThread().interrupt();assertEquals(JavaMemoryMonitoringCycle.Outcome.STOPPED,cycle.tick());}finally{Thread.interrupted();}
    }
}
