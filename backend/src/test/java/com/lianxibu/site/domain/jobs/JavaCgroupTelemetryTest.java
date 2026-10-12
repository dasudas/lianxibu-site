package com.lianxibu.site.domain.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JavaCgroupTelemetryTest {
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456Z"), NOW = START.plusSeconds(60);
    static final String EPOCH = "a".repeat(64);

    @Test void ExactResourceBirthCapacityAndLocalCountersAreRetained() {
        var sample = new JavaCgroupTelemetry.Evidence(1234, START, NOW, EPOCH, 536870913, 536870912L, 3, 9);
        assertEquals(1234, sample.pid()); assertEquals(START, sample.startedAt()); assertEquals(NOW, sample.receivedAt());
        assertEquals(EPOCH, sample.resourceEpoch()); assertEquals(536870913, sample.currentBytes());
        assertEquals(536870912L, sample.maximumBytes()); assertEquals(3, sample.oomEvents()); assertEquals(9, sample.oomKills());
        assertEquals("JavaCgroupEvidence[redacted]", sample.toString());
    }

    @Test void NativeUnlimitedAndZeroCountersAreSeparateFromUnknown() {
        var zero = new JavaCgroupTelemetry.Evidence(1, START, NOW, EPOCH, 0, null, 0, 0);
        assertNull(zero.maximumBytes()); assertEquals(0, zero.oomEvents()); assertEquals(0, zero.oomKills());
        JavaCgroupTelemetry.Api unavailable = () -> null, actual = () -> zero;
        assertNull(unavailable.read()); assertEquals(zero, actual.read());
    }

    @Test void InvalidIdentityTimeCapacityAndCountersAreRejected() {
        for (long pid : new long[] {0, -1}) assertThrows(IllegalArgumentException.class,
                () -> new JavaCgroupTelemetry.Evidence(pid, START, NOW, EPOCH, 1, null, 0, 0));
        for (Instant start : new Instant[] {null, START.plusNanos(1), NOW.plusSeconds(1), Instant.EPOCH.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, start, NOW, EPOCH, 1, null, 0, 0));
        for (Instant now : new Instant[] {null, NOW.plusNanos(1), START.minusSeconds(1), Instant.EPOCH.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, START, now, EPOCH, 1, null, 0, 0));
        for (String epoch : new String[] {null, "", "A".repeat(64), "g".repeat(64), "a".repeat(63)})
            assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, START, NOW, epoch, 1, null, 0, 0));
        for (Long max : new Long[] {0L, -1L}) assertThrows(IllegalArgumentException.class,
                () -> new JavaCgroupTelemetry.Evidence(1, START, NOW, EPOCH, 1, max, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, START, NOW, EPOCH, -1, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, START, NOW, EPOCH, 1, null, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new JavaCgroupTelemetry.Evidence(1, START, NOW, EPOCH, 1, null, 0, -1));
    }
}
