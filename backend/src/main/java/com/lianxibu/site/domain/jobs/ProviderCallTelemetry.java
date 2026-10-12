package com.lianxibu.site.domain.jobs;
import java.time.Instant;
/** Complete native unknown-call and blocked-configuration counts; no caller content or credential. */
public final class ProviderCallTelemetry {
 private ProviderCallTelemetry(){}
 public record Evidence(Instant receivedAt,long unknownCalls,long oldestUnknownSeconds,long configurationBlockedRoots){
  public Evidence {
   if(receivedAt==null||receivedAt.getEpochSecond()<0||receivedAt.getNano()%1000!=0||unknownCalls<0||unknownCalls>9007199254740991L
       ||oldestUnknownSeconds<0||oldestUnknownSeconds>9007199254740991L||configurationBlockedRoots<0||configurationBlockedRoots>9007199254740991L
       ||unknownCalls==0&&oldestUnknownSeconds!=0)throw new IllegalArgumentException("invalid native provider call facts");
  }
  @Override public String toString(){return "ProviderCallEvidence[redacted]";}
 }
 /** Null is unknown. Zero is valid only after a complete actual native query, never a startup default. */
 @FunctionalInterface public interface Api {Evidence read();}
}
