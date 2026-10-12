package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class OperationalCapacityWatermarkTest {
    static final Instant START=OperationalRuleEngineTest.START;
    static final OperationalRuleEngine ENGINE=new OperationalRuleEngine();
    static State sample(State state,int minute,String free,Instant measured){return ENGINE.sample(state,OperationalRuleEngineTest.config(4),1,START.plusSeconds(minute*60L),true,new Capacity(new BigDecimal(free),measured)).state();}
    @Test void repeatedRolledBackOrMissingTimestampCannotAccumulateRecovery() {
        State state=sample(OperationalRuleEngineTest.empty(4),0,"4",START.minusSeconds(60));
        state=sample(state,1,"90",START); assertFalse(state.recoveryReady());
        state=sample(state,2,"90",START);assertEquals(Status.TELEMETRY_UNKNOWN,state.status());assertTrue(state.timers().isEmpty());assertEquals(4,state.guards().size());
        assertEquals(START.getEpochSecond(),state.counters().get("capacity_measured_at"));
        state=sample(state,3,"90",START.minusSeconds(60));assertEquals(Status.TELEMETRY_UNKNOWN,state.status());
        state=sample(state,4,"90",null);assertEquals(Status.TELEMETRY_UNKNOWN,state.status());
        assertEquals(START.plusSeconds(60).getEpochSecond(),state.counters().get("capacity_verified_at"));
    }
    @Test void actualMeasurementGapsAndConfigurationChangesResetWindowButKeepWatermark() {
        State state=sample(OperationalRuleEngineTest.empty(4),0,"4",START.minusSeconds(60));
        for(int minute=1;minute<=30;minute++){state=sample(state,minute,"90",START.plusSeconds((minute-1)*60L));assertFalse(state.recoveryReady());}
        state=sample(state,31,"90",START.plusSeconds(30*60L));assertTrue(state.recoveryReady());
        state=sample(state,32,"90",START.plusSeconds(32*60L));assertFalse(state.recoveryReady());
        state=ENGINE.sample(state,null,2,START.plusSeconds(33*60L),false,null).state();
        assertEquals(START.plusSeconds(32*60L).getEpochSecond(),state.counters().get("capacity_measured_at"));
        state=sample(state,34,"90",START.plusSeconds(32*60L));assertEquals(Status.TELEMETRY_UNKNOWN,state.status());
        assertFalse(state.recoveryReady());assertEquals(4,state.guards().size());
    }
    @Test void watermarkSurvivesRealSummaryReloadAndUnknownSamples() {
        var f=new OperationalPersistenceFixture();Key key=new Key("OPS-04","fixture","fictional-resource");
        var monitor=f.monitor(f.store());monitor.sample(key,true,new Capacity(new BigDecimal("4"),START.minusSeconds(60)),START,new SharedWriteExecutor.ActionContext("fixture-first","fixture-ops",null));
        f.clock.now=START.plusSeconds(60);f.monitor(f.store()).sample(key,false,null,f.clock.now,new SharedWriteExecutor.ActionContext("fixture-missing","fixture-ops",null));
        f.clock.now=START.plusSeconds(120);State result=f.monitor(f.store()).sample(key,true,new Capacity(new BigDecimal("90"),START.minusSeconds(60)),f.clock.now,new SharedWriteExecutor.ActionContext("fixture-duplicate","fixture-ops",null));
        assertEquals(Status.TELEMETRY_UNKNOWN,result.status());assertFalse(result.recoveryReady());assertEquals(4,result.guards().size());assertEquals(START.minusSeconds(60).getEpochSecond(),result.counters().get("capacity_measured_at"));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
    }
}
