package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.jobs.OperationalNotification.Destination;
import com.lianxibu.site.repository.jobs.JdbcOperationalDeliveries;
import com.lianxibu.site.service.jobs.OperationalNoticeDelivery.*;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persistent lease and local log handoff receipts over the shared writer, without an AI task. */
public final class OperationalDeliveryJdbcStore implements Store {
    private final JdbcOperationalDeliveries repository;
    public OperationalDeliveryJdbcStore(JdbcOperationalDeliveries repository){this.repository=java.util.Objects.requireNonNull(repository);}
    @Override public Destination destination(){return repository.destination();}
    @Override public String channelId(){return repository.channelId();}
    @Override public Claim claimed(String key){var row=repository.receipt(key);if(row==null)return null;var payload=payload(row);if(!"claimed".equals(payload.get("phase").asString()))throw invalid();return claim(payload);}
    @Override public Claim claim(String key,String token,Instant now,Instant expires) {
        var original=repository.next(now);if(original==null)return null;
        var event=event(original);var old=repository.latest(event.id());long sequence=old==null?1:old.sequence()+1;
        long attempt=old==null?1:payload(old).get("attempt").asLong()+1;
        var claim=new Claim(event,token,attempt,expires);var value=encode(claim,"claimed");
        repository.append(repository.receiptId(key),key,event.id(),sequence,value.toString(),expires,now);return claim;
    }
    @Override public Boolean finished(String key){var row=repository.receipt(key);if(row==null)return null;var value=payload(row);if(!java.util.Set.of("retry_pending",acceptedPhase()).contains(value.get("phase").asString()))throw invalid();return true;}
    @Override public boolean finish(String key,Claim claim,Instant now,boolean accepted,Instant retryAt,String errorCode) {
        var latest=repository.latest(claim.event().id());if(latest==null)return false;var previous=payload(latest);
        if(!"claimed".equals(previous.get("phase").asString())||!claim.equals(claim(previous))||!now.isBefore(claim.expiresAt()))return false;
        if(!claim.event().equals(event(repository.notice(claim.event().id()))))throw invalid();
        if(accepted&&(retryAt!=null||errorCode!=null)||!accepted&&(retryAt==null||!retryAt.isAfter(now)||!(destination()==Destination.LOG?"log_handoff_failed":"cloud_notification_failed").equals(errorCode)))throw invalid();
        var value=encode(claim,accepted?acceptedPhase():"retry_pending");value.put("error_code",errorCode);value.put("retry_at",retryAt==null?null:retryAt.toString());
        String id=accepted?repository.completionId(claim.event().id()):repository.receiptId(key);
        repository.append(id,key,claim.event().id(),latest.sequence()+1,value.toString(),accepted?now:retryAt,now);return true;
    }
    private static Event event(JdbcOperationalDeliveries.Stored row) {
        if(row==null||!row.id().matches("opsn_[0-9a-f]{48}"))throw invalid();var value=CanonicalJson.parse(row.payload());var target=CanonicalJson.parse(row.target());
        if(!"ops_notice_intent".equals(value.path("record_type").asString())||!"pending".equals(value.path("delivery_state").asString()))throw invalid();
        String level=switch(value.get("level").asString()){case "CRITICAL"->"L1";case "WARNING"->"L2";default->throw invalid();};
        return new Event(row.id(),target.get("rule_id").asString(),target.get("component").asString(),target.get("resource_key").asString(),level,value.get("action").asString(),Instant.parse(value.get("at").asString()),nullable(value,"observed_value"),nullable(value,"threshold"));
    }
    private ObjectNode encode(Claim claim,String phase) {
        var value=JsonMapper.shared().createObjectNode();value.put("schema_version",schema());value.put("phase",phase);
        if(destination()==Destination.CLOUD)value.put("channel_id",channelId());
        value.put("token",claim.token());value.put("attempt",claim.attempt());value.put("expires_at",claim.expiresAt().toString());
        value.set("event",CanonicalJson.parse(OperationalNoticeLog.line(claim.event())));return value;
    }
    private JsonNode payload(JdbcOperationalDeliveries.Stored row){
        var value=CanonicalJson.parse(row.payload());if(!schema().equals(value.path("schema_version").asString()))throw invalid();
        if(destination()==Destination.CLOUD){var target=CanonicalJson.parse(row.target());if(!channelId().equals(value.path("channel_id").asString())||!channelId().equals(target.path("channel_id").asString())||!target.path("notice_id").asString().equals(value.path("event").path("event_id").asString()))throw invalid();}
        return value;
    }
    private String schema(){return destination()==Destination.LOG?"ops-log-delivery-v1":"ops-cloud-delivery-v1";}
    private String acceptedPhase(){return destination()==Destination.LOG?"log_handed_off":"cloud_accepted";}
    private static Claim claim(JsonNode value) {
        var e=value.get("event");var event=new Event(e.get("event_id").asString(),e.get("rule_id").asString(),e.get("component").asString(),e.get("resource_key").asString(),e.get("level").asString(),e.get("action").asString(),Instant.parse(e.get("event_time").asString()),nullable(e,"observed_value"),nullable(e,"threshold"));
        return new Claim(event,value.get("token").asString(),value.get("attempt").asLong(),Instant.parse(value.get("expires_at").asString()));
    }
    private static String nullable(JsonNode value,String name){var node=value.get(name);if(node==null)throw invalid();return node.isNull()?null:node.asString();}
    private static IllegalStateException invalid(){return new IllegalStateException("invalid operational delivery proof");}
}
