package com.lianxibu.site.domain.jobs;
import java.time.Instant;
/** Current native node, exact original host-memory frame and controlled hard-budget evidence. */
public final class CollectorMemoryFacts {
 private CollectorMemoryFacts(){}
 public record Snapshot(String captureEpoch,String processIdentity,Instant captureStartedAt,long sequence,long pid,Instant observedAt,long rssBytes,String cgroupEpoch,Long maximumBytes,long oomEvents,long oomKills){
  public Snapshot{if(captureEpoch==null||!captureEpoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();sha(processIdentity);sha(cgroupEpoch);time(captureStartedAt);time(observedAt);safe(sequence,true);safe(pid,true);safe(rssBytes,true);safe(oomEvents,false);safe(oomKills,false);if(captureStartedAt.isAfter(observedAt))throw invalid();if(maximumBytes!=null)safe(maximumBytes,true);}
  @Override public String toString(){return "CollectorMemorySnapshot[redacted]";}
 }
 public record Evidence(String nodeId,long nodeRevision,boolean enabled,long expectedMaximumBytes,long historyRevision,Snapshot snapshot,Instant receivedAt,Instant readAt){
  public Evidence{id(nodeId);safe(nodeRevision,true);safe(expectedMaximumBytes,true);time(readAt);if(snapshot==null){if(historyRevision!=0||receivedAt!=null)throw invalid();}else{safe(historyRevision,true);time(receivedAt);if(snapshot.observedAt().isAfter(receivedAt)||receivedAt.isAfter(readAt))throw invalid();}}
  @Override public String toString(){return "CollectorMemoryFacts[redacted]";}
 }
 /** Null is an unavailable complete read; a null snapshot is actual missing current history. */
 @FunctionalInterface public interface Api{Evidence read(String nodeId);}
 private static void sha(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
 private static void id(String value){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>64||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw invalid();}
 private static void safe(long value,boolean positive){if(value<(positive?1:0)||value>9007199254740991L)throw invalid();}
 private static void time(Instant value){if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("collector native memory facts unavailable");}
}
