package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit private-state codec: decimal measurements are strings and null remains unknown. */
final class OperationalStateCodec {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELDS = Set.of("key","revision","configuration_revision","status","level",
            "first_seen","last_seen","last_sample","observed","threshold","timers","counters","guards",
            "acknowledged","recovery_ready","next_reminder","silenced_until");
    static ObjectNode encode(State value) {
        ObjectNode out = JSON.createObjectNode(), key = JSON.createObjectNode();
        key.put("rule",value.key().ruleId()); key.put("component",value.key().component()); key.put("resource",value.key().resourceKey());
        out.set("key",key);out.put("revision",value.revision());out.put("configuration_revision",value.configurationRevision());
        out.put("status",value.status().name());put(out,"level",value.level()==null?null:value.level().name());
        time(out,"first_seen",value.firstSeenAt());time(out,"last_seen",value.lastSeenAt());time(out,"last_sample",value.lastSampleAt());
        put(out,"observed",value.observedValue());put(out,"threshold",value.threshold());
        var timers=JSON.createObjectNode();new java.util.TreeMap<>(value.timers()).forEach((name,at)->time(timers,name,at));out.set("timers",timers);
        var counters=JSON.createObjectNode();new java.util.TreeMap<>(value.counters()).forEach(counters::put);out.set("counters",counters);
        var guards=JSON.createArrayNode();value.guards().stream().map(Enum::name).sorted().forEach(guards::add);out.set("guards",guards);
        out.put("acknowledged",value.acknowledged());out.put("recovery_ready",value.recoveryReady());
        time(out,"next_reminder",value.nextReminderAt());time(out,"silenced_until",value.silencedUntil());return out;
    }
    static State decode(JsonNode in) {
        fields(in,FIELDS);JsonNode key=in.get("key");fields(key,Set.of("rule","component","resource"));
        Key identity=new Key(text(key.get("rule")),text(key.get("component")),text(key.get("resource")));
        var timers=new HashMap<String,Instant>();var counters=new HashMap<String,Long>();
        for(var field:map(in.get("timers")).properties()) { name(field.getKey());Instant at=at(field.getValue());if(at==null)throw invalid();timers.put(field.getKey(),at); }
        for(var field:map(in.get("counters")).properties()){name(field.getKey());counters.put(field.getKey(),number(field.getValue()));}
        JsonNode list=in.get("guards");if(!list.isArray()||list.size()>Guard.values().length)throw invalid();
        Set<Guard> guards=new HashSet<>();for(JsonNode item:list)if(!guards.add(Guard.valueOf(text(item))))throw invalid();
        String level=nullable(in.get("level"));State result=new State(identity,number(in.get("revision")),number(in.get("configuration_revision")),
                Status.valueOf(text(in.get("status"))),level==null?null:Level.valueOf(level),at(in.get("first_seen")),at(in.get("last_seen")),at(in.get("last_sample")),
                decimal(in.get("observed")),decimal(in.get("threshold")),timers,counters,guards,flag(in.get("acknowledged")),flag(in.get("recovery_ready")),at(in.get("next_reminder")),at(in.get("silenced_until")));
        if(result.revision()<1||result.lastSampleAt()==null||result.lastSeenAt()==null
                ||result.level()!=null&&(result.firstSeenAt()==null||result.threshold()==null))throw invalid();
        return result;
    }
    static void fields(JsonNode node,Set<String> expected){
        if(node==null||!node.isObject()||node.size()!=expected.size())throw invalid();
        for(var field:node.properties())if(!expected.contains(field.getKey()))throw invalid();
    }
    private static JsonNode map(JsonNode node){if(node==null||!node.isObject()||node.size()>16)throw invalid();return node;}
    private static void name(String value){if(!value.matches("[a-z][a-z0-9_]{0,63}"))throw invalid();}
    private static String text(JsonNode node){if(node==null||!node.isTextual())throw invalid();return node.asText();}
    private static String nullable(JsonNode node){if(node==null)throw invalid();return node.isNull()?null:text(node);}
    private static String decimal(JsonNode node){String value=nullable(node);if(value!=null&&(value.length()>30||!value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")))throw invalid();return value;}
    private static Instant at(JsonNode node){String value=nullable(node);return value==null?null:Instant.parse(value);}
    private static long number(JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0||node.longValue()>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return node.longValue();}
    private static boolean flag(JsonNode node){if(node==null||!node.isBoolean())throw invalid();return node.booleanValue();}
    static void put(ObjectNode out,String name,String value){if(value==null)out.putNull(name);else out.put(name,value);}
    static void time(ObjectNode out,String name,Instant value){put(out,name,value==null?null:value.toString());}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid persisted operational state");}
}
