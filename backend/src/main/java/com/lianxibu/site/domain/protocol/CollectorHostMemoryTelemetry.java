package com.lianxibu.site.domain.protocol;
import java.nio.charset.StandardCharsets;import java.time.Instant;import java.util.Set;
/** Independent native collector host memory snapshot; original heartbeat bytes remain unchanged. */
public final class CollectorHostMemoryTelemetry {
 private CollectorHostMemoryTelemetry(){}public static final String VERSION="collector-host-memory-v1";
 public record Sample(String captureEpoch,String processIdentity,Instant captureStartedAt,long sequence,long pid,Instant observedAt,long rssBytes,String cgroupEpoch,Long maximumBytes,long oomEvents,long oomKills){
  public Sample{if(captureEpoch==null||!captureEpoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();sha(processIdentity);sha(cgroupEpoch);time(captureStartedAt);time(observedAt);safe(sequence);safe(pid);safe(rssBytes);safe(oomEvents);safe(oomKills);if(sequence==0||pid==0||rssBytes==0||captureStartedAt.isAfter(observedAt))throw invalid();if(maximumBytes!=null){safe(maximumBytes);if(maximumBytes==0)throw invalid();}}
  @Override public String toString(){return "CollectorHostMemorySample[redacted]";}
 }
 public record Parsed(Sample sample,String canonical,String payloadHash){@Override public String toString(){return "CollectorHostMemoryParsed[redacted]";}}
 public static Parsed parse(byte[] raw){
  if(raw==null||raw.length==0||raw.length>8192)throw invalid();var fields=Set.of("schema_version","capture_epoch","process_identity","capture_started_at","sequence","pid","observed_at","rss_bytes","cgroup_epoch","maximum_bytes","oom_events","oom_kills");var root=WireValues.object(CanonicalJson.parse(raw),fields,fields);if(!VERSION.equals(WireValues.text(root.get("schema_version"))))throw invalid();var maximum=root.get("maximum_bytes");
  var sample=new Sample(WireValues.text(root.get("capture_epoch")),WireValues.sha256(root.get("process_identity")),WireValues.utcMicroseconds(root.get("capture_started_at")),WireValues.nonnegativeInteger(root.get("sequence")),WireValues.nonnegativeInteger(root.get("pid")),WireValues.utcMicroseconds(root.get("observed_at")),WireValues.nonnegativeInteger(root.get("rss_bytes")),WireValues.sha256(root.get("cgroup_epoch")),maximum.isNull()?null:WireValues.nonnegativeInteger(maximum),WireValues.nonnegativeInteger(root.get("oom_events")),WireValues.nonnegativeInteger(root.get("oom_kills")));
  return new Parsed(sample,new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8),CanonicalJson.hash(CanonicalJson.Domain.ACTION,root));
 }
 private static void safe(long value){if(value<0||value>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();}
 private static void time(Instant value){if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();}
 private static void sha(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid collector host memory telemetry");}
}
