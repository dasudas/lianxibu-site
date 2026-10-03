package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
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
    @Override public Claim claimed(String key){var row=repository.receipt(key);if(row==null)return null;var payload=payload(row);if(!"claimed".equals(payload.get("phase").asString()))throw invalid();return claim(payload);}
    @Override public Claim claim(String key,String token,Instant now,Instant expires) {
        var original=repository.next(now);if(original==null)return null;
        var event=event(original);var old=repository.latest(event.id());long sequence=old==null?1:old.sequence()+1;
        long attempt=old==null?1:payload(old).get("attempt").asLong()+1;
        var claim=new Claim(event,token,attempt,expires);var value=encode(claim,"claimed");
        repository.append(JdbcOperationalDeliveries.id(key),key,event.id(),sequence,value.toString(),expires,now);return claim;
    }
    @Override public Boolean finished(String key){var row=repository.receipt(key);if(row==null)return null;var value=payload(row);if(!java.util.Set.of("retry_pending","log_handed_off").contains(value.get("phase").asString()))throw invalid();return true;}
    @Override public boolean finish(String key,Claim claim,Instant now,boolean accepted,Instant retryAt,String errorCode) {
        var latest=repository.latest(claim.event().id());if(latest==null)return false;var previous=payload(latest);
        if(!"claimed".equals(previous.get("phase").asString())||!claim.equals(claim(previous))||!now.isBefore(claim.expiresAt()))return false;
        if(!claim.event().equals(event(repository.notice(claim.event().id()))))throw invalid();
        if(accepted&&(retryAt!=null||errorCode!=null)||!accepted&&(retryAt==null||!retryAt.isAfter(now)||!"log_handoff_failed".equals(errorCode)))throw invalid();
        var value=encode(claim,accepted?"log_handed_off":"retry_pending");value.put("error_code",errorCode);value.put("retry_at",retryAt==null?null:retryAt.toString());
        String id=accepted?"opsd_"+claim.event().id().substring(5):JdbcOperationalDeliveries.id(key);
        repository.append(id,key,claim.event().id(),latest.sequence()+1,value.toString(),accepted?now:retryAt,now);return true;
    }
    private static Event event(JdbcOperationalDeliveries.Stored row) {
        if(row==null||!row.id().matches("opsn_[0-9a-f]{48}"))throw invalid();var value=CanonicalJson.parse(row.payload());var target=CanonicalJson.parse(row.target());
        if(!"ops_notice_intent".equals(value.path("record_type").asString())||!"pending".equals(value.path("delivery_state").asString()))throw invalid();
        String level=switch(value.get("level").asString()){case "CRITICAL"->"L1";case "WARNING"->"L2";default->throw invalid();};
        return new Event(row.id(),target.get("rule_id").asString(),target.get("component").asString(),target.get("resource_key").asString(),level,value.get("action").asString(),Instant.parse(value.get("at").asString()),nullable(value,"observed_value"),nullable(value,"threshold"));
    }
    private static ObjectNode encode(Claim claim,String phase) {
        var value=JsonMapper.shared().createObjectNode();value.put("schema_version","ops-log-delivery-v1");value.put("phase",phase);
        value.put("token",claim.token());value.put("attempt",claim.attempt());value.put("expires_at",claim.expiresAt().toString());
        value.set("event",CanonicalJson.parse(OperationalNoticeLog.line(claim.event())));return value;
    }
    private static JsonNode payload(JdbcOperationalDeliveries.Stored row){var value=CanonicalJson.parse(row.payload());if(!"ops-log-delivery-v1".equals(value.path("schema_version").asString()))throw invalid();return value;}
    private static Claim claim(JsonNode value) {
        var e=value.get("event");var event=new Event(e.get("event_id").asString(),e.get("rule_id").asString(),e.get("component").asString(),e.get("resource_key").asString(),e.get("level").asString(),e.get("action").asString(),Instant.parse(e.get("event_time").asString()),nullable(e,"observed_value"),nullable(e,"threshold"));
        return new Claim(event,value.get("token").asString(),value.get("attempt").asLong(),Instant.parse(value.get("expires_at").asString()));
    }
    private static String nullable(JsonNode value,String name){var node=value.get(name);if(node==null)throw invalid();return node.isNull()?null:node.asString();}
    private static IllegalStateException invalid(){return new IllegalStateException("invalid operational delivery proof");}
}
