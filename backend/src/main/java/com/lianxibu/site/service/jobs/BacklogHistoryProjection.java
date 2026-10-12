package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.BacklogHistoryTelemetry;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;
/** Native history lower bounds and durable lifetime proofs; partial history never grants recovery. */
final class BacklogHistoryProjection {
 record Result(Backlog reading,boolean partial,boolean newEpoch,Map<String,Long> watermarks){}
 private static final String ZERO="0".repeat(64);private static final long MAX=9007199254740991L;
 static Result join(BacklogHistoryTelemetry.Evidence facts,State previous){
  if(facts==null||facts.mutation()==null)throw invalid();var mutation=facts.mutation();long read=micros(facts.readAt()),born=micros(mutation.startedAt()),changed=micros(mutation.changedAt());
  boolean required=previous.counters().containsKey("backlog_required");var old=previous.counters();boolean same=false;
  if(required){validate(previous);same=mutation.epoch().replace("-","").equals(words(old,"backlog_e",4));
   if(read<=old.get("backlog_read_micros")||same&&(born!=old.get("backlog_started_micros")||changed<old.get("backlog_changed_micros")||mutation.sequence()<old.get("backlog_sequence"))||!same&&born<=old.get("backlog_read_micros"))throw invalid();
  }
  long eligible=0,wall=0,rootBorn=0;String selected=ZERO;boolean partial=false;
  for(var entry:facts.entries()){
   long age=Duration.between(entry.createdAt(),facts.readAt()).getSeconds();wall=Math.max(wall,age);long actualBorn=micros(entry.createdAt());String id=sha(entry.rootId());long proven=0;
   if(entry.spans()==null)partial=true;else{
    var spans=new ArrayList<OperationalWaitingTime.Span>();
    for(var span:entry.spans()){
     boolean excluded=Boolean.FALSE.equals(span.ready())||Boolean.TRUE.equals(span.globallyPaused());
     boolean included=Boolean.TRUE.equals(span.ready())&&Boolean.FALSE.equals(span.globallyPaused());
     if(!excluded&&!included&&span.end().isAfter(span.start()))partial=true;
     spans.add(new OperationalWaitingTime.Span(span.start(),span.end(),included,false));
    }
    proven=OperationalWaitingTime.eligibleSeconds(spans);
   }
   if(required&&actualBorn==old.get("backlog_root_born_micros")&&id.equals(words(old,"backlog_r",8)))proven=Math.max(proven,old.get("backlog_eligible_seconds"));
   if(proven>age)throw invalid();
   if(selected.equals(ZERO)||proven>eligible||proven==eligible&&id.compareTo(selected)<0){eligible=proven;selected=id;rootBorn=actualBorn;}
  }
  var watermarks=new HashMap<String,Long>();watermarks.put("backlog_required",1L);watermarks.put("backlog_read_micros",read);watermarks.put("backlog_started_micros",born);watermarks.put("backlog_changed_micros",changed);watermarks.put("backlog_sequence",mutation.sequence());watermarks.put("backlog_root_born_micros",rootBorn);watermarks.put("backlog_eligible_seconds",eligible);watermarks.put("backlog_wall_seconds",wall);watermarks.put("backlog_partial",partial?1L:0L);
  put(watermarks,"backlog_e",mutation.epoch().replace("-",""));put(watermarks,"backlog_r",selected);put(watermarks,"backlog_f",sha(value(facts)));
  return new Result(new Backlog(eligible,wall,false),partial,required&&!same,Map.copyOf(watermarks));
 }
 static String value(BacklogHistoryTelemetry.Evidence facts){
  if(facts==null)return "null";var mapper=tools.jackson.databind.json.JsonMapper.shared();var value=mapper.createObjectNode();value.put("read",facts.readAt().toString());
  var mutation=facts.mutation();if(mutation==null)value.putNull("mutation");else{var m=value.putObject("mutation");m.put("epoch",mutation.epoch());m.put("started",mutation.startedAt().toString());m.put("changed",mutation.changedAt().toString());m.put("sequence",mutation.sequence());}
  var entries=value.putArray("entries");for(var entry:facts.entries().stream().sorted(Comparator.comparing(BacklogHistoryTelemetry.Entry::rootId)).toList()){
   var e=entries.addObject();e.put("root",entry.rootId());e.put("priority",entry.priority());e.put("state",entry.state());e.put("created",entry.createdAt().toString());e.put("read",entry.readAt().toString());
   if(entry.spans()==null)e.putNull("spans");else{var spans=e.putArray("spans");for(var span:entry.spans()){var s=spans.addObject();s.put("start",span.start().toString());s.put("end",span.end().toString());if(span.ready()==null)s.putNull("ready");else s.put("ready",span.ready());if(span.globallyPaused()==null)s.putNull("paused");else s.put("paused",span.globallyPaused());}}
  }return value.toString();
 }
 private static void validate(State previous){var values=previous.counters();if(!Long.valueOf(1).equals(values.get("backlog_required")))throw invalid();
  for(String key:List.of("backlog_read_micros","backlog_started_micros","backlog_changed_micros","backlog_sequence","backlog_root_born_micros","backlog_eligible_seconds","backlog_wall_seconds","backlog_partial")){Long v=values.get(key);if(v==null||v<0||v>MAX)throw invalid();}
  if(values.get("backlog_changed_micros")<values.get("backlog_started_micros")||values.get("backlog_read_micros")<values.get("backlog_changed_micros")||values.get("backlog_root_born_micros")>values.get("backlog_read_micros")||values.get("backlog_eligible_seconds")>values.get("backlog_wall_seconds")||values.get("backlog_partial")>1||words(values,"backlog_e",4)==null||words(values,"backlog_r",8)==null||words(values,"backlog_f",8)==null)throw invalid();
 }
 private static void put(Map<String,Long> values,String prefix,String hex){for(int i=0;i<hex.length()/8;i++)values.put(prefix+i,Long.parseUnsignedLong(hex.substring(i*8,i*8+8),16));}
 private static String words(Map<String,Long> values,String prefix,int count){var out=new StringBuilder();for(int i=0;i<count;i++){Long v=values.get(prefix+i);if(v==null||v<0||v>4294967295L)return null;out.append(String.format(Locale.ROOT,"%08x",v));}return out.toString();}
 private static long micros(Instant at){long value=Math.addExact(Math.multiplyExact(at.getEpochSecond(),1000000),at.getNano()/1000);if(value<0||value>MAX)throw invalid();return value;}
 private static String sha(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw invalid();}}
 private static IllegalStateException invalid(){return new IllegalStateException("native backlog history proof unavailable");}
}
