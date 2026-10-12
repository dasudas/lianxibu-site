package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static com.lianxibu.site.service.jobs.OperationalRuleEngineTest.*;
import static com.lianxibu.site.service.jobs.JavaMemoryRuleTest.reading;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class JavaMemoryEventsRuleTest {
    static final String A = "a".repeat(64), B = "b".repeat(64);
    static JavaMemoryEvents events(int minute, String epoch, long oom, long kills) {
        return new JavaMemoryEvents(reading("900", "500", false), epoch, oom, kills,
                START.plusSeconds(minute * 60L + 5).plusNanos(123456000));
    }
    static State roundTrip(State state) {
        return OperationalStateCodec.decode(OperationalStateCodec.JSON.readTree(
                OperationalStateCodec.JSON.writeValueAsBytes(OperationalStateCodec.encode(state))));
    }

    @Test void OnlyNewKernelCountsTriggerAndUnchangedCountersPermitOriginalRecoveryWindow() {
        var state = tick(empty(6), 0, events(0, A, 0, 0)).state(); assertNull(state.level());
        var trigger = tick(state, 1, events(1, A, 1, 0)); assertEquals(Level.CRITICAL, trigger.state().level());
        assertEquals("1", trigger.state().observedValue()); assertEquals("trigger", trigger.notices().getFirst().action());
        state = trigger.state();
        for (int m = 2; m < 12; m++) { state = tick(state, m, events(m, A, 1, 0)).state(); assertEquals(Level.CRITICAL, state.level()); }
        var recovery = tick(state, 12, events(12, A, 1, 0)); assertNull(recovery.state().level());
        assertEquals("resolved", recovery.notices().getFirst().action());
        var killed = tick(recovery.state(), 13, events(13, A, 1, 1)); assertEquals(Level.CRITICAL, killed.state().level());
        assertEquals("trigger", killed.notices().getFirst().action());
        var initialHistorical = tick(empty(6), 0, events(0, A, 3, 9)); assertEquals(Level.CRITICAL, initialHistorical.state().level());
        assertEquals(9L, initialHistorical.state().counters().get("java_kernel_kills"));
    }

    @Test void AllThirteenWatermarksSurviveUnknownConfigurationGapAndSerializedRestart() {
        var state = tick(empty(6), 0, events(0, A, 3, 1)).state(); assertEquals(13, state.counters().size());
        var original = state.counters();
        state = ENGINE.sample(state, config(6), 1, START.plusSeconds(60), false, null).state(); assertEquals(original, state.counters());
        state = ENGINE.sample(state, null, 2, START.plusSeconds(120), true, events(2, A, 3, 1)).state(); assertEquals(original, state.counters());
        state = roundTrip(state); assertEquals(original, state.counters());
        var after = new OperationalRuleEngine().sample(state, config(6), 2, START.plusSeconds(300), true, events(5, A, 3, 1));
        assertEquals(Level.CRITICAL, after.state().level()); assertTrue(after.notices().isEmpty());
        assertEquals(3L, after.state().counters().get("java_kernel_oom"));
        assertEquals(1L, after.state().counters().get("java_kernel_required"));
    }

    @Test void SameResourceCounterRollbackIsUnknownAndNeverOverwritesRetainedFacts() {
        var state = tick(empty(6), 0, events(0, A, 3, 2)).state();
        for (long[] counts : new long[][] {{2, 2}, {3, 1}, {2, 3}}) {
            var after = tick(roundTrip(state), 1, events(1, A, counts[0], counts[1])).state();
            assertEquals(Status.TELEMETRY_UNKNOWN, after.status()); assertEquals(state.counters(), after.counters());
            assertEquals(state.guards(), after.guards()); assertNull(after.observedValue());
        }
    }

    @Test void ResourceReplacementClearsContinuousTimersAndRetainsProtectionUntilNewFullWindow() {
        var state = tick(empty(6), 0, events(0, A, 3, 0)).state();
        for (int m = 1; m < 7; m++) state = tick(state, m, events(m, A, 3, 0)).state();
        state = tick(state, 7, events(7, B, 0, 0)).state();
        assertEquals(0xbbbbbbbbL, state.counters().get("java_cgroup_e0")); assertEquals(0L, state.counters().get("java_kernel_oom"));
        for (int m = 8; m < 17; m++) { state = tick(state, m, events(m, B, 0, 0)).state(); assertEquals(Level.CRITICAL, state.level()); }
        assertNull(tick(state, 17, events(17, B, 0, 0)).state().level());
        assertEquals(Level.CRITICAL, tick(empty(6), 0, events(0, B, 1, 0)).state().level());
    }

    @Test void InvalidReceivedFactsAndMissingRequiredWatermarksCannotBecomeHealthy() {
        var state = tick(empty(6), 0, events(0, A, 1, 0)).state();
        for (Instant at : new Instant[] {null, START.plusSeconds(59), START.plusSeconds(120), START.plusSeconds(65).plusNanos(1)}) {
            var after = tick(state, 1, new JavaMemoryEvents(reading("900", "500", false), A, 1, 0, at)).state();
            assertEquals(Status.TELEMETRY_UNKNOWN, after.status()); assertEquals(state.counters(), after.counters());
        }
        for (String epoch : new String[] {null, "", "g".repeat(64), "A".repeat(64)})
            assertEquals(Status.TELEMETRY_UNKNOWN, tick(state, 1, events(1, epoch, 1, 0)).state().status());
        assertEquals(Status.TELEMETRY_UNKNOWN, tick(state, 1, new JavaMemoryEvents(null, A, 1, 0, START.plusSeconds(65))).state().status());
        for (String name : new String[] {"java_kernel_oom", "java_kernel_kills", "java_cgroup_e3", "java_memory_received_micros"}) {
            var stored = OperationalStateCodec.encode(state); ((ObjectNode) stored.get("counters")).remove(name);
            var damaged = OperationalStateCodec.decode(OperationalStateCodec.JSON.readTree(OperationalStateCodec.JSON.writeValueAsBytes(stored)));
            assertEquals(Status.TELEMETRY_UNKNOWN, tick(damaged, 1, events(1, A, 1, 0)).state().status());
        }
    }

    @Test void KernelRequiredResourceRejectsRssOnlyAndAllOldShapesEvenAfterRestart() {
        var state = roundTrip(tick(empty(6), 0, events(0, A, 1, 0)).state());
        for (Reading downgraded : new Reading[] {reading("900", "500", false), new Memory(true, decimal("900"), false), new Memory(false, decimal("100"), false)}) {
            var after = tick(state, 1, downgraded).state();
            assertEquals(Status.TELEMETRY_UNKNOWN, after.status()); assertEquals(state.guards(), after.guards());
            assertEquals(state.counters(), after.counters());
        }
    }

    @Test void CounterRepresentationIsBoundedAndHeapOomRemainsAnIndependentCriticalFact() {
        for (long invalid : new long[] {-1, 9007199254740992L}) {
            assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, events(0, A, invalid, 0)));
            assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, events(0, A, 0, invalid)));
        }
        assertEquals(Level.CRITICAL, tick(empty(6), 0, events(0, A, 9007199254740991L, 0)).state().level());
        var actualHeap = new JavaMemoryEvents(reading("900", "500", true), A, 0, 0, START.plusSeconds(5));
        assertEquals(Level.CRITICAL, tick(empty(6), 0, actualHeap).state().level());
        assertThrows(IllegalArgumentException.class, () -> tick(empty(5), 0, events(0, A, 0, 0)));
    }
}
