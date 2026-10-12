package com.lianxibu.site.domain.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class WorkerRecoveryTelemetryTest {
 @Test void actualFreeAndOccupiedFactsRemainSeparate(){
  var free=new WorkerRecoveryTelemetry.Snapshot(0,0,true);var live=new WorkerRecoveryTelemetry.Snapshot(0,1,false);var overdue=new WorkerRecoveryTelemetry.Snapshot(95,1,false);
  var success=new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH,free,0,true);var failed=new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH,overdue,3,false);
  assertTrue(success.scanSuccessful());assertTrue(success.snapshot().slotFree());assertFalse(live.slotFree());assertEquals(95,failed.snapshot().overdueSeconds());assertEquals(3,failed.consecutiveFailedScans());assertEquals("WorkerRecoveryEvidence[redacted]",failed.toString());assertEquals("WorkerRecoverySnapshot[redacted]",overdue.toString());
 }
 @Test void partialOrContradictoryScanIsNeverHealthy(){
  assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Snapshot(-1,1,false));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Snapshot(1,0,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Snapshot(0,1,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Snapshot(0,201,false));
  var free=new WorkerRecoveryTelemetry.Snapshot(0,0,true);
  assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Evidence(null,free,0,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH.plusNanos(1),free,0,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH,null,0,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH,free,1,true));assertThrows(IllegalArgumentException.class,()->new WorkerRecoveryTelemetry.Evidence(Instant.EPOCH,free,0,false));
 }
}
