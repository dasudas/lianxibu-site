package com.lianxibu.site.domain.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JavaVmMemoryTelemetryTest {
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456Z"), NOW = START.plusSeconds(60);
    @Test void OriginalBirthExplicitByteBudgetsAndNativeOomPolicyAreRetained() {
        var fact = new JavaVmMemoryTelemetry.Evidence(1234, START, NOW, 67108864, 268435456, 134217728, 33554432, 67108864, true, "a".repeat(64));
        assertEquals(1234, fact.pid()); assertEquals(START, fact.startedAt()); assertEquals(NOW, fact.receivedAt());
        assertEquals(67108864, fact.initialHeapBytes()); assertEquals(268435456, fact.maximumHeapBytes());
        assertEquals(134217728, fact.maximumMetaspaceBytes()); assertEquals(33554432, fact.maximumDirectBytes());
        assertEquals(67108864, fact.reservedCodeCacheBytes()); assertTrue(fact.exitOnOutOfMemoryError()); assertEquals("a".repeat(64), fact.oomHookSha256());
        assertEquals("JavaVmMemoryEvidence[redacted]", fact.toString());
    }
    @Test void ActualUnsetHookOrFalseExitPolicyIsSeparateFromMissingVmFacts() {
        var fact = new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, 1, 1, 1, 1, false, null);
        assertFalse(fact.exitOnOutOfMemoryError()); assertNull(fact.oomHookSha256());
        JavaVmMemoryTelemetry.Api known = () -> fact, missing = () -> null;
        assertEquals(fact, known.read()); assertNull(missing.read());
    }
    @Test void InvalidIdentityTimeBudgetAndHookProofCannotBecomeValid() {
        for (long pid : new long[] {0, -1}) assertThrows(IllegalArgumentException.class,
                () -> new JavaVmMemoryTelemetry.Evidence(pid, START, NOW, 1, 1, 1, 1, 1, true, null));
        for (Instant start : new Instant[] {null, START.plusNanos(1), NOW.plusSeconds(1), Instant.EPOCH.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, start, NOW, 1, 1, 1, 1, 1, true, null));
        for (Instant now : new Instant[] {null, NOW.plusNanos(1), START.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, now, 1, 1, 1, 1, 1, true, null));
        for (long bad : new long[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, bad, 1, 1, 1, 1, true, null));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, bad, 1, 1, 1, true, null));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, 1, bad, 1, 1, true, null));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, 1, 1, bad, 1, true, null));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, 1, 1, 1, bad, true, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 2, 1, 1, 1, 1, true, null));
        for (String hook : new String[] {"", "A".repeat(64), "a".repeat(63), "g".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> new JavaVmMemoryTelemetry.Evidence(1, START, NOW, 1, 1, 1, 1, 1, true, hook));
    }
}
