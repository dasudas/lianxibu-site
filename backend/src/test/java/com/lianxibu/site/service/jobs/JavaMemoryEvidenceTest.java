package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.*;
import com.lianxibu.site.service.jobs.JavaMemoryEvidence.Budget;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JavaMemoryEvidenceTest {
    static final long MIB = 1048576;
    static final Instant NOW = Instant.parse("2026-10-01T00:00:20Z"), BIRTH = NOW.minusSeconds(120), AT = NOW.minusSeconds(1);
    static final Budget BUDGET = new Budget(64*MIB, 256*MIB, 96*MIB, 32*MIB, 48*MIB, 64*MIB, 576*MIB, true, null);
    static final JavaMemoryEvidence VALIDATOR = new JavaMemoryEvidence(BUDGET);
    static JavaMemoryTelemetry.Evidence host(Instant at, long available) { return new JavaMemoryTelemetry.Evidence(at, 2048*MIB, available); }
    static JavaProcessTelemetry.Evidence process(long pid, Instant birth, Instant at, long rss) { return new JavaProcessTelemetry.Evidence(pid, birth, at, rss); }
    static JavaCgroupTelemetry.Evidence group(long pid, Instant birth, Instant at, Long maximum, long oom) {
        return new JavaCgroupTelemetry.Evidence(pid, birth, at, "a".repeat(64), 800*MIB, maximum, oom, 2);
    }
    static JavaVmMemoryTelemetry.Evidence vm(long pid, Instant birth, Instant at, long initial, long heap, long meta, long direct, long code, boolean exit, String hook) {
        return new JavaVmMemoryTelemetry.Evidence(pid, birth, at, initial, heap, meta, direct, code, exit, hook);
    }
    static JavaVmMemoryTelemetry.Evidence vm() { return vm(1234, BIRTH, AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null); }
    static JavaVmOomTelemetry.Evidence oom(long pid, Instant birth, Instant at) { return new JavaVmOomTelemetry.Evidence(pid,birth,at,3,1,2); }
    static OperationalRuleEngine.JavaMemoryVmEvents validate(JavaMemoryTelemetry.Evidence h, JavaProcessTelemetry.Evidence p,
            JavaCgroupTelemetry.Evidence c, JavaVmMemoryTelemetry.Evidence v, JavaVmOomTelemetry.Evidence o, Instant now) {
        return VALIDATOR.validate(h,p,c,v,o,now);
    }
    static OperationalRuleEngine.JavaMemoryVmEvents valid() { return validate(host(AT,900*MIB),process(1234,BIRTH,AT,500*MIB),group(1234,BIRTH,AT,576*MIB,4),vm(),oom(1234,BIRTH,AT),NOW); }

    @Test void FiveActualFactsPreserveIndependentRawCountersOriginalProcessAndQualifiedBudgets() {
        var reading=valid(); assertNotNull(reading); assertEquals(new BigDecimal("900.00000000"),reading.kernel().memory().availableMib());
        assertEquals(new BigDecimal("500.00000000"),reading.kernel().memory().residentMib()); assertEquals(new BigDecimal("576.00000000"),reading.kernel().memory().hardLimitMib());
        assertFalse(reading.kernel().memory().outOfMemory()); assertEquals(4,reading.kernel().oomEvents()); assertEquals(2,reading.kernel().oomKills());
        assertEquals("a".repeat(64),reading.kernel().resourceEpoch()); assertEquals(AT,reading.kernel().receivedAt()); assertEquals(3,reading.vm().javaHeapEvents());
        assertEquals(BIRTH,reading.vm().startedAt()); assertEquals("JavaMemoryBudget[redacted]",BUDGET.toString());
        var transition=OperationalRuleEngineTest.tick(OperationalRuleEngineTest.empty(6),0,reading); assertEquals(OperationalRuleEngine.Level.CRITICAL,transition.state().level());
    }

    @Test void ActualZerosRemainFactsAndMissingAnyFactIsUnknown() {
        var zeroOom=new JavaVmOomTelemetry.Evidence(1234,BIRTH,AT,0,0,0);
        var zeroGroup=new JavaCgroupTelemetry.Evidence(1234,BIRTH,AT,"a".repeat(64),0,576*MIB,0,0);
        var h=host(AT,0); var p=process(1234,BIRTH,AT,0); var v=vm(); var result=validate(h,p,zeroGroup,v,zeroOom,NOW);
        assertNotNull(result); assertEquals(0,result.kernel().memory().availableMib().signum()); assertEquals(0,result.kernel().memory().residentMib().signum());
        assertEquals(0,result.vm().javaHeapEvents());
        assertNull(validate(null,p,zeroGroup,v,zeroOom,NOW)); assertNull(validate(h,null,zeroGroup,v,zeroOom,NOW));
        assertNull(validate(h,p,null,v,zeroOom,NOW)); assertNull(validate(h,p,zeroGroup,null,zeroOom,NOW)); assertNull(validate(h,p,zeroGroup,v,null,NOW));
    }

    @Test void OneByteBelowAndAtHardLimitRemainDistinctAfterMiBConversion() {
        var h=host(AT,900*MIB); var c=group(1234,BIRTH,AT,576*MIB,0); var o=new JavaVmOomTelemetry.Evidence(1234,BIRTH,AT,0,0,0);
        c=new JavaCgroupTelemetry.Evidence(c.pid(),c.startedAt(),c.receivedAt(),c.resourceEpoch(),c.currentBytes(),c.maximumBytes(),0,0);
        var below=validate(h,process(1234,BIRTH,AT,576*MIB-1),c,vm(),o,NOW); var exact=validate(h,process(1234,BIRTH,AT,576*MIB),c,vm(),o,NOW);
        assertTrue(below.kernel().memory().residentMib().compareTo(below.kernel().memory().hardLimitMib())<0);
        assertNull(OperationalRuleEngineTest.tick(OperationalRuleEngineTest.empty(6),0,below).state().level());
        assertEquals(OperationalRuleEngine.Level.CRITICAL,OperationalRuleEngineTest.tick(OperationalRuleEngineTest.empty(6),0,exact).state().level());
    }

    @Test void EachFactMustHaveOriginalPidAndActualBirthMatchingRss() {
        var h=host(AT,900*MIB); var p=process(1234,BIRTH,AT,500*MIB); var c=group(1234,BIRTH,AT,576*MIB,0); var v=vm(); var o=oom(1234,BIRTH,AT);
        for(long pid:new long[]{1235,9007199254740992L}) {
            assertNull(validate(h,process(pid,BIRTH,AT,500*MIB),c,v,o,NOW)); assertNull(validate(h,p,group(pid,BIRTH,AT,576*MIB,0),v,o,NOW));
            assertNull(validate(h,p,c,vm(pid,BIRTH,AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),o,NOW)); assertNull(validate(h,p,c,v,oom(pid,BIRTH,AT),NOW));
        }
        Instant other=BIRTH.plusNanos(1000);
        assertNull(validate(h,process(1234,other,AT,500*MIB),c,v,o,NOW)); assertNull(validate(h,p,group(1234,other,AT,576*MIB,0),v,o,NOW));
        assertNull(validate(h,p,c,vm(1234,other,AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),o,NOW)); assertNull(validate(h,p,c,v,oom(1234,other,AT),NOW));
    }

    @Test void EachMeasuredBudgetAndActualExitHookMustMatchControlledPlan() {
        var h=host(AT,900*MIB); var p=process(1234,BIRTH,AT,500*MIB); var c=group(1234,BIRTH,AT,576*MIB,0); var o=oom(1234,BIRTH,AT);
        for(var v:new JavaVmMemoryTelemetry.Evidence[]{vm(1234,BIRTH,AT,65*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),
                vm(1234,BIRTH,AT,64*MIB,257*MIB,96*MIB,32*MIB,48*MIB,true,null),vm(1234,BIRTH,AT,64*MIB,256*MIB,97*MIB,32*MIB,48*MIB,true,null),
                vm(1234,BIRTH,AT,64*MIB,256*MIB,96*MIB,33*MIB,48*MIB,true,null),vm(1234,BIRTH,AT,64*MIB,256*MIB,96*MIB,32*MIB,49*MIB,true,null),
                vm(1234,BIRTH,AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,false,null),vm(1234,BIRTH,AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,"b".repeat(64))})
            assertNull(validate(h,p,c,v,o,NOW));
        for(Long cap:new Long[]{null,575*MIB,577*MIB}) assertNull(validate(h,p,group(1234,BIRTH,AT,cap,0),vm(),o,NOW));
        assertNull(validate(h,p,group(1234,BIRTH,AT,576*MIB,9007199254740992L),vm(),o,NOW));
        var hookBudget=new Budget(64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,64*MIB,576*MIB,true,"b".repeat(64));
        assertNotNull(new JavaMemoryEvidence(hookBudget).validate(h,p,c,vm(1234,BIRTH,AT,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,"b".repeat(64)),o,NOW));
    }

    @Test void AllFiveObservationTimesMustBeFreshWithinSameSampleMinuteAndNotFuture() {
        var h=host(AT,900*MIB); var p=process(1234,BIRTH,AT,500*MIB); var c=group(1234,BIRTH,AT,576*MIB,0); var v=vm(); var o=oom(1234,BIRTH,AT);
        for(Instant at:new Instant[]{NOW.minusSeconds(21),NOW.plusNanos(1000),NOW.minusSeconds(20).minusNanos(1000)}) {
            assertNull(validate(host(at,900*MIB),p,c,v,o,NOW)); assertNull(validate(h,process(1234,BIRTH,at,500*MIB),c,v,o,NOW));
            assertNull(validate(h,p,group(1234,BIRTH,at,576*MIB,0),v,o,NOW));
            assertNull(validate(h,p,c,vm(1234,BIRTH,at,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),o,NOW)); assertNull(validate(h,p,c,v,oom(1234,BIRTH,at),NOW));
        }
        assertNotNull(validate(host(NOW.minusSeconds(20),900*MIB),p,c,v,o,NOW));
        assertNull(validate(h,p,c,v,o,null)); assertNull(validate(h,p,c,v,o,NOW.plusNanos(1)));
        Instant early=NOW.minusSeconds(15), priorMinute=early.minusSeconds(10);
        assertNull(validate(host(priorMinute,900*MIB),process(1234,BIRTH,early,500*MIB),group(1234,BIRTH,early,576*MIB,0),
                vm(1234,BIRTH,early,64*MIB,256*MIB,96*MIB,32*MIB,48*MIB,true,null),oom(1234,BIRTH,early),early));
    }

    @Test void ControlledBudgetRejectsInvalidCapacityOverflowAndHasNoAcquisition() {
        assertThrows(IllegalArgumentException.class,()->new JavaMemoryEvidence(null));
        for(long[] values:new long[][]{{0,2,1,1,1,1,8},{3,2,1,1,1,1,8},{1,2,0,1,1,1,8},{1,2,1,0,1,1,8},
                {1,2,1,1,0,1,8},{1,2,1,1,1,0,8},{1,2,1,1,1,1,5},{1,9007199254740992L,1,1,1,1,9007199254740992L},
                {1,9007199254740991L,1,1,1,1,9007199254740991L}})
            assertThrows(IllegalArgumentException.class,()->new Budget(values[0],values[1],values[2],values[3],values[4],values[5],values[6],true,null));
        assertThrows(IllegalArgumentException.class,()->new Budget(1,2,1,1,1,1,8,true,"not-a-hash"));
        assertNotNull(new JavaMemoryEvidence(new Budget(1,2,1,1,1,1,6,false,null)));
    }
}
