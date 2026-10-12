package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry;
import com.lianxibu.site.domain.jobs.ProviderCallTelemetry;
import java.time.Instant;
/** Both complete facts must be fresh. Account readiness does not prove repair of a failed prompt. */
public final class ProviderEvidence {
 public OperationalRuleEngine.Provider validate(ProviderReadinessTelemetry.Evidence account,ProviderCallTelemetry.Evidence calls,Instant now){
  if(account==null||calls==null||now==null||now.getEpochSecond()<0||now.getNano()%1000!=0
      ||!fresh(account.receivedAt(),now)||!fresh(calls.receivedAt(),now))return null;
  boolean failed=account.status()!=ProviderReadinessTelemetry.Status.READY||calls.configurationBlockedRoots()>0;
  return new OperationalRuleEngine.Provider(failed,calls.unknownCalls(),calls.oldestUnknownSeconds(),false);
 }
 private boolean fresh(Instant received,Instant now){return !received.isAfter(now)&&!received.isBefore(now.minusSeconds(120));}
}
