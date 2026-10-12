package com.lianxibu.site.domain.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JavaMemoryTelemetryTest {
    static final Instant NOW=Instant.parse("2026-10-10T10:00:00.123456Z");
    @Test void PhysicalBytesAndRealObservationTimeAreRetainedExactly(){var sample=new JavaMemoryTelemetry.Evidence(NOW,17179869184L,268435456L);assertEquals(NOW,sample.receivedAt());assertEquals(17179869184L,sample.totalBytes());assertEquals(268435456L,sample.availableBytes());assertEquals(0,new JavaMemoryTelemetry.Evidence(NOW,1,0).availableBytes());assertEquals(1,new JavaMemoryTelemetry.Evidence(NOW,1,1).availableBytes());}
    @Test void ImpossibleMissingOrOverpreciseFactsAreRejected(){assertThrows(IllegalArgumentException.class,()->new JavaMemoryTelemetry.Evidence(null,1,1));assertThrows(IllegalArgumentException.class,()->new JavaMemoryTelemetry.Evidence(NOW.plusNanos(1),1,1));assertThrows(IllegalArgumentException.class,()->new JavaMemoryTelemetry.Evidence(Instant.ofEpochSecond(-1),1,1));for(long[] pair:new long[][]{{0,0},{-1,0},{1,-1},{1,2}})assertThrows(IllegalArgumentException.class,()->new JavaMemoryTelemetry.Evidence(NOW,pair[0],pair[1]));}
    @Test void UnavailableSourceCannotBeConfusedWithActualZeroAvailableMemory(){JavaMemoryTelemetry.Api missing=()->null;JavaMemoryTelemetry.Api exhausted=()->new JavaMemoryTelemetry.Evidence(NOW,1,0);assertNull(missing.read());assertNotNull(exhausted.read());assertEquals(0,exhausted.read().availableBytes());}
    @Test void DiagnosticDoesNotExposeHostValuesOrCaptureTime(){var sample=new JavaMemoryTelemetry.Evidence(NOW,17179869184L,268435456L);assertEquals("JavaMemoryEvidence[redacted]",sample.toString());}
}
