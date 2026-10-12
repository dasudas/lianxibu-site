package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.jobs.OperationalNotification.Destination;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only delivery receipts. Original operational notice audit rows are never updated. */
public final class JdbcOperationalDeliveries {
    public record Stored(String id,String target,String payload,long sequence) { }
    private final JdbcTemplate jdbc;private final JdbcJobRecords jobs;private final Destination destination;private final String channelId,type;
    public JdbcOperationalDeliveries(DataSource source){this(source,Destination.LOG,null);}
    public JdbcOperationalDeliveries(DataSource source,Destination destination,String channelId){
        if(destination==null||destination==Destination.LOG&&channelId!=null||destination==Destination.CLOUD&&(channelId==null||!channelId.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("invalid delivery destination");
        jdbc=new JdbcTemplate(source);jobs=new JdbcJobRecords(source);this.destination=destination;this.channelId=channelId;type=destination==Destination.LOG?"ops_log_delivery":"ops_cloud_delivery";
    }
    public Destination destination(){return destination;}
    public String channelId(){return channelId;}
    public Stored next(Instant now) {
        if(destination==Destination.CLOUD)return nextCloud(now);
        var ids=jdbc.query("SELECT n.id FROM jobs n WHERE n.record_kind='disposition' AND n.target_type='ops_rule'"
                +" AND n.id LIKE 'opsn!_%' ESCAPE '!'"
                +" AND NOT EXISTS (SELECT 1 FROM jobs done WHERE done.id=CONCAT('opsd_',SUBSTRING(n.id,6)))"
                +" AND NOT EXISTS (SELECT 1 FROM jobs d WHERE d.target_type='ops_log_delivery' AND d.target_id=n.id"
                +" AND d.eligible_at>? AND NOT EXISTS (SELECT 1 FROM jobs newer WHERE newer.target_type='ops_log_delivery'"
                +" AND newer.target_id=n.id AND newer.revision>d.revision)) ORDER BY n.id LIMIT 1",
                (rs,index)->rs.getString(1),Timestamp.from(now));
        return ids.isEmpty()?null:notice(ids.getFirst());
    }
    private Stored nextCloud(Instant now) {
        var ids=jdbc.query("SELECT n.id FROM jobs n WHERE n.record_kind='disposition' AND n.target_type='ops_rule' AND n.id LIKE 'opsn!_%' ESCAPE '!'"
                +" AND NOT EXISTS (SELECT 1 FROM jobs done WHERE done.record_kind='disposition' AND done.target_type='ops_cloud_delivery' AND done.target_id=n.id"
                +" AND JSON_UNQUOTE(JSON_EXTRACT(done.target_key_json,'$.channel_id'))=? AND JSON_UNQUOTE(JSON_EXTRACT(done.action_payload,'$.phase'))='cloud_accepted')"
                +" AND NOT EXISTS (SELECT 1 FROM jobs d WHERE d.record_kind='disposition' AND d.target_type='ops_cloud_delivery' AND d.target_id=n.id"
                +" AND JSON_UNQUOTE(JSON_EXTRACT(d.target_key_json,'$.channel_id'))=? AND d.eligible_at>?"
                +" AND NOT EXISTS (SELECT 1 FROM jobs newer WHERE newer.record_kind='disposition' AND newer.target_type='ops_cloud_delivery' AND newer.target_id=n.id"
                +" AND JSON_UNQUOTE(JSON_EXTRACT(newer.target_key_json,'$.channel_id'))=? AND newer.revision>d.revision)) ORDER BY n.id LIMIT 1",
                (rs,index)->rs.getString(1),channelId,channelId,Timestamp.from(now),channelId);
        return ids.isEmpty()?null:notice(ids.getFirst());
    }
    public Stored notice(String id){return one("id=? AND record_kind='disposition' AND target_type='ops_rule'",id);}
    public Stored receipt(String key){return destination==Destination.LOG?one("record_kind='disposition' AND target_type=? AND action_key_hash=?",type,sha(key.getBytes(StandardCharsets.UTF_8))):one("record_kind='disposition' AND target_type=? AND action_key_hash=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.channel_id'))=?",type,sha(key.getBytes(StandardCharsets.UTF_8)),channelId);}
    public Stored latest(String noticeId){return destination==Destination.LOG?one("record_kind='disposition' AND target_type=? AND target_id=? ORDER BY revision DESC LIMIT 1",type,noticeId):one("record_kind='disposition' AND target_type=? AND target_id=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.channel_id'))=? ORDER BY revision DESC LIMIT 1",type,noticeId,channelId);}
    private Stored one(String condition,Object... parameters) {
        var rows=jdbc.query("SELECT id,target_key_json,action_payload,revision,target_key_hash,action_payload_hash FROM jobs WHERE "+condition,(rs,index)->{
            String target=rs.getString(2),payload=rs.getString(3);
            if(!Arrays.equals(sha(CanonicalJson.bytes(CanonicalJson.parse(target))),rs.getBytes(5))
                    ||!Arrays.equals(sha(CanonicalJson.bytes(CanonicalJson.parse(payload))),rs.getBytes(6)))throw new IllegalStateException("delivery audit hash mismatch");
            return new Stored(rs.getString(1),target,payload,rs.getLong(4));
        },parameters);
        if(rows.size()>1)throw new IllegalStateException("duplicate delivery receipt");return rows.isEmpty()?null:rows.getFirst();
    }
    public void append(String id,String key,String noticeId,long sequence,String payload,Instant due,Instant now) {
        JdbcFrozenInputs.requireTx();if(sequence<1||sequence>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalArgumentException("invalid delivery sequence");
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("notice_id",noticeId);
        if(destination==Destination.CLOUD)target.put("channel_id",channelId);
        jobs.appendDisposition(new JdbcJobRecords.Disposition(id,key,type,target.toString(),"system-ops",destination==Destination.LOG?"log delivery":"cloud notification delivery",payload),now);
        // Initialize scheduling fields in the same insertion transaction, before this immutable receipt is visible.
        if(jdbc.update("UPDATE jobs SET target_id=?,revision=?,eligible_at=? WHERE id=? AND target_type=? AND revision=0",
                noticeId,sequence,Timestamp.from(due),id,type)!=1)throw new IllegalStateException("delivery receipt initialization conflict");
    }
    public String receiptId(String key){return destination==Destination.LOG?id(key):"opscl_"+HexFormat.of().formatHex(sha((channelId+":"+key).getBytes(StandardCharsets.UTF_8))).substring(0,48);}
    public String completionId(String noticeId){return destination==Destination.LOG?"opsd_"+noticeId.substring(5):"opscd_"+HexFormat.of().formatHex(sha((channelId+":"+noticeId).getBytes(StandardCharsets.UTF_8))).substring(0,48);}
    public static String id(String key){return "opsdl_"+HexFormat.of().formatHex(sha(key.getBytes(StandardCharsets.UTF_8))).substring(0,48);}
    private static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
