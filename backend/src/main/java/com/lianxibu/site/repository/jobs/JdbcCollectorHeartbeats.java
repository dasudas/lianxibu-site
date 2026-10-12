package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CollectorHeartbeatTelemetry;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Latest three original heartbeats; one bounded native setting and node last-seen in the same writer. */
public final class JdbcCollectorHeartbeats {
    public record Entry(CollectorHeartbeatTelemetry.Parsed input, Instant receivedAt) {
        @Override public String toString(){return "CollectorHeartbeatEntry[redacted]";}
    }
    public record History(String nodeId, long nodeRevision, long revision, List<Entry> samples) {
        public History { samples=List.copyOf(samples); }
        public Entry latest(){return samples.getLast();}
        @Override public String toString(){return "CollectorHeartbeatHistory[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcCollectorHeartbeats(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public History original(String node){requireTx();return load(node);}
    public History latest(String node,long nodeRevision){requireTx();safe(nodeRevision);var value=load(node);if(value==null)return null;if(value.nodeRevision()>nodeRevision)throw unavailable();return value.nodeRevision()==nodeRevision?value:null;}
    public Entry committed(String node,long nodeRevision,CollectorHeartbeatTelemetry.Parsed input){var history=latest(node,nodeRevision);if(history==null)return null;for(var entry:history.samples())if(entry.input().sample().observedAt().equals(input.sample().observedAt())){if(!entry.input().payloadHash().equals(input.payloadHash()))throw unavailable();return entry;}return null;}
    public Entry save(String node,long nodeRevision,CollectorHeartbeatTelemetry.Parsed input,Instant now){
        requireTx();safe(nodeRevision);time(now);WireValues.id(node,false);
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()||input==null||!input.equals(CollectorHeartbeatTelemetry.parse(input.canonical().getBytes(StandardCharsets.UTF_8)))||input.sample().observedAt().isAfter(now))throw unavailable();
        var before=load(node);if(before!=null&&(before.nodeRevision()>nodeRevision||before.latest().receivedAt().isAfter(now)))throw unavailable();
        var replay=committed(node,nodeRevision,input);if(replay!=null)return replay;
        var entries=new ArrayList<Entry>();if(before!=null&&before.nodeRevision()==nodeRevision){if(!input.sample().observedAt().isAfter(before.latest().input().sample().observedAt()))throw unavailable();entries.addAll(before.samples());}
        var result=new Entry(input,now);entries.add(result);if(entries.size()>3)entries.removeFirst();
        var root=JsonMapper.shared().createObjectNode();root.put("schema_version","collector-heartbeat-store-v1");root.put("node_id",node);root.put("node_revision",nodeRevision);var array=root.putArray("samples");
        for(var entry:entries){var item=array.addObject();item.set("body",CanonicalJson.parse(entry.input().canonical()));item.put("payload_hash",entry.input().payloadHash());item.put("received_at",format(entry.receivedAt()));}
        var stamp=Timestamp.from(now);String encoded=new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8);
        if(before==null)jdbc.update("INSERT INTO runtime_settings(setting_key,value_json,revision,updated_at) VALUES(?,?,1,?)",ps->bind(ps,new Object[]{key(node),encoded,stamp}));
        else {if(before.revision()>=CanonicalJson.MAX_SAFE_INTEGER||jdbc.update("UPDATE runtime_settings SET value_json=?,revision=revision+1,updated_at=? WHERE setting_key=? AND revision=?",ps->bind(ps,new Object[]{encoded,stamp,key(node),before.revision()}))!=1)throw unavailable();}
        if(jdbc.update("UPDATE collector_nodes SET last_seen_at=? WHERE id=? AND enabled=1 AND config_revision=? AND (last_seen_at IS NULL OR last_seen_at<=?)",ps->bind(ps,new Object[]{stamp,node,nodeRevision,stamp}))!=1)throw unavailable();
        return result;
    }
    private History load(String node){
        WireValues.id(node,false);var rows=jdbc.query("SELECT CASE WHEN OCTET_LENGTH(value_json) BETWEEN 1 AND 8192 THEN value_json ELSE NULL END,revision,updated_at FROM runtime_settings WHERE setting_key=?",(r,n)->{
            if(r.getString(1)==null)throw unavailable();var fields=Set.of("schema_version","node_id","node_revision","samples");var root=WireValues.object(CanonicalJson.parse(r.getString(1)),fields,fields);
            if(!"collector-heartbeat-store-v1".equals(WireValues.text(root.get("schema_version")))||!node.equals(WireValues.id(root.get("node_id"),false)))throw unavailable();long revision=r.getLong(2),nodeRevision=WireValues.nonnegativeInteger(root.get("node_revision"));safe(revision);safe(nodeRevision);
            var array=root.get("samples");if(!array.isArray()||array.isEmpty()||array.size()>3||r.getTimestamp(3,utc())==null)throw unavailable();var samples=new ArrayList<Entry>();Instant observed=null,received=null;
            for(var item:array){var keys=Set.of("body","payload_hash","received_at");WireValues.object(item,keys,keys);var parsed=CollectorHeartbeatTelemetry.parse(CanonicalJson.bytes(item.get("body")));var at=WireValues.utcMicroseconds(item.get("received_at"));time(at);if(!parsed.payloadHash().equals(WireValues.sha256(item.get("payload_hash")))||parsed.sample().observedAt().isAfter(at)||observed!=null&&!parsed.sample().observedAt().isAfter(observed)||received!=null&&at.isBefore(received))throw unavailable();observed=parsed.sample().observedAt();received=at;samples.add(new Entry(parsed,at));}
            if(!samples.getLast().receivedAt().equals(r.getTimestamp(3,utc()).toInstant()))throw unavailable();return new History(node,nodeRevision,revision,samples);
        },key(node));if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    public static String key(String node){WireValues.id(node,false);try{return "collector-heartbeat:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(node.getBytes(StandardCharsets.UTF_8))).substring(0,32);}catch(java.security.NoSuchAlgorithmException impossible){throw unavailable();}}
    private static String format(Instant time){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(time);}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void bind(java.sql.PreparedStatement ps,Object[] values)throws java.sql.SQLException{for(int i=0;i<values.length;i++){if(values[i] instanceof Timestamp time)ps.setTimestamp(i+1,time,utc());else ps.setObject(i+1,values[i]);}}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();}
    private static void safe(long value){if(value<1||value>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();}
    private static void time(Instant time){if(time==null||time.getEpochSecond()<0||time.getNano()%1000!=0)throw unavailable();}
    private static IllegalStateException unavailable(){return new IllegalStateException("collector heartbeat proof unavailable");}
}
