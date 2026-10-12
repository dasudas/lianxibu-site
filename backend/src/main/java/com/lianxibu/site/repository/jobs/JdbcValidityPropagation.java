package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable non-AI work in generation_intent; completed identities remain deduplicated. */
public final class JdbcValidityPropagation {
    public record Work(String id,String disposition,JsonNode target,String cursor,long revision) { }
    public record Candidate(String id,String type,String target,String payload,byte[] targetHash,byte[] payloadHash) { }
    private final JdbcTemplate jdbc;private final JdbcJobRecords records;
    public JdbcValidityPropagation(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public boolean hasQueued(){return !jdbc.query("SELECT id FROM jobs WHERE record_kind='generation_intent' AND task_type='validity_propagation' AND state='queued' LIMIT 1",(rs,n)->rs.getString(1)).isEmpty();}
    public void enqueue(String disposition,JsonNode target,Instant now){
        JdbcFrozenInputs.requireTx();WireValues.id(disposition,false);var snapshot=JsonMapper.shared().createObjectNode();snapshot.put("schema_version","validity-work-v1");snapshot.put("disposition_id",disposition);snapshot.set("target",target);String full=JdbcFrozenInputs.canonical(snapshot),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
        var origins=jdbc.query("SELECT id,created_at,action_payload,action_payload_hash FROM jobs WHERE id=? AND record_kind='disposition'",(rs,n)->new Object[]{rs.getString(1),rs.getTimestamp(2),rs.getString(3),rs.getBytes(4)},disposition);
        if(origins.size()!=1||!disposition.equals(origins.getFirst()[0])||origins.getFirst()[1]==null)throw unavailable();var origin=origins.getFirst();var originPayload=CanonicalJson.parse((String)origin[2]);if(!"content-validity-v1".equals(originPayload.path("schema_version").asText())||!"invalidate_conclusion".equals(originPayload.path("action").asText())||!Arrays.equals((byte[])origin[3],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(originPayload)))))throw unavailable();
        LocalDate frozenDate=LocalDate.ofInstant(((Timestamp)origin[1]).toInstant(),ZoneOffset.UTC);
        String logical="validity-work:"+full;String id=JdbcFrozenInputs.stableId("validity-work",full);String stored=records.ensureIntent(new JdbcJobRecords.Intent(id,logical,0,"validity_propagation",frozenDate),now);if(!id.equals(stored))throw unavailable();
        var rows=jdbc.query("SELECT id,state,input_snapshot_json,LOWER(HEX(input_hash)) FROM jobs WHERE id=? AND record_kind='generation_intent' AND task_type='validity_propagation'",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},id);
        if(rows.size()!=1||!id.equals(rows.getFirst()[0]))throw unavailable();var row=rows.getFirst();
        if(row[2]==null){if(!"waiting_input".equals(row[1])||jdbc.update("UPDATE jobs SET input_snapshot_json=?,input_hash=?,state='queued',updated_at=? WHERE id=? AND state='waiting_input' AND input_snapshot_json IS NULL",full,HexFormat.of().parseHex(hash),Timestamp.from(now),id)!=1)throw unavailable();}
        else if(!full.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(row[2])))||!hash.equals(row[3])||!Set.of("queued","completed").contains(row[1]))throw unavailable();
    }
    public Work next(){
        JdbcFrozenInputs.requireTx();var rows=jdbc.query("SELECT id,input_snapshot_json,LOWER(HEX(input_hash)),result_json,revision FROM jobs WHERE record_kind='generation_intent' AND task_type='validity_propagation' AND state='queued' ORDER BY created_at,id LIMIT 1 FOR UPDATE",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)});
        if(rows.isEmpty())return null;var row=rows.getFirst();var snapshot=CanonicalJson.parse((String)row[1]);var fields=Set.of("schema_version","disposition_id","target");WireValues.object(snapshot,fields,fields);if(!"validity-work-v1".equals(snapshot.path("schema_version").asText())||!CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot).equals(row[2])||!JdbcFrozenInputs.stableId("validity-work",JdbcFrozenInputs.canonical(snapshot)).equals(row[0]))throw unavailable();
        String cursor=null;if(row[3]!=null){var progress=CanonicalJson.parse((String)row[3]);WireValues.object(progress,Set.of("cursor"),Set.of("cursor"));cursor=WireValues.id(progress.get("cursor"),false);}
        return new Work((String)row[0],WireValues.id(snapshot.get("disposition_id"),false),snapshot.get("target"),cursor,(long)row[4]);
    }
    public void finish(Work work,String cursor,Instant now){JdbcFrozenInputs.requireTx();if(work.revision()<0||work.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();String progress=null;if(cursor!=null){WireValues.id(cursor,false);if(work.cursor()!=null&&cursor.compareTo(work.cursor())<=0)throw unavailable();var node=JsonMapper.shared().createObjectNode();node.put("cursor",cursor);progress=JdbcFrozenInputs.canonical(node);}if(jdbc.update("UPDATE jobs SET state=?,result_json=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='generation_intent' AND task_type='validity_propagation' AND state='queued' AND revision=?",cursor==null?"completed":"queued",progress,Timestamp.from(now),work.id(),work.revision())!=1)throw unavailable();}
    public List<Candidate> candidates(String after,int limit){if(limit<1||limit>101)throw new IllegalArgumentException("invalid frozen reference page");return jdbc.query("SELECT id,target_type,target_key_json,action_payload,target_key_hash,action_payload_hash FROM jobs WHERE record_kind='disposition' AND target_type IN ('result_application','content_review_version') AND id>? ORDER BY id LIMIT ?",(rs,n)->new Candidate(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5),rs.getBytes(6)),after==null?"":after,limit);}
    public List<Candidate> versionCandidates(String after,int limit){if(limit<1||limit>500)throw new IllegalArgumentException("invalid version page");return jdbc.query("SELECT id,target_type,target_key_json,action_payload,target_key_hash,action_payload_hash FROM jobs WHERE record_kind='disposition' AND target_type IN ('result_application','content_review_version','content_version') AND id>? ORDER BY id LIMIT ?",(rs,n)->new Candidate(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5),rs.getBytes(6)),after==null?"":after,limit);}
    public Boolean receipt(String key,String type,String actor,JsonNode target,String commandHash){
        var rows=jdbc.query("SELECT target_type,operator_id,target_key_json,target_key_hash,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBytes(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw unavailable();var row=rows.getFirst();String full=JdbcFrozenInputs.canonical(target);var payload=CanonicalJson.parse((String)row[4]);WireValues.object(payload,Set.of("schema_version","command_hash","worked"),Set.of("schema_version","command_hash","worked"));
        if(!type.equals(row[0])||!actor.equals(row[1])||!full.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse((String)row[2])))||!Arrays.equals((byte[])row[3],HexFormat.of().parseHex(JdbcResultState.sha(full)))||!Arrays.equals((byte[])row[5],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(payload))))||!"validity-operation-receipt-v1".equals(payload.path("schema_version").asText())||!commandHash.equals(payload.path("command_hash").asText())||!payload.path("worked").isBoolean())throw unavailable();return payload.path("worked").booleanValue();
    }
    public void receipt(String key,String type,String actor,String reason,JsonNode target,String hash,boolean worked,Instant now){
        JdbcFrozenInputs.requireTx();var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","validity-operation-receipt-v1");payload.put("command_hash",hash);payload.put("worked",worked);records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("validity-operation",key),key,type,JdbcFrozenInputs.canonical(target),actor,reason,JdbcFrozenInputs.canonical(payload)),now);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("durable validity propagation unavailable");}
}
