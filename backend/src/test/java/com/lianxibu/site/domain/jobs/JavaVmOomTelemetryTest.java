package com.lianxibu.site.domain.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JavaVmOomTelemetryTest {
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456Z"), NOW = START.plusSeconds(60);
    @Test void ActualCountersAndOriginalProcessBirthRemainExactAndRedacted() {
        var fact = new JavaVmOomTelemetry.Evidence(1234, START, NOW, 3, 2, Integer.MAX_VALUE);
        assertEquals(1234, fact.pid()); assertEquals(START, fact.startedAt()); assertEquals(NOW, fact.receivedAt());
        assertEquals(3, fact.javaHeapEvents()); assertEquals(2, fact.metaspaceEvents()); assertEquals(Integer.MAX_VALUE, fact.classMetaspaceEvents());
        assertEquals("JavaVmOomEvidence[redacted]", fact.toString());
    }
    @Test void ActualNativeZeroAndUnknownAreDifferent() {
        JavaVmOomTelemetry.Api actual = () -> new JavaVmOomTelemetry.Evidence(1, START, NOW, 0, 0, 0), unknown = () -> null;
        assertNotNull(actual.read()); assertEquals(0, actual.read().javaHeapEvents()); assertNull(unknown.read());
    }
    @Test void InvalidIdentityTimeAndNativeCounterRangeAreRejected() {
        for (long pid : new long[] {0, -1}) assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(pid, START, NOW, 0, 0, 0));
        for (Instant start : new Instant[] {null, START.plusNanos(1), NOW.plusSeconds(1), Instant.EPOCH.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(1, start, NOW, 0, 0, 0));
        for (Instant now : new Instant[] {null, NOW.plusNanos(1), START.minusSeconds(1), Instant.EPOCH.minusSeconds(1)})
            assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(1, START, now, 0, 0, 0));
        for (long count : new long[] {-1, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(1, START, NOW, count, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(1, START, NOW, 0, count, 0));
            assertThrows(IllegalArgumentException.class, () -> new JavaVmOomTelemetry.Evidence(1, START, NOW, 0, 0, count));
        }
    }
}
