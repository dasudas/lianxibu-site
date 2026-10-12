package com.lianxibu.site.domain.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProviderReadinessTelemetryTest {
    @ParameterizedTest @EnumSource(ProviderReadinessTelemetry.Status.class)
    void preservesActualTimeAndExplicitStatusWithoutDiagnosticDetails(ProviderReadinessTelemetry.Status status) {
        var now=Instant.parse("2026-01-01T00:00:00.123456Z");
        var fact=new ProviderReadinessTelemetry.Evidence(now,status);
        assertEquals(now,fact.receivedAt());assertEquals(status,fact.status());
        assertEquals("ProviderReadinessEvidence[redacted]",fact.toString());
    }
    @Test void missingOrUnqualifiedFactsCannotBecomeReady() {
        assertThrows(IllegalArgumentException.class,()->new ProviderReadinessTelemetry.Evidence(null,ProviderReadinessTelemetry.Status.READY));
        assertThrows(IllegalArgumentException.class,()->new ProviderReadinessTelemetry.Evidence(Instant.EPOCH,null));
        assertThrows(IllegalArgumentException.class,()->new ProviderReadinessTelemetry.Evidence(Instant.EPOCH.minusNanos(1000),ProviderReadinessTelemetry.Status.READY));
        assertThrows(IllegalArgumentException.class,()->new ProviderReadinessTelemetry.Evidence(Instant.EPOCH.plusNanos(1),ProviderReadinessTelemetry.Status.READY));
    }
}
