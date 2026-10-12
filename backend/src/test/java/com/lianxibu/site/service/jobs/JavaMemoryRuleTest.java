package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static com.lianxibu.site.service.jobs.OperationalRuleEngineTest.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaMemoryRuleTest {
    static JavaMemory reading(String available, String rss, boolean oom) {
        return new JavaMemory(decimal(available), decimal(rss), decimal("640"), oom);
    }

    @Test void NativeRssAtArtifactHardLimitImmediatelyProtectsEvenWithAvailableHostMemory() {
        assertNull(tick(empty(6), 0, reading("900", "639.999999", false)).state().level());
        var trigger = tick(empty(6), 0, reading("900", "640", false));
        assertEquals(Level.CRITICAL, trigger.state().level()); assertEquals(Set.of(Guard.DISPATCH_AI), trigger.state().guards());
        assertEquals("640", trigger.state().observedValue()); assertEquals("640", trigger.state().threshold());
        assertEquals("trigger", trigger.notices().getFirst().action());
        assertEquals(Level.CRITICAL, tick(empty(6), 0, reading("900", "640.000001", false)).state().level());
    }

    @Test void OriginalHostWarningHoldsAndUpgradesToRssCriticalWhileOomHasItsOwnEvidence() {
        var state = tick(empty(6), 0, reading("199.999999", "500", false)).state(); assertNull(state.level());
        state = tick(state, 1, reading("199.999999", "500", false)).state(); assertEquals(Level.WARNING, state.level());
        var upgrade = tick(state, 2, reading("900", "640", false)); assertEquals(Level.CRITICAL, upgrade.state().level());
        assertEquals("upgrade", upgrade.notices().getFirst().action());
        var oom = tick(empty(6), 0, reading("900", "1", true)); assertEquals(Level.CRITICAL, oom.state().level());
        assertEquals("1", oom.state().observedValue()); assertEquals("1", oom.state().threshold());
        assertNull(tick(tick(empty(6), 0, reading("200", "500", false)).state(), 1, reading("200", "500", false)).state().level());
    }

    @Test void RecoveryRequiresTenContinuousMinutesOfBothHostAndRssHealth() {
        var state = tick(empty(6), 0, reading("900", "640", false)).state();
        for (int minute = 1; minute < 12; minute++) state = tick(state, minute, reading("900", "640", false)).state();
        assertEquals(Level.CRITICAL, state.level());
        for (int minute = 12; minute < 22; minute++) {
            state = tick(state, minute, reading("250", "639.999999", false)).state(); assertEquals(Level.CRITICAL, state.level());
        }
        var recovery = tick(state, 22, reading("250", "639.999999", false));
        assertNull(recovery.state().level()); assertTrue(recovery.state().guards().isEmpty());
        assertEquals("resolved", recovery.notices().getFirst().action());
        state = tick(recovery.state(), 23, reading("900", "500", true)).state();
        for (int minute = 24; minute < 40; minute++) state = tick(state, minute, reading("249.999999", "500", false)).state();
        assertEquals(Level.CRITICAL, state.level());
    }

    @Test void MissingOrDowngradedRssCannotReleaseProtectionAcrossGapConfigurationAndRestart() {
        var state = tick(empty(6), 0, reading("900", "640", false)).state();
        assertEquals(1L, state.counters().get("java_rss_required"));
        state = ENGINE.sample(state, config(6), 1, START.plusSeconds(60), false, null).state();
        assertEquals(Status.TELEMETRY_UNKNOWN, state.status()); assertEquals(1L, state.counters().get("java_rss_required"));
        state = ENGINE.sample(state, null, 2, START.plusSeconds(120), true, reading("900", "500", false)).state();
        assertEquals(Status.CONFIGURATION_UNKNOWN, state.status()); assertEquals(1L, state.counters().get("java_rss_required"));
        var engine = new OperationalRuleEngine();
        for (int minute = 10; minute < 25; minute++) {
            state = engine.sample(state, config(6), 2, START.plusSeconds(minute * 60L), true, new Memory(true, decimal("900"), false)).state();
            assertEquals(Status.TELEMETRY_UNKNOWN, state.status()); assertEquals(Level.CRITICAL, state.level());
            assertEquals(Set.of(Guard.DISPATCH_AI), state.guards()); assertEquals(1L, state.counters().get("java_rss_required"));
        }
        for (int minute = 25; minute < 35; minute++) {
            state = engine.sample(state, config(6), 2, START.plusSeconds(minute * 60L), true, reading("250", "500", false)).state();
            assertEquals(Level.CRITICAL, state.level());
        }
        state = engine.sample(state, config(6), 2, START.plusSeconds(35 * 60L), true, reading("250", "500", false)).state();
        assertNull(state.level()); assertEquals(1L, state.counters().get("java_rss_required"));
    }

    @Test void InvalidAndWrongRuleReadingsAreRejectedWithoutTreatingMissingDataAsHealthy() {
        for (BigDecimal invalid : new BigDecimal[] {null, decimal("-1"), decimal("9007199254740992"), decimal("0.000000001")}) {
            assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, new JavaMemory(invalid, decimal("1"), decimal("640"), false)));
            assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, new JavaMemory(decimal("900"), invalid, decimal("640"), false)));
            assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, new JavaMemory(decimal("900"), decimal("1"), invalid, false)));
        }
        assertThrows(IllegalArgumentException.class, () -> tick(empty(6), 0, new JavaMemory(decimal("900"), decimal("1"), BigDecimal.ZERO, false)));
        assertThrows(IllegalArgumentException.class, () -> tick(empty(5), 0, reading("900", "1", false)));
        assertEquals(Status.TELEMETRY_UNKNOWN, ENGINE.sample(empty(6), config(6), 1, START, false, null).state().status());
    }

    @Test void OriginalMemoryCommandShapeAndCollectorBehaviorRemainCompatible() {
        assertEquals("Memory[javaNode=true, measuredMib=900, outOfMemory=false]", new Memory(true, decimal("900"), false).toString());
        assertNull(tick(empty(6), 0, new Memory(true, decimal("900"), false)).state().level());
        assertEquals(Level.CRITICAL, tick(empty(6), 0, new Memory(false, decimal("192"), false)).state().level());
        assertEquals(Set.of(Guard.NEW_FETCH), tick(empty(6), 0, new Memory(false, decimal("192"), false)).state().guards());
    }

    @Test void SerializedRssRequirementSurvivesRestartAndRefusesCollectorShapeForSameJavaResource() {
        var original = tick(empty(6), 0, reading("900", "640", false)).state();
        var bytes = OperationalStateCodec.JSON.writeValueAsBytes(OperationalStateCodec.encode(original));
        var restored = OperationalStateCodec.decode(OperationalStateCodec.JSON.readTree(bytes));
        assertEquals(original, restored);
        var after = new OperationalRuleEngine().sample(restored, config(6), 1, START.plusSeconds(60), true,
                new Memory(false, decimal("100"), false)).state();
        assertEquals(Status.TELEMETRY_UNKNOWN, after.status()); assertEquals(Level.CRITICAL, after.level());
        assertEquals(Set.of(Guard.DISPATCH_AI), after.guards()); assertEquals(1L, after.counters().get("java_rss_required"));
    }
}
