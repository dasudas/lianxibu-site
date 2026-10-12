package com.lianxibu.site.domain.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class JavaProcessTelemetryTest {
    static final Instant START=Instant.parse("2026-10-10T10:00:00.123456Z"),NOW=START.plusSeconds(60);
    @Test void ActualBirthIdentityAndKernelReportedRssArePreserved(){var value=new JavaProcessTelemetry.Evidence(1234,START,NOW,123456789);assertEquals(1234,value.pid());assertEquals(START,value.startedAt());assertEquals(NOW,value.receivedAt());assertEquals(123456789,value.residentBytes());assertEquals("JavaProcessEvidence[redacted]",value.toString());}
    @Test void InvalidIdentityMissingBirthAndImpossibleTimeOrRssAreRejected(){for(long pid:new long[]{0,-1})assertThrows(IllegalArgumentException.class,()->new JavaProcessTelemetry.Evidence(pid,START,NOW,1));for(Instant start:new Instant[]{null,START.plusNanos(1),NOW.plusSeconds(1),Instant.ofEpochSecond(-1)})assertThrows(IllegalArgumentException.class,()->new JavaProcessTelemetry.Evidence(1,start,NOW,1));for(Instant now:new Instant[]{null,NOW.plusNanos(1),Instant.ofEpochSecond(-1)})assertThrows(IllegalArgumentException.class,()->new JavaProcessTelemetry.Evidence(1,START,now,1));assertThrows(IllegalArgumentException.class,()->new JavaProcessTelemetry.Evidence(1,START,NOW,-1));}
    @Test void UnknownSourceIsSeparateFromAnActualZeroResidentReading(){JavaProcessTelemetry.Api missing=()->null;JavaProcessTelemetry.Api zero=()->new JavaProcessTelemetry.Evidence(1,START,NOW,0);assertNull(missing.read());assertEquals(0,zero.read().residentBytes());}
}
