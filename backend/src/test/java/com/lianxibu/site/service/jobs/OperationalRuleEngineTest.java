package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.settings.OperationalRulePolicy;
import com.lianxibu.site.service.settings.OperationalRulePolicy.Compiled;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class OperationalRuleEngineTest {
    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static final OperationalRuleEngine ENGINE = new OperationalRuleEngine();
    static Compiled config(int id) { return OperationalRulePolicy.compile(MonitoringFixtures.complete().get(id - 1)); }
    static State empty(int id) { return State.empty(new Key(String.format("OPS-%02d", id), "fixture", "fictional-resource")); }
    static Transition tick(State state, int minute, Reading reading) {
        return ENGINE.sample(state, config(Integer.parseInt(state.key().ruleId().substring(4))), 1,
                START.plusSeconds(minute * 60L), true, reading);
    }
    static BigDecimal decimal(String value) { return new BigDecimal(value); }
    @Test void allTenRulesTriggerOnlyTheirDefinedProtections() {
        State backlog = tick(empty(1), 0, new Backlog(0, 604800, false)).state();
        assertEquals(Level.CRITICAL, backlog.level()); assertTrue(backlog.guards().isEmpty());
        assertNull(tick(empty(2), 0, new Heartbeat(301, true, false, false)).state().level());
        State heartbeat = tick(empty(2), 0, new Heartbeat(901, true, false, false)).state();
        assertEquals(Level.CRITICAL, heartbeat.level());
        assertEquals(java.util.Set.of(Guard.DISPATCH_AI), tick(empty(3), 0, new SlotRecovery(61, 0, false)).state().guards());
        assertEquals(4, tick(empty(4), 0, new Capacity(decimal("4.9"))).state().guards().size());
        assertEquals(java.util.Set.of(Guard.NEW_FETCH), tick(empty(5), 0, new Disk(decimal("384"), decimal("2000"))).state().guards());
        State memory = tick(empty(6), 0, new Memory(true, decimal("199"), false)).state();
        memory = tick(memory, 1, new Memory(true, decimal("199"), false)).state();
        assertEquals(java.util.Set.of(Guard.DISPATCH_AI), memory.guards());
        assertEquals(Level.CRITICAL, tick(empty(6), 0, new Memory(false, decimal("192"), false)).state().level());
        State errors = tick(empty(7), 0, new ContractFailures(1, 3, false)).state();
        assertEquals(java.util.Set.of(Guard.RETRY_MEMBER, Guard.NEW_FETCH), errors.guards());
        State providerUnknown = tick(empty(8), 0, new Provider(false, 3, 100, false)).state();
        assertEquals(Level.WARNING, providerUnknown.level()); assertTrue(providerUnknown.guards().isEmpty());
        assertEquals(java.util.Set.of(Guard.DISPATCH_AI), tick(empty(8), 0, new Provider(true, 0, 0, false)).state().guards());
        assertEquals(Level.CRITICAL, tick(empty(9), 0, new ApiHealth(100, decimal("10"), decimal("1"), START)).state().level());
        assertEquals(4, tick(empty(10), 0, new Telemetry(601, 601)).state().guards().size());
    }
    @Test void warningWindowsAndDistinctApiWindowsDoNotCountRepeatedSamples() {
        State backlog = empty(1);
        for (int m = 0; m < 10; m++) { backlog = tick(backlog, m, new Backlog(7200, 10000, false)).state(); assertNull(backlog.level()); }
        assertEquals(Level.WARNING, tick(backlog, 10, new Backlog(7200, 10000, false)).state().level());
        State heartbeat = tick(empty(2), 0, new Heartbeat(301, true, false, false)).state();
        assertEquals(Level.WARNING, tick(heartbeat, 1, new Heartbeat(301, true, false, false)).state().level());
        State api = empty(9);
        for (int m = 0; m < 5; m++) {
            api = tick(api, m, new ApiHealth(100, decimal("2"), decimal("1"), START)).state(); assertNull(api.level());
        }
        assertEquals(Level.WARNING, tick(api, 5, new ApiHealth(100, decimal("2"), decimal("1"), START.plusSeconds(300))).state().level());
    }
    @Test void capacityRequiresThirtyContinuousMinutesAndMissingSamplesCannotRecover() {
        State state = tick(empty(4), 0, new Capacity(decimal("4"))).state();
        assertFalse(tick(state, 1, new Capacity(decimal("15"))).state().recoveryReady());
        state = ENGINE.sample(state, config(4), 1, START.plusSeconds(60), false, null).state();
        assertEquals(Status.TELEMETRY_UNKNOWN, state.status()); assertNull(state.observedValue()); assertEquals(4, state.guards().size());
        for (int m = 2; m < 32; m++) { state = tick(state, m, new Capacity(decimal("15"))).state(); assertFalse(state.recoveryReady()); }
        state = tick(state, 32, new Capacity(decimal("15"))).state();
        assertTrue(state.recoveryReady()); assertEquals(4, state.guards().size());
        assertFalse(tick(state, 34, new Capacity(decimal("15"))).state().recoveryReady());
        State unconfigured = ENGINE.sample(state, null, 2, START.plusSeconds(33 * 60), true, new Capacity(decimal("99"))).state();
        assertEquals(Status.CONFIGURATION_UNKNOWN, unconfigured.status()); assertEquals(4, unconfigured.guards().size());
    }
    @Test void providerBlockSurvivesSampleGapAndUnknownDataUntilVerifiedRepair() {
        State state = tick(empty(8), 0, new Provider(true, 0, 0, false)).state();
        state = ENGINE.sample(state, config(8), 1, START.plusSeconds(60), false, null).state();
        state = tick(state, 4, new Provider(false, 0, 0, false)).state();
        assertTrue(state.guards().contains(Guard.DISPATCH_AI));
        state = tick(state, 5, new Provider(false, 3, 0, true)).state();
        assertFalse(state.guards().contains(Guard.DISPATCH_AI));
    }
    @Test void reminderResolutionAndNoRequestWindowsAreExplicit() {
        State state = tick(empty(9), 0, new ApiHealth(100, decimal("10"), decimal("0"), START)).state();
        for (int m = 1; m < 30; m++) state = tick(state, m, new ApiHealth(100, decimal("10"), decimal("0"), START.plusSeconds((m/5)*300))).state();
        Transition reminder = tick(state, 30, new ApiHealth(100, decimal("10"), decimal("0"), START.plusSeconds(1800)));
        assertEquals("reminder", reminder.notices().getFirst().action()); state = reminder.state();
        for (int m = 31; m < 45; m++) state = tick(state, m, new ApiHealth(0, decimal("0"), decimal("0"), START.plusSeconds((m/5)*300))).state();
        assertEquals(Level.CRITICAL, state.level());
        for (int m = 45; m < 55; m++) state = tick(state, m, new ApiHealth(1, decimal("0"), decimal("0"), START.plusSeconds((m/5)*300))).state();
        Transition recovered = tick(state, 55, new ApiHealth(1, decimal("0"), decimal("0"), START.plusSeconds(3300)));
        assertEquals("resolved", recovered.notices().getFirst().action()); assertNull(recovered.state().level());
        assertTrue(tick(recovered.state(), 56, new ApiHealth(1, decimal("0"), decimal("0"), START.plusSeconds(3300))).notices().isEmpty());
    }
    @Test void missingSampleCannotReleaseProviderBlockOnNextConsecutiveTick() {
        State state = tick(empty(8), 0, new Provider(true, 0, 0, false)).state();
        state = ENGINE.sample(state, config(8), 1, START.plusSeconds(60), false, null).state();
        state = tick(state, 2, new Provider(false, 0, 0, false)).state();
        assertTrue(state.guards().contains(Guard.DISPATCH_AI));
        assertEquals(Level.CRITICAL, state.level());
        assertEquals(Level.CRITICAL, tick(empty(10), 0, new Telemetry(0, 601)).state().level());
    }
    @Test void resourceRecoveryRequiresItsWindowAndReconciliationOrRevalidation() {
        State heartbeat = tick(empty(2), 0, new Heartbeat(901, true, false, false)).state();
        for (int m = 1; m <= 3; m++) heartbeat = tick(heartbeat, m, new Heartbeat(0, true, true, false)).state();
        assertNotNull(heartbeat.level());
        assertNull(tick(heartbeat, 4, new Heartbeat(0, true, true, true)).state().level());
        State slot = tick(empty(3), 0, new SlotRecovery(0, 3, false)).state();
        for (int m = 1; m < 4; m++) { slot = tick(slot, m, new SlotRecovery(0, 0, true)).state(); assertFalse(slot.guards().isEmpty()); }
        assertTrue(tick(slot, 4, new SlotRecovery(0, 0, true)).state().guards().isEmpty());
        State contract = tick(empty(7), 0, new ContractFailures(1, 1, false)).state();
        for (int m = 1; m <= 31; m++) contract = tick(contract, m, new ContractFailures(0, 0, false)).state();
        assertTrue(contract.guards().contains(Guard.RETRY_MEMBER));
        assertTrue(tick(contract, 32, new ContractFailures(0, 0, true)).state().guards().isEmpty());
        State missing = tick(empty(10), 0, new Telemetry(601, 601)).state();
        for (int m = 1; m < 4; m++) { missing = tick(missing, m, new Telemetry(10, 10)).state(); assertFalse(missing.guards().isEmpty()); }
        assertTrue(tick(missing, 4, new Telemetry(10, 10)).state().guards().isEmpty());
    }

}
