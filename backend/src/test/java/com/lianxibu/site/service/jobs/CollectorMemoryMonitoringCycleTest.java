package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.CollectorMemoryFacts;import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;import org.junit.jupiter.api.Test;
class CollectorMemoryMonitoringCycleTest {
    final Key key=new Key("OPS-06","collector","fictional-node");
    CollectorMemoryFacts.Evidence evidence(OperationalPersistenceFixture f,long rss,long revision){return new CollectorMemoryFacts.Evidence("fictional-node",1,true,192L<<20,revision,new CollectorMemoryFacts.Snapshot("00000000-0000-4000-8000-000000000001","a".repeat(64),OperationalRuleEngineTest.START.minusSeconds(60),revision,12,f.clock.now,rss<<20,"b".repeat(64),192L<<20,0,0),f.clock.now,f.clock.now);}
    CollectorMemoryMonitoringCycle cycle(OperationalPersistenceFixture f,CollectorMemoryFacts.Api api){var store=f.store();var c=new CollectorMemoryMonitoringCycle("fictional-node",api,store,f.monitor(store),f.clock);c.start();return c;}
    @Test void NativeAcquisitionIsOutsideWritesAndOriginalMinuteSurvivesRestart(){
        var f=new OperationalPersistenceFixture();var n=new AtomicInteger();var c=cycle(f,id->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());n.incrementAndGet();return evidence(f,192,1);});
        assertEquals(CollectorMemoryMonitoringCycle.Outcome.SAMPLED,c.tick());assertTrue(f.store().read(key).guards().contains(Guard.NEW_FETCH));assertEquals(1,n.get());
        assertEquals(CollectorMemoryMonitoringCycle.Outcome.ALREADY_SAMPLED,cycle(f,id->{fail("original minute must not reacquire");return null;}).tick());assertEquals(1,f.store().read(key).revision());
        f.clock.now=f.clock.now.plusSeconds(60);cycle(f,id->{throw new IllegalStateException("fictional unavailable");}).tick();var state=f.store().read(key);assertEquals(Status.TELEMETRY_UNKNOWN,state.status());assertTrue(state.guards().contains(Guard.NEW_FETCH));assertEquals(1,state.counters().get("cm_history"));
    }
    @Test void ColdStopCrossMinuteRollbackAndOuterTransactionDoNotGrantHealthyState(){
        var f=new OperationalPersistenceFixture();var store=f.store();var c=new CollectorMemoryMonitoringCycle("fictional-node",id->{fail("cold no IO");return null;},store,f.monitor(store),f.clock);assertEquals(CollectorMemoryMonitoringCycle.Outcome.STOPPED,c.tick());assertNull(store.read(key));
        cycle(f,id->{var value=evidence(f,100,1);f.clock.now=f.clock.now.plusSeconds(60);return value;}).tick();assertEquals(Status.TELEMETRY_UNKNOWN,store.read(key).status());
        f.clock.now=OperationalRuleEngineTest.START.minusSeconds(60);assertThrows(IllegalStateException.class,()->cycle(f,id->null).tick());
        f.clock.now=OperationalRuleEngineTest.START.plusSeconds(120);assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(f.tx).execute(s->cycle(f,id->null).tick()));
    }
    @Test void SingleFlightAndStopRestartRejectOriginalInFlightGeneration()throws Exception{
        var f=new OperationalPersistenceFixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var c=cycle(f,id->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}return evidence(f,192,1);});var pool=Executors.newSingleThreadExecutor();
        try{var first=pool.submit(c::tick);assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(CollectorMemoryMonitoringCycle.Outcome.BUSY,c.tick());c.stop();c.start();release.countDown();assertEquals(CollectorMemoryMonitoringCycle.Outcome.STOPPED,first.get(5,TimeUnit.SECONDS));assertNull(f.store().read(key));assertEquals(CollectorMemoryMonitoringCycle.Outcome.SAMPLED,c.tick());assertTrue(f.store().read(key).guards().contains(Guard.NEW_FETCH));}finally{release.countDown();pool.shutdownNow();c.stop();}
    }
    @Test void RealForwardSamplesRecoverAndRepeatedHistoryStaysUnknown(){
        var f=new OperationalPersistenceFixture();cycle(f,id->evidence(f,192,1)).tick();
        f.clock.now=f.clock.now.plusSeconds(60);cycle(f,id->evidence(f,100,1)).tick();assertEquals(Status.TELEMETRY_UNKNOWN,f.store().read(key).status());
        for(int m=2;m<=12;m++){f.clock.now=OperationalRuleEngineTest.START.plusSeconds(60L*m);final int revision=m+1;cycle(f,id->evidence(f,100,revision)).tick();if(m<12)assertTrue(f.store().read(key).guards().contains(Guard.NEW_FETCH));}
        assertEquals(Status.RESOLVED,f.store().read(key).status());assertTrue(f.store().read(key).guards().isEmpty());
    }
}
