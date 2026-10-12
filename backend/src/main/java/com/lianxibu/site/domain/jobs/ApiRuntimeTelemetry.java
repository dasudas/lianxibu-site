package com.lianxibu.site.domain.jobs;
import java.time.*;
/** One original complete nonoverlapping process window; no default values for unavailable telemetry. */
public final class ApiRuntimeTelemetry {
 private ApiRuntimeTelemetry(){}
 public record Evidence(String processEpoch,Instant startedAt,Instant windowStart,Instant windowEnd,long requests,long serverErrors,long lockSamples,long lockWaitP95Nanos,Instant readAt){
  public Evidence{validate(processEpoch,startedAt,windowStart,windowEnd,requests,serverErrors,lockSamples,lockWaitP95Nanos,readAt);}
  @Override public String toString(){return "ApiRuntimeEvidence[redacted]";}
 }
 @FunctionalInterface public interface Api{Evidence read();}
 private static void validate(String epoch,Instant started,Instant start,Instant end,long requests,long errors,long locks,long p95,Instant read){
  if(epoch==null||!epoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("native API process identity required");for(Instant time:new Instant[]{started,start,end,read})if(time==null||time.getEpochSecond()<0||time.getNano()%1000!=0)throw new IllegalArgumentException("native API clock required");
  if(start.getNano()!=0||start.getEpochSecond()%300!=0||!end.equals(start.plusSeconds(300))||start.isBefore(started)||read.isBefore(end))throw new IllegalArgumentException("complete native API window required");
  for(long count:new long[]{requests,errors,locks,p95})if(count<0||count>9007199254740991L)throw new IllegalArgumentException("native API counts unavailable");if(errors>requests||locks==0&&p95!=0)throw new IllegalArgumentException("contradictory native API counts");
 }
}
