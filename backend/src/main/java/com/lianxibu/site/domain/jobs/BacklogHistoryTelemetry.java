package com.lianxibu.site.domain.jobs;
import java.time.*;import java.util.*;
/** Raw durable readiness/pause intervals; absent history remains explicitly unavailable. */
public final class BacklogHistoryTelemetry {
 private BacklogHistoryTelemetry(){}
 public static final Set<String> PENDING=Set.of("waiting_input","prepared","queued","waiting_retry","running","blocked","blocked_configuration","unknown_external","result_conflict","cancel_requested");
 public record Span(Instant start,Instant end,Boolean ready,Boolean globallyPaused){public Span{time(start);time(end);if(end.isBefore(start)||Duration.between(start,end).toDays()>3660)throw invalid();}@Override public String toString(){return "BacklogHistorySpan[redacted]";}}
 public record Entry(String rootId,int priority,String state,Instant createdAt,Instant readAt,List<Span> spans){
  public Entry{id(rootId);time(createdAt);time(readAt);if(priority<1||priority>14||state==null||!PENDING.contains(state)||readAt.isBefore(createdAt)||Duration.between(createdAt,readAt).toDays()>3660)throw invalid();
   if(spans!=null){spans=List.copyOf(spans);if(spans.isEmpty()||spans.size()>10000)throw invalid();Instant cursor=createdAt;for(var span:spans){if(!span.start().equals(cursor)||span.end().isAfter(readAt))throw invalid();cursor=span.end();}if(!cursor.equals(readAt))throw invalid();}
  }
  @Override public String toString(){return "BacklogHistoryEntry[redacted]";}
 }
 public record Mutation(String epoch,Instant startedAt,Instant changedAt,long sequence){public Mutation{time(startedAt);time(changedAt);if(epoch==null||!epoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||changedAt.isBefore(startedAt)||sequence<0||sequence>9007199254740991L)throw invalid();}@Override public String toString(){return "BacklogMutationWitness[redacted]";}}
 public record Evidence(Instant readAt,List<Entry> entries,Mutation mutation){
  public Evidence(Instant readAt,List<Entry> entries){this(readAt,entries,null);}
  public Evidence{time(readAt);entries=List.copyOf(entries);if(entries.size()>4096||mutation!=null&&mutation.changedAt().isAfter(readAt))throw invalid();var ids=new HashSet<String>();for(var entry:entries)if(!entry.readAt().equals(readAt)||!ids.add(entry.rootId()))throw invalid();}
  @Override public String toString(){return "BacklogHistoryEvidence[redacted]";}
 }
 @FunctionalInterface public interface Api{Evidence read();}
 private static void id(String value){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>64||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256||value.codePoints().anyMatch(Character::isISOControl))throw invalid();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}}
 private static void time(Instant value){if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("native backlog history unavailable");}
}
