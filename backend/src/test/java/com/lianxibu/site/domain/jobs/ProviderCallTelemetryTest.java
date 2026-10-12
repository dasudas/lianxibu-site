package com.lianxibu.site.domain.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class ProviderCallTelemetryTest {
 @Test void nativeKnownCountsAndAgeRemainExactAndDiagnosticsRedacted(){var value=new ProviderCallTelemetry.Evidence(Instant.EPOCH,5,86401,2);assertEquals(5,value.unknownCalls());assertEquals(86401,value.oldestUnknownSeconds());assertEquals(2,value.configurationBlockedRoots());assertEquals("ProviderCallEvidence[redacted]",value.toString());assertEquals(0,new ProviderCallTelemetry.Evidence(Instant.EPOCH,0,0,0).unknownCalls());}
 @Test void missingClockBadRangeAndContradictoryZeroAreNotKnownTelemetry(){assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(null,0,0,0));assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(Instant.EPOCH.plusNanos(1),0,0,0));assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(Instant.EPOCH,-1,0,0));assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(Instant.EPOCH,0,1,0));assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(Instant.EPOCH,1,-1,0));assertThrows(IllegalArgumentException.class,()->new ProviderCallTelemetry.Evidence(Instant.EPOCH,0,0,9007199254740992L));}
}
