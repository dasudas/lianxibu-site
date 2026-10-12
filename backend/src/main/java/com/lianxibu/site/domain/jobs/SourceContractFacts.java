package com.lianxibu.site.domain.jobs;
import java.time.Instant;
/** Complete current authorized-node source facts; missing input is unknown. */
public final class SourceContractFacts {
 private SourceContractFacts(){}
 public record Evidence(String sourceId,long configVersion,String resourceEpoch,long totalFailures,long eventsInWindow,int nodeCount,
                        Instant observedAt,Instant snapshotReceivedAt,Instant readAt,Instant reviewedAt){
  public Evidence {
   id(sourceId);safe(configVersion,true);safe(totalFailures,false);safe(eventsInWindow,false);
   if(resourceEpoch==null||!resourceEpoch.matches("[0-9a-f]{64}")||nodeCount<1||nodeCount>128||eventsInWindow>totalFailures||eventsInWindow>200L*nodeCount)throw invalid();
   time(observedAt);time(snapshotReceivedAt);time(readAt);time(reviewedAt);
   if(observedAt.isAfter(snapshotReceivedAt)||snapshotReceivedAt.isAfter(readAt)||reviewedAt.isAfter(readAt))throw invalid();
  }
  @Override public String toString(){return "SourceContractFacts[redacted]";}
 }
 /** All current enabled source-bound nodes and the native manual admission must be proven. */
 @FunctionalInterface public interface Api {Evidence read(String sourceId);}
 private static void id(String value){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>80||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>320||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw invalid();}
 private static void safe(long value,boolean positive){if(value<(positive?1:0)||value>9007199254740991L)throw invalid();}
 private static void time(Instant value){if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("source contract facts unavailable");}
}
