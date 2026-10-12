package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.CollectorMemoryFacts;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.math.BigDecimal;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;

class CollectorMemoryWatermarkTest {
    static final Instant T=OperationalRuleEngineTest.START;
    static final String E="00000000-0000-4000-8000-000000000001", E2="00000000-0000-4000-8000-000000000002";
    final OperationalRuleEngine engine=new OperationalRuleEngine();
    State empty(){return State.empty(new Key("OPS-06","collector","fictional-node"));}
    CollectorMemory frame(int minute,long rss,long kills,Long max){return frame(minute,minute+1,minute+1,1,E,T.minusSeconds(60),"a".repeat(64),12,"b".repeat(64),rss,0,kills,max);}
    CollectorMemory frame(int minute,long history,long sequence,long node,String epoch,Instant born,String process,long pid,String cgroup,long rss,long oom,long kills,Long max){
        Instant at=T.plusSeconds(minute*60L);var snapshot=new CollectorMemoryFacts.Snapshot(epoch,process,born,sequence,pid,at,rss<<20,cgroup,max,oom,kills);
        return new CollectorMemory(new CollectorMemoryFacts.Evidence("fictional-node",node,true,192L<<20,history,snapshot,at,at));
    }
    State tick(State state,int minute,Reading reading){return engine.sample(state,OperationalRuleEngineTest.config(6),1,T.plusSeconds(minute*60L),true,reading).state();}
    void unknown(State before,int minute,Reading reading){var after=tick(before,minute,reading);assertEquals(Status.TELEMETRY_UNKNOWN,after.status());assertEquals(before.guards(),after.guards());assertEquals(before.counters(),after.counters());assertTrue(after.timers().isEmpty());}
    @Test void nativeCriticalAndRecoveryRequireElevenForwardSamplesWithOriginalCodecCapacity(){
        State s=tick(empty(),0,frame(0,192,0,192L<<20));assertEquals(Level.CRITICAL,s.level());assertEquals(Set.of(Guard.NEW_FETCH),s.guards());assertEquals(31,s.counters().size());
        s=OperationalStateCodec.decode(OperationalStateCodec.encode(s));
        for(int m=1;m<=10;m++){s=tick(s,m,frame(m,110,0,192L<<20));assertEquals(Level.CRITICAL,s.level());assertTrue(s.guards().contains(Guard.NEW_FETCH));}
        s=tick(s,11,frame(11,110,0,192L<<20));assertEquals(Status.RESOLVED,s.status());assertNull(s.level());assertTrue(s.guards().isEmpty());
    }
    @Test void warningUsesOriginalFiveMinuteHoldAndReplayNeverAdvancesRecovery(){
        State s=empty();for(int m=0;m<5;m++){s=tick(s,m,frame(m,129,0,192L<<20));assertNull(s.level());}
        s=tick(s,5,frame(5,129,0,192L<<20));assertEquals(Level.WARNING,s.level());
        unknown(s,6,frame(5,100,0,192L<<20));unknown(s,6,new Memory(false,BigDecimal.ZERO,false));
        var e=frame(5,100,0,192L<<20).facts();unknown(s,6,new CollectorMemory(new CollectorMemoryFacts.Evidence(e.nodeId(),e.nodeRevision(),true,e.expectedMaximumBytes(),e.historyRevision(),e.snapshot(),e.receivedAt(),T.plusSeconds(360))));
    }
    @Test void IndependentKillCounterTriggersOnceAndNewDeltaRestartsRecovery(){
        State s=tick(empty(),0,frame(0,100,2,192L<<20));assertEquals(Level.CRITICAL,s.level());
        for(int m=1;m<=5;m++)s=tick(s,m,frame(m,100,2,192L<<20));
        s=tick(s,6,frame(6,100,3,192L<<20));assertEquals(Level.CRITICAL,s.level());assertFalse(s.timers().containsKey("healthy"));
        for(int m=7;m<=16;m++){s=tick(s,m,frame(m,100,3,192L<<20));assertEquals(Level.CRITICAL,s.level());}
        s=tick(s,17,frame(17,100,3,192L<<20));assertEquals(Status.RESOLVED,s.status());
    }
    @Test void PartialOrWrongBudgetNeverGrantsRecoveryButActualRiskStillProtects(){
        for(Long max:new Long[]{null,256L<<20}){
            State s=tick(empty(),0,frame(0,192,0,max));assertEquals(Status.TELEMETRY_UNKNOWN,s.status());assertEquals(Level.CRITICAL,s.level());
            for(int m=1;m<=20;m++){s=tick(s,m,frame(m,100,0,max));assertEquals(Status.TELEMETRY_UNKNOWN,s.status());assertTrue(s.guards().contains(Guard.NEW_FETCH));assertFalse(s.timers().containsKey("healthy"));}
        }
    }
    @Test void LifetimesCountersHistoryAndCorruptWatermarksFailClosed(){
        State s=tick(empty(),0,frame(0,192,2,192L<<20));
        unknown(s,1,frame(1,1,2,1,E,T.minusSeconds(60),"a".repeat(64),12,"b".repeat(64),100,0,2,192L<<20));
        unknown(s,1,frame(1,2,1,1,E,T.minusSeconds(60),"a".repeat(64),12,"b".repeat(64),100,0,2,192L<<20));
        unknown(s,1,frame(1,2,2,1,E,T.minusSeconds(60),"c".repeat(64),12,"b".repeat(64),100,0,2,192L<<20));
        unknown(s,1,frame(1,2,2,1,E,T.minusSeconds(60),"a".repeat(64),13,"b".repeat(64),100,0,2,192L<<20));
        unknown(s,1,frame(1,2,2,1,E2,T,"a".repeat(64),12,"b".repeat(64),100,0,2,192L<<20));
        unknown(s,1,frame(1,100,1,1,E2,T.plusSeconds(1),"c".repeat(64),13,"b".repeat(64),100,0,1,192L<<20));
        var map=new HashMap<>(s.counters());map.put("cm_e0",4294967296L);
        var bad=new State(s.key(),s.revision(),s.configurationRevision(),s.status(),s.level(),s.firstSeenAt(),s.lastSeenAt(),s.lastSampleAt(),s.observedValue(),s.threshold(),s.timers(),map,s.guards(),s.acknowledged(),s.recoveryReady(),s.nextReminderAt(),s.silencedUntil());
        unknown(bad,1,frame(1,100,2,192L<<20));
    }
    @Test void NewProcessCgroupNodeAndGapsRestartWindowsWhileUnknownRetainsLifetimes(){
        State s=tick(empty(),0,frame(0,192,0,192L<<20));s=tick(s,1,frame(1,100,0,192L<<20));
        s=tick(s,2,frame(2,3,1,2,E2,T.plusSeconds(61),"c".repeat(64),13,"d".repeat(64),100,0,0,192L<<20));
        assertEquals(T.plusSeconds(120),s.timers().get("healthy"));assertEquals(31,s.counters().size());
        var missing=engine.sample(s,null,2,T.plusSeconds(180),true,null).state();assertEquals(s.counters(),missing.counters());assertEquals(s.guards(),missing.guards());
        var round=OperationalStateCodec.decode(OperationalStateCodec.encode(missing));unknown(round,4,new Memory(false,BigDecimal.ZERO,false));
        var delayed=frame(5,4,2,2,E2,T.plusSeconds(61),"c".repeat(64),13,"d".repeat(64),100,0,0,192L<<20);
        var joined=CollectorMemoryProjection.join(delayed.facts(),s,T.plusSeconds(300));assertTrue(joined.reset());
        var e=delayed.facts();unknown(s,6,new CollectorMemory(new CollectorMemoryFacts.Evidence("other-fictional-node",2,true,192L<<20,4,e.snapshot(),e.receivedAt(),T.plusSeconds(360))));
        unknown(s,9,new CollectorMemory(new CollectorMemoryFacts.Evidence(e.nodeId(),2,true,192L<<20,4,e.snapshot(),e.receivedAt(),T.plusSeconds(540))));
    }
    @Test void FullPrivateCommandProjectionIncludesRawAndControlledFactsWhileDiagnosticsStayRedacted(){
        var original=frame(0,100,0,192L<<20);assertEquals("CollectorMemoryFacts[redacted]",original.facts().toString());
        assertNotEquals(original.toString(),frame(0,101,0,192L<<20).toString());assertNotEquals(original.toString(),frame(0,100,1,192L<<20).toString());assertNotEquals(original.toString(),frame(0,100,0,null).toString());
        var e=original.facts();assertNotEquals(original.toString(),new CollectorMemory(new CollectorMemoryFacts.Evidence(e.nodeId(),e.nodeRevision(),true,256L<<20,e.historyRevision(),e.snapshot(),e.receivedAt(),e.readAt())).toString());
        var json=tools.jackson.databind.json.JsonMapper.shared().readTree(CollectorMemoryProjection.value(e));assertEquals(11,json.get("snapshot").size());
    }
}

