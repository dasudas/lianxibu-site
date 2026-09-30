package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.jobs.JdbcOperationalSummaries;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real summary CAS plus append-only operational action/notice audit in the shared transaction. */
public final class OperationalJdbcStore implements OperationalMonitoringService.Store {
    public record View(State state, Instant resolvedAt, Level resolvedLevel) { }
    private record SampleReceipt(String keyHash, String commandHash, State result) { }
    private record Entry(State state, SampleReceipt sample, Instant resolvedAt, Level resolvedLevel) { }
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("operational action identity conflict"); }
    }
    private final JdbcOperationalSummaries summaries;
    private final JdbcJobRecords jobs;
    private final Clock clock;
    public OperationalJdbcStore(JdbcOperationalSummaries summaries, JdbcJobRecords jobs, Clock clock) {
        if (summaries == null || jobs == null || clock == null) throw new IllegalArgumentException("operational store dependencies required");
        this.summaries = summaries; this.jobs = jobs; this.clock = clock;
    }
    public static String alertId(Key key) { return "ops_" + hash(key.ruleId(),key.component(),key.resourceKey()).substring(0,48); }
    public static String operationId(String key) { return "opsop_" + hash(key).substring(0,48); }
    @Override public State committed(String actionKey,String commandHash) {
        String internal=internal(actionKey);
        String audit=summaries.auditPayload(sha(internal));
        if(audit!=null) {
            JsonNode value=CanonicalJson.parse(audit);
            if(!"ops_action".equals(value.path("record_type").asText()) || !commandHash.equals(value.path("command_hash").asText()))throw new Conflict();
            return OperationalStateCodec.decode(value.get("result_state"));
        }
        String keyHash=HexFormat.of().formatHex(sha(internal));
        for(Entry entry:load(summaries.read()).values()) {
            if(entry.sample()!=null&&keyHash.equals(entry.sample().keyHash())) {
                if(!commandHash.equals(entry.sample().commandHash()))throw new Conflict();
                return entry.sample().result();
            }
        }
        return null;
    }
    @Override public State read(Key key) {
        Entry entry=load(summaries.read()).get(alertId(key));
        if(entry==null)return null;
        if(!key.equals(entry.state().key()))throw new Conflict();
        return entry.state();
    }
    public Key key(String alertId) {
        Entry entry=load(summaries.read()).get(alertId);return entry==null?null:entry.state().key();
    }
    public List<View> views() {
        List<View> values=new ArrayList<>();
        load(summaries.read()).values().forEach(entry->values.add(new View(entry.state(),entry.resolvedAt(),entry.resolvedLevel())));
        return List.copyOf(values);
    }
    @Override public void save(State previous,State next,List<Notice> notices,String actionKey,String commandHash,String actor,String reason) {
        if(previous==null||next==null||notices==null||!previous.key().equals(next.key())||next.revision()!=previous.revision()+1)
            throw new IllegalArgumentException("invalid operational transition");
        JdbcOperationalSummaries.Summary summary=summaries.locked();
        Map<String,Entry> values=load(summary);
        String id=alertId(next.key());Entry old=values.get(id);
        if((old==null?0:old.state().revision())!=previous.revision())throw new JdbcOperationalSummaries.Conflict();
        if(old==null&&values.size()>=256)throw new IllegalStateException("operational resource bound reached");
        Instant resolved=old==null?null:old.resolvedAt();Level resolvedLevel=old==null?null:old.resolvedLevel();
        for(Notice notice:notices)if("resolved".equals(notice.action())){resolved=notice.at();resolvedLevel=notice.level();}
        if(next.level()!=null){resolved=null;resolvedLevel=null;}
        SampleReceipt sample=actor==null?new SampleReceipt(HexFormat.of().formatHex(sha(internal(actionKey))),commandHash,next):old==null?null:old.sample();
        values.put(id,new Entry(next,sample,resolved,resolvedLevel));
        expireResolved(values,clock.instant());
        ObjectNode payload=OperationalStateCodec.JSON.createObjectNode();payload.put("schema_version","1");
        ObjectNode resources=OperationalStateCodec.JSON.createObjectNode();new java.util.TreeMap<>(values).forEach((key,entry)->resources.set(key,encode(entry)));
        payload.set("resources",resources);
        summaries.replace(summary==null?0:summary.revision(),payload.toString(),clock.instant());
        if(actor!=null) {
            ObjectNode receipt=OperationalStateCodec.JSON.createObjectNode();receipt.put("record_type","ops_action");receipt.put("command_hash",commandHash);
            receipt.set("result_state",OperationalStateCodec.encode(next));
            jobs.appendDisposition(new JdbcJobRecords.Disposition("opsa_"+hash(actionKey).substring(0,48),internal(actionKey),
                    "ops_rule",target(next.key()).toString(),actor,reason,receipt.toString()),clock.instant());
        }
        for(int index=0;index<notices.size();index++) {
            Notice notice=notices.get(index);ObjectNode event=OperationalStateCodec.JSON.createObjectNode();
            event.put("record_type","ops_notice_intent");event.put("delivery_state","pending");event.put("action",notice.action());
            event.put("level",notice.level().name());OperationalStateCodec.time(event,"at",notice.at());
            OperationalStateCodec.put(event,"observed_value",notice.observedValue());OperationalStateCodec.put(event,"threshold",notice.threshold());
            event.put("alert_id",id);event.put("revision",next.revision());
            String identity="ops_notice:"+hash(actionKey,Integer.toString(index));
            jobs.appendDisposition(new JdbcJobRecords.Disposition("opsn_"+hash(identity).substring(0,48),identity,
                    "ops_rule",target(next.key()).toString(),actor==null?"system-ops":actor,notice.action(),event.toString()),clock.instant());
        }
    }
    private static ObjectNode target(Key key) {
        ObjectNode result=OperationalStateCodec.JSON.createObjectNode();result.put("rule_id",key.ruleId());result.put("component",key.component());result.put("resource_key",key.resourceKey());return result;
    }
    private static ObjectNode encode(Entry entry) {
        ObjectNode out=OperationalStateCodec.JSON.createObjectNode();out.set("state",OperationalStateCodec.encode(entry.state()));
        if(entry.sample()==null)out.putNull("latest_sample");else {
            ObjectNode sample=OperationalStateCodec.JSON.createObjectNode();sample.put("key_hash",entry.sample().keyHash());sample.put("command_hash",entry.sample().commandHash());
            sample.set("result",OperationalStateCodec.encode(entry.sample().result()));out.set("latest_sample",sample);
        }
        OperationalStateCodec.time(out,"resolved_at",entry.resolvedAt());OperationalStateCodec.put(out,"resolved_level",entry.resolvedLevel()==null?null:entry.resolvedLevel().name());return out;
    }
    private static Map<String,Entry> load(JdbcOperationalSummaries.Summary summary) {
        Map<String,Entry> out=new HashMap<>();if(summary==null)return out;
        if(summary.revision()<1||summary.valueJson().getBytes(StandardCharsets.UTF_8).length>262144)throw new Conflict();
        JsonNode value=CanonicalJson.parse(summary.valueJson());OperationalStateCodec.fields(value,Set.of("schema_version","resources"));
        JsonNode resources=value.get("resources");if(!"1".equals(value.get("schema_version").asText())||!resources.isObject()||resources.size()>256)throw new Conflict();
        for(var field:resources.properties()) {
            JsonNode entry=field.getValue();OperationalStateCodec.fields(entry,Set.of("state","latest_sample","resolved_at","resolved_level"));
            State state=OperationalStateCodec.decode(entry.get("state"));if(!field.getKey().equals(alertId(state.key())))throw new Conflict();
            SampleReceipt sample=null;JsonNode stored=entry.get("latest_sample");if(!stored.isNull()) {
                OperationalStateCodec.fields(stored,Set.of("key_hash","command_hash","result"));
                String keyHash=stored.get("key_hash").asText(),command=stored.get("command_hash").asText();
                if(!keyHash.matches("[0-9a-f]{64}")||!command.matches("[0-9a-f]{64}"))throw new Conflict();
                State result=OperationalStateCodec.decode(stored.get("result"));if(!state.key().equals(result.key())||result.revision()>state.revision())throw new Conflict();
                sample=new SampleReceipt(keyHash,command,result);
            }
            Instant resolved=entry.get("resolved_at").isNull()?null:Instant.parse(entry.get("resolved_at").asText());
            Level level=entry.get("resolved_level").isNull()?null:Level.valueOf(entry.get("resolved_level").asText());
            if((resolved==null)!=(level==null))throw new Conflict();out.put(field.getKey(),new Entry(state,sample,resolved,level));
        }
        return out;
    }
    private static void expireResolved(Map<String,Entry> entries,Instant now) {
        entries.replaceAll((id,entry)-> {
            if(entry.resolvedAt()==null||now.isBefore(entry.resolvedAt().plusSeconds(604800))||entry.state().level()!=null)return entry;
            State s=entry.state();State baseline=new State(s.key(),s.revision(),s.configurationRevision(),
                    s.status()==Status.RESOLVED?Status.NORMAL:s.status(),null,null,s.lastSeenAt(),s.lastSampleAt(),s.observedValue(),s.threshold(),
                    s.timers(),s.counters(),s.guards(),s.acknowledged(),s.recoveryReady(),s.nextReminderAt(),s.silencedUntil());
            return new Entry(baseline,entry.sample(),null,null);
        });
    }
    private static String internal(String actionKey){return "ops_action:"+hash(actionKey);}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String hash(String... values) {
        try{var digest=MessageDigest.getInstance("SHA-256");for(String value:values){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
