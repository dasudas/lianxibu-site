package com.lianxibu.site.domain.protocol;
import com.lianxibu.site.domain.protocol.CanonicalJson;import com.lianxibu.site.domain.protocol.WireValues;
import java.time.*;import java.util.*;import tools.jackson.databind.JsonNode;
/** Closed source health snapshot. Zero requires a complete native collector journal, never an omitted field. */
public final class SourceContractTelemetry {
 private SourceContractTelemetry(){}
 public record Event(long sequence,Instant occurredAt){public Event{safe(sequence,true);time(occurredAt);} @Override public String toString(){return "SourceFailureEvent[redacted]";}}
 public record Snapshot(String sourceId,long configVersion,String epoch,long sequence,Instant observedAt,Instant windowStart,long totalFailures,List<Event> events){
  public Snapshot {WireValues.id(sourceId,true);safe(configVersion,true);safe(sequence,true);safe(totalFailures,false);time(observedAt);time(windowStart);if(epoch==null||!epoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||!windowStart.equals(observedAt.minusSeconds(600))||events==null||events.size()>200)throw invalid();events=List.copyOf(events);long last=0;Instant at=windowStart;for(var event:events){if(event==null||event.sequence()<=last||event.sequence()>totalFailures||!event.occurredAt().isAfter(windowStart)||event.occurredAt().isBefore(at)||event.occurredAt().isAfter(observedAt))throw invalid();last=event.sequence();at=event.occurredAt();}if(totalFailures==0&&!events.isEmpty())throw invalid();}
  @Override public String toString(){return "SourceContractSnapshot[redacted]";}
 }
 public record Parsed(Snapshot snapshot,String canonical,String payloadHash){@Override public String toString(){return "SourceContractParsed[redacted]";}}
 public static Parsed parse(byte[] raw){
  if(raw==null||raw.length==0||raw.length>32768)throw invalid();var fields=Set.of("schema_version","source_id","config_version","epoch","sequence","observed_at","window_start","total_failures","events");
  JsonNode root=WireValues.object(CanonicalJson.parse(raw),fields,fields);if(!"source-contract-telemetry-v1".equals(WireValues.text(root.get("schema_version"))))throw invalid();var list=root.get("events");if(!list.isArray()||list.size()>200)throw invalid();var events=new ArrayList<Event>();for(var item:list){var keys=Set.of("sequence","occurred_at");WireValues.object(item,keys,keys);events.add(new Event(WireValues.nonnegativeInteger(item.get("sequence")),WireValues.utcMicroseconds(item.get("occurred_at"))));}
  var snapshot=new Snapshot(WireValues.id(root.get("source_id"),true),WireValues.nonnegativeInteger(root.get("config_version")),WireValues.text(root.get("epoch")),WireValues.nonnegativeInteger(root.get("sequence")),WireValues.utcMicroseconds(root.get("observed_at")),WireValues.utcMicroseconds(root.get("window_start")),WireValues.nonnegativeInteger(root.get("total_failures")),events);
  return new Parsed(snapshot,new String(CanonicalJson.bytes(root),java.nio.charset.StandardCharsets.UTF_8),CanonicalJson.hash(CanonicalJson.Domain.ACTION,root));
 }
 private static void time(Instant time){if(time==null||time.getEpochSecond()<0||time.getNano()%1000!=0)throw invalid();}
 private static void safe(long value,boolean positive){if(value<(positive?1:0)||value>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid source contract telemetry");}
}
