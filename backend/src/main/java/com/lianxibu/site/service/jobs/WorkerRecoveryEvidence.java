package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry.Evidence;
import java.time.Instant;
/** Fresh full native scans only. Failed scans below the configured alert threshold remain unknown. */
public final class WorkerRecoveryEvidence {
 public OperationalRuleEngine.SlotRecovery validate(Evidence evidence,Instant now,long failureThreshold){
  if(evidence==null||now==null||now.getEpochSecond()<0||now.getNano()%1000!=0||failureThreshold<1||failureThreshold>100
      ||evidence.receivedAt().isAfter(now)||evidence.receivedAt().isBefore(now.minusSeconds(120)))return null;
  if(!evidence.scanSuccessful()&&evidence.consecutiveFailedScans()<failureThreshold)return null;
  return new OperationalRuleEngine.SlotRecovery(evidence.snapshot().overdueSeconds(),evidence.consecutiveFailedScans(),evidence.scanSuccessful());
 }
}
