package com.lianxibu.site.domain.jobs;
import java.time.Instant;
/** Current native node and original heartbeat facts. A missing heartbeat is distinct from an empty queue. */
public final class CollectorNodeFacts {
    private CollectorNodeFacts(){}
    public record Heartbeat(long nodeRevision,long historyRevision,Instant observedAt,Instant receivedAt,
                            long pendingObservations,long pendingControls,long quarantined,
                            long outboxBytes,long freeBytes,Instant oldestPendingAt){
        public Heartbeat {safe(nodeRevision,true);safe(historyRevision,true);time(observedAt);time(receivedAt);safe(pendingObservations,false);safe(pendingControls,false);safe(quarantined,false);safe(outboxBytes,false);safe(freeBytes,false);if(observedAt.isAfter(receivedAt)||(pendingObservations!=0||pendingControls!=0)!=(oldestPendingAt!=null))throw invalid();if(oldestPendingAt!=null){time(oldestPendingAt);if(oldestPendingAt.isAfter(observedAt))throw invalid();}}
        @Override public String toString(){return "CollectorNodeHeartbeat[redacted]";}
    }
    public record Evidence(String nodeId,long nodeRevision,boolean enabled,Instant expectedSince,
                           Heartbeat heartbeat,int pendingServerClaims,Instant readAt){
        public Evidence {id(nodeId);safe(nodeRevision,true);time(expectedSince);time(readAt);if(expectedSince.isAfter(readAt)||pendingServerClaims<0||pendingServerClaims>200||heartbeat!=null&&(heartbeat.nodeRevision()>nodeRevision||heartbeat.receivedAt().isAfter(readAt)))throw invalid();}
        @Override public String toString(){return "CollectorNodeFacts[redacted]";}
    }
    /** Null means the complete current node, original time/proof or bounded server reconciliation could not be read. */
    @FunctionalInterface public interface Api {Evidence read(String nodeId);}
    private static void id(String value){if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>64||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw invalid();}
    private static void safe(long value,boolean positive){if(value<(positive?1:0)||value>9007199254740991L)throw invalid();}
    private static void time(Instant value){if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("collector node facts unavailable");}
}
