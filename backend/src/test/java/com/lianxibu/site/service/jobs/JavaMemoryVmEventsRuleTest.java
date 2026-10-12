package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static com.lianxibu.site.service.jobs.OperationalRuleEngineTest.*;
import static com.lianxibu.site.service.jobs.JavaMemoryEventsRuleTest.*;
import com.lianxibu.site.domain.jobs.JavaVmOomTelemetry;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class JavaMemoryVmEventsRuleTest {
    static final Instant BIRTH = START.minusSeconds(120);
    static final List<String> VM_KEYS = List.of("java_vm_required", "java_vm_pid", "java_vm_started_micros",
            "java_vm_received_micros", "java_vm_heap", "java_vm_meta", "java_vm_class_meta");
    static JavaMemoryVmEvents sample(int minute, long heap, long meta, long classMeta) {
        return new JavaMemoryVmEvents(events(minute, A, 0, 0), vm(minute, 1234, BIRTH, heap, meta, classMeta));
    }
    static JavaVmOomTelemetry.Evidence vm(int minute, long pid, Instant birth, long heap, long meta, long classMeta) {
        return new JavaVmOomTelemetry.Evidence(pid, birth, START.plusSeconds(minute * 60L + 6), heap, meta, classMeta);
    }

    @Test void EachNativeCategoryTriggersOnlyItsNewIncrementAndRepeatedCountsRecoverAfterOriginalWindow() {
        for (int category = 0; category < 3; category++) {
            long heap = category == 0 ? 1 : 0, meta = category == 1 ? 1 : 0, classMeta = category == 2 ? 1 : 0;
            var state = tick(empty(6), 0, sample(0, 0, 0, 0)).state(); assertNull(state.level());
            var trigger = tick(state, 1, sample(1, heap, meta, classMeta)); state = trigger.state();
            assertEquals(Level.CRITICAL, state.level()); assertEquals("1", state.observedValue());
            assertEquals("trigger", trigger.notices().getFirst().action()); assertTrue(state.guards().contains(Guard.DISPATCH_AI));
            for (int m = 2; m < 12; m++) { state = tick(state, m, sample(m, heap, meta, classMeta)).state(); assertEquals(Level.CRITICAL, state.level()); }
            var recovered = tick(state, 12, sample(12, heap, meta, classMeta)); assertNull(recovered.state().level());
            assertEquals("resolved", recovered.notices().getFirst().action());
        }
        assertEquals(Level.CRITICAL, tick(empty(6), 0, sample(0, 1, 0, 0)).state().level());
        assertEquals(Level.CRITICAL, tick(empty(6), 0, sample(0, 0, Integer.MAX_VALUE, 0)).state().level());
    }

    @Test void ActualTwentyCounterJsonSurvivesUnknownConfigurationGapAndNewEngine() {
        var state = tick(empty(6), 0, sample(0, 2, 1, 3)).state(); assertEquals(20, state.counters().size());
        var original = state.counters(); state = roundTrip(state); assertEquals(original, state.counters());
        state = ENGINE.sample(state, config(6), 1, START.plusSeconds(60), false, null).state(); assertEquals(original, state.counters());
        state = ENGINE.sample(state, null, 2, START.plusSeconds(120), true, sample(2, 2, 1, 3)).state(); assertEquals(original, state.counters());
        var after = new OperationalRuleEngine().sample(roundTrip(state), config(6), 2, START.plusSeconds(300), true, sample(5, 2, 1, 3));
        assertEquals(Level.CRITICAL, after.state().level()); assertTrue(after.notices().isEmpty());
        assertEquals(2L, after.state().counters().get("java_vm_heap"));
    }

    @Test void SameProcessAnyNativeRollbackPreservesAllPriorWatermarksAndGuards() {
        var state = tick(empty(6), 0, sample(0, 3, 3, 3)).state();
        for (long[] values : new long[][] {{2, 3, 3}, {3, 2, 3}, {3, 3, 2}, {4, 2, 3}}) {
            var after = tick(roundTrip(state), 1, sample(1, values[0], values[1], values[2])).state();
            assertEquals(Status.TELEMETRY_UNKNOWN, after.status()); assertEquals(state.counters(), after.counters()); assertEquals(state.guards(), after.guards());
        }
    }

    @Test void ProcessReplacementClearsRecoveryContinuityAndKeepsGuardUntilFullWindow() {
        var state = tick(empty(6), 0, sample(0, 1, 0, 0)).state();
        for (int m = 1; m < 7; m++) state = tick(state, m, sample(m, 1, 0, 0)).state();
        Instant newBirth = START.plusSeconds(400);
        for (int m = 7; m < 17; m++) {
            state = tick(state, m, new JavaMemoryVmEvents(events(m, A, 0, 0), vm(m, 1234, newBirth, 0, 0, 0))).state();
            assertEquals(Level.CRITICAL, state.level());
        }
        state = tick(state, 17, new JavaMemoryVmEvents(events(17, A, 0, 0), vm(17, 1234, newBirth, 0, 0, 0))).state(); assertNull(state.level());
        var replacedPositive = tick(state, 18, new JavaMemoryVmEvents(events(18, A, 0, 0), vm(18, 5678, START.plusSeconds(1070), 1, 0, 0)));
        assertEquals(Level.CRITICAL, replacedPositive.state().level());
    }

    @Test void InvalidOrMissingNativeFactsAndDowngradesCannotReleaseProtection() {
        var state = roundTrip(tick(empty(6), 0, sample(0, 1, 0, 0)).state());
        for (Instant received : new Instant[] {START.plusSeconds(59), START.plusSeconds(120), START.plusSeconds(6)}) {
            var nativeFact = new JavaVmOomTelemetry.Evidence(1234, BIRTH, received, 1, 0, 0);
            assertEquals(Status.TELEMETRY_UNKNOWN, tick(state, 1, new JavaMemoryVmEvents(events(1, A, 0, 0), nativeFact)).state().status());
        }
        for (Reading invalid : new Reading[] {new JavaMemoryVmEvents(events(1, A, 0, 0), null), new JavaMemoryVmEvents(null, vm(1, 1234, BIRTH, 1, 0, 0)),
                events(1, A, 0, 0), JavaMemoryRuleTest.reading("900", "500", false), new Memory(true, decimal("900"), false)}) {
            var after = tick(state, 1, invalid).state(); assertEquals(Status.TELEMETRY_UNKNOWN, after.status());
            assertEquals(state.counters(), after.counters()); assertEquals(state.guards(), after.guards());
        }
    }

    @Test void EveryMissingOrDamagedNativeWatermarkIsUnknownRatherThanFirstSeen() {
        var state = tick(empty(6), 0, sample(0, 1, 0, 0)).state();
        for (String name : VM_KEYS) {
            var encoded = OperationalStateCodec.encode(state); ((ObjectNode) encoded.get("counters")).remove(name);
            var damaged = OperationalStateCodec.decode(encoded);
            var after = tick(damaged, 1, sample(1, 1, 0, 0)).state(); assertEquals(Status.TELEMETRY_UNKNOWN, after.status());
            assertEquals(damaged.counters(), after.counters());
        }
        for (String name : VM_KEYS) {
            var encoded = OperationalStateCodec.encode(state);
            ((ObjectNode) encoded.get("counters")).put(name, name.equals("java_vm_pid") || name.equals("java_vm_required") ? 0 : 9007199254740991L);
            assertEquals(Status.TELEMETRY_UNKNOWN, tick(OperationalStateCodec.decode(encoded), 1, sample(1, 1, 0, 0)).state().status());
        }
        var encoded = OperationalStateCodec.encode(state); var counters = (ObjectNode) encoded.get("counters");
        counters.properties().stream().map(java.util.Map.Entry::getKey).filter(name -> !name.startsWith("java_vm_")).toList().forEach(counters::remove);
        assertEquals(Status.TELEMETRY_UNKNOWN, tick(OperationalStateCodec.decode(encoded), 1, sample(1, 1, 0, 0)).state().status());
    }

    @Test void KernelAndIndependentReportedOomRemainCriticalAndInvalidKernelDoesNotAdvanceVm() {
        var state = tick(empty(6), 0, sample(0, 0, 0, 0)).state();
        assertEquals(Level.CRITICAL, tick(state, 1, new JavaMemoryVmEvents(events(1, A, 1, 0), vm(1, 1234, BIRTH, 0, 0, 0))).state().level());
        var kernel = new JavaMemoryEvents(JavaMemoryRuleTest.reading("900", "500", true), A, 0, 0, START.plusSeconds(65));
        assertEquals(Level.CRITICAL, tick(state, 1, new JavaMemoryVmEvents(kernel, vm(1, 1234, BIRTH, 0, 0, 0))).state().level());
        var invalidKernel = new JavaMemoryEvents(JavaMemoryRuleTest.reading("900", "500", false), A, 0, 0, START.plusSeconds(5));
        var unknown = tick(state, 1, new JavaMemoryVmEvents(invalidKernel, vm(1, 1234, BIRTH, 1, 0, 0))).state();
        assertEquals(Status.TELEMETRY_UNKNOWN, unknown.status()); assertEquals(state.counters(), unknown.counters());
        assertThrows(IllegalArgumentException.class, () -> tick(empty(5), 0, sample(0, 0, 0, 0)));
    }

    @Test void PrivateCodecBoundsCountersAt32AndKeepsTimerBound16() {
        var state = tick(empty(6), 0, sample(0, 0, 0, 0)).state(); var encoded = OperationalStateCodec.encode(state);
        var counters = (ObjectNode) encoded.get("counters");
        for (int index = 20; index < 32; index++) counters.put("fictional_count_" + index, index);
        assertEquals(32, OperationalStateCodec.decode(OperationalStateCodec.JSON.readTree(OperationalStateCodec.JSON.writeValueAsBytes(encoded))).counters().size());
        counters.put("fictional_count_32", 32); assertThrows(IllegalArgumentException.class, () -> OperationalStateCodec.decode(encoded));
        counters.remove("fictional_count_32"); var timers = (ObjectNode) encoded.get("timers"); timers.removeAll();
        for (int index = 0; index < 16; index++) timers.put("fictional_timer_" + index, START.toString());
        assertEquals(16, OperationalStateCodec.decode(encoded).timers().size());
        timers.put("fictional_timer_16", START.toString()); assertThrows(IllegalArgumentException.class, () -> OperationalStateCodec.decode(encoded));
    }
}
