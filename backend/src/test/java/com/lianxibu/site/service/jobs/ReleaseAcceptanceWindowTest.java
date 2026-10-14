package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseAcceptanceWindowTest {
    private static final Instant ISSUED = Instant.parse("2026-01-02T03:00:00.000000Z");
    private static final Instant UNTIL = Instant.parse("2026-01-02T05:00:00.000000Z");
    private static Map<String, String> grant() {
        return Map.of("SITE_AI_ACCEPTANCE_RELEASE", "2099010101",
                "SITE_AI_ACCEPTANCE_ISSUED_AT", ISSUED.toString(),
                "SITE_AI_ACCEPTANCE_UNTIL", UNTIL.toString());
    }

    @Test void absentPermissionKeepsNormalShanghaiBoundaryAndNextDay() {
        var normal = ReleaseAcceptanceWindow.fromEnvironment(Map.of());
        assertFalse(normal.isOpen(Instant.parse("2026-01-01T16:59:59Z")));
        assertTrue(normal.isOpen(Instant.parse("2026-01-01T17:00:00Z")));
        assertTrue(normal.isOpen(Instant.parse("2026-01-01T22:59:59.999999Z")));
        assertFalse(normal.isOpen(Instant.parse("2026-01-01T23:00:00Z")));
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"), normal.nextEligible(ISSUED));
    }

    @Test void validHostPermissionUsesRealTimeAndEndsExclusively() {
        var policy = ReleaseAcceptanceWindow.fromEnvironment(grant());
        assertFalse(policy.isOpen(ISSUED.minusNanos(1000)));
        assertTrue(policy.isOpen(ISSUED));
        assertTrue(policy.isOpen(UNTIL.minusNanos(1000)));
        assertFalse(policy.isOpen(UNTIL));
        assertEquals(ISSUED, policy.nextEligible(ISSUED));
        assertEquals(ISSUED, policy.nextWindow(ISSUED));
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"), policy.nextEligible(UNTIL));
        assertEquals("ReleaseAcceptanceWindow[redacted]", policy.toString());
    }

    @Test void permissionCannotRemoveNormalWindowAndDoesNotReadLaterMapChanges() {
        var values = new HashMap<>(grant());
        var policy = ReleaseAcceptanceWindow.fromEnvironment(values);
        values.clear();
        assertTrue(policy.isOpen(ISSUED));
        assertTrue(policy.isOpen(Instant.parse("2026-01-02T17:00:00Z")));
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"), policy.nextEligible(Instant.parse("2026-01-02T17:00:00Z")));
    }

    @Test void partialInvalidOrUnlimitedPermissionFailsClosed() {
        for (String key : grant().keySet()) {
            var values = new HashMap<>(grant()); values.remove(key);
            assertThrows(IllegalArgumentException.class, () -> ReleaseAcceptanceWindow.fromEnvironment(values));
        }
        for (var change : Map.of("SITE_AI_ACCEPTANCE_RELEASE", "true",
                "SITE_AI_ACCEPTANCE_ISSUED_AT", "tomorrow",
                "SITE_AI_ACCEPTANCE_UNTIL", "forever").entrySet()) {
            var values = new HashMap<>(grant()); values.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> ReleaseAcceptanceWindow.fromEnvironment(values));
        }
        for (String end : new String[]{ISSUED.toString(), ISSUED.minusSeconds(1).toString(),
                ISSUED.plusSeconds(43201).toString(), "2026-01-02T05:00:00.000000001Z"}) {
            var values = new HashMap<>(grant()); values.put("SITE_AI_ACCEPTANCE_UNTIL", end);
            assertThrows(IllegalArgumentException.class, () -> ReleaseAcceptanceWindow.fromEnvironment(values));
        }
        var maximum = new HashMap<>(grant()); maximum.put("SITE_AI_ACCEPTANCE_UNTIL", ISSUED.plusSeconds(43200).toString());
        assertTrue(ReleaseAcceptanceWindow.fromEnvironment(maximum).isOpen(ISSUED.plusSeconds(43199)));
    }
}
