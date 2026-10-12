package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Jobs result state only. All writes join the caller's shared writer transaction. */
public final class JdbcResultState {
    public record Identity(String rootId,String attemptId,String dispatchToken,long slotRevision,
                           long fencing,Instant leaseUntil,String frozenRequestJson,String inputHash) { }
    public record Root(String id,String hash,long revision,long generation,String state,
                       String application,String resultHash,String resultJson,Instant appliedAt) { }
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    private final JdbcWorkerState worker;
    public JdbcResultState(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);worker=new JdbcWorkerState(source);}
    public Root root(String id){
        var rows=jdbc.query("SELECT r.id,LOWER(HEX(r.input_hash)),r.revision,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash),r.state,r.application_state,LOWER(HEX(r.result_hash)),CASE WHEN OCTET_LENGTH(r.result_json) BETWEEN 1 AND 16384 THEN r.result_json ELSE NULL END,r.applied_at,r.result_json IS NOT NULL FROM jobs r WHERE r.id=? AND r.record_kind='logical_task'",(rs,n)->{if(rs.getString(7)==null&&rs.getBoolean(10))throw conflict();return new Root(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant());},id);
        if(rows.size()!=1||!id.equals(rows.getFirst().id())||rows.getFirst().resultHash()!=null&&rows.getFirst().resultJson()==null)throw conflict();return rows.getFirst();
    }
    public JsonNode claim(Identity id){
        if(id==null||id.frozenRequestJson()==null||id.frozenRequestJson().getBytes(StandardCharsets.UTF_8).length>1048576||id.inputHash()==null||!id.inputHash().matches("[0-9a-f]{64}"))throw conflict();
        var target=JsonMapper.shared().createObjectNode();target.put("root_id",id.rootId());target.put("attempt_id",id.attemptId());target.put("dispatch_token",id.fencing());target.put("slot_revision",id.slotRevision());
        String raw=worker.claimReceipt(sha(JdbcFrozenInputs.canonical(target)));if(raw==null)throw conflict();var claim=CanonicalJson.parse(raw);
        if(!id.rootId().equals(claim.path("root_id").asText())||!id.attemptId().equals(claim.path("attempt_id").asText())||id.fencing()!=claim.path("dispatch_token").asLong()||id.fencing()!=claim.path("fencing").asLong()||!Long.toString(id.fencing()).equals(id.dispatchToken())||id.slotRevision()!=claim.path("slot_revision").asLong()||!id.leaseUntil().equals(Instant.parse(claim.path("lease_until").asText()))||!id.inputHash().equals(claim.path("input_hash").asText())||!claim.path("root_revision").isIntegralNumber()||!claim.path("owner_epoch").isTextual())throw conflict();
        var rows=jdbc.query("SELECT id,record_kind,root_job_id,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END,snapshot_owner_id,input_hash,send_authorized_at IS NOT NULL,call_count,max_attempts,priority_no,payload_json IS NULL FROM jobs WHERE id=?",
            (r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBytes(6),r.getBoolean(7),r.getObject(8,Integer.class),r.getObject(9,Integer.class),r.getObject(10,Integer.class),r.getBoolean(11)},id.attemptId());
        if(rows.size()!=1)throw conflict();var attempt=rows.getFirst();
        if(!id.attemptId().equals(attempt[0])||!"ai_attempt".equals(attempt[1])||!id.rootId().equals(attempt[2])||attempt[3]==null||!id.rootId().equals(attempt[4])||!Arrays.equals(HexFormat.of().parseHex(id.inputHash()),(byte[])attempt[5])||!Boolean.TRUE.equals(attempt[6])||!Integer.valueOf(0).equals(attempt[7])||attempt[8]!=null||!Integer.valueOf(0).equals(attempt[9])||!Boolean.TRUE.equals(attempt[10]))throw conflict();
        var request=CanonicalJson.parse((String)attempt[3]);if(!id.inputHash().equals(request.path("input_hash").asText())||!Arrays.equals(CanonicalJson.bytes(request),CanonicalJson.bytes(CanonicalJson.parse(id.frozenRequestJson())))||!claim.path("request_hash").asText().equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,request)))throw conflict();
        return claim;
    }
    public boolean owns(Identity id,JsonNode claim,Instant now){return worker.owns(id.rootId(),id.attemptId(),claim.path("owner_epoch").asText(),id.fencing(),id.slotRevision(),claim.path("root_revision").asLong(),now,id.frozenRequestJson(),id.inputHash());}
    public static final class ReceiptIdentityConflict extends IllegalStateException {
        public ReceiptIdentityConflict(){super("result receipt identity conflict");}
    }
    public JsonNode receipt(String key,String actor,String root,String attempt){
        var rows=jdbc.query("SELECT target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,operator_id,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,target_key_hash,call_count,max_attempts,priority_no FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5),rs.getBytes(6),rs.getObject(7,Integer.class),rs.getObject(8,Integer.class),rs.getObject(9,Integer.class)},sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();if(row[1]==null||row[3]==null||!Integer.valueOf(0).equals(row[6])||row[7]!=null||!Integer.valueOf(0).equals(row[8]))throw conflict();var target=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[3]);
        if(!Arrays.equals((byte[])row[5],HexFormat.of().parseHex(sha(JdbcFrozenInputs.canonical(target))))||!Arrays.equals((byte[])row[4],HexFormat.of().parseHex(sha(JdbcFrozenInputs.canonical(payload)))))throw conflict();
        if(!"result_application".equals(row[0]))throw new ReceiptIdentityConflict();
        com.lianxibu.site.domain.protocol.WireValues.object(target,Set.of("root_id","attempt_id"),Set.of("root_id","attempt_id"));
        if(!actor.equals(row[2])||!root.equals(target.path("root_id").asText())||!attempt.equals(target.path("attempt_id").asText()))throw new ReceiptIdentityConflict();return payload;
    }
    public void retain(Root root,String attempt,String hash,String envelope,Instant now){
        JdbcFrozenInputs.requireTx();if(root.resultHash()!=null){if(!hash.equals(root.resultHash()))throw conflict();return;}
        String part=JdbcFrozenInputs.stableId("retained-result",root.id()+":"+hash);var json=CanonicalJson.parse(envelope);
        records.putPart(new JdbcJobRecords.Part(part,root.id(),"result",hash,0,envelope,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,json)),now);
        var manifest=JsonMapper.shared().createObjectNode();manifest.put("schema_version","retained-ai-result-manifest-v1");manifest.put("part_id",part);manifest.put("result_hash",hash);
        one(jdbc.update("UPDATE jobs SET result_hash=?,result_json=?,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND result_hash IS NULL",HexFormat.of().parseHex(hash),JdbcFrozenInputs.canonical(manifest),Timestamp.from(now),root.id(),root.revision()));
        one(jdbc.update("UPDATE jobs SET result_hash=?,result_json=?,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND root_job_id=? AND state='running' AND result_hash IS NULL",HexFormat.of().parseHex(hash),JdbcFrozenInputs.canonical(manifest),Timestamp.from(now),attempt,root.id()));
    }
    public String retained(Root root){
        if(root.resultHash()==null)return null;if(root.resultJson()==null||root.resultJson().getBytes(StandardCharsets.UTF_8).length>16384)throw conflict();var manifest=CanonicalJson.parse(root.resultJson());if(!manifest.isObject()||manifest.size()!=3||!"retained-ai-result-manifest-v1".equals(manifest.path("schema_version").asText())||!root.resultHash().equals(manifest.path("result_hash").asText()))throw conflict();
        var rows=jdbc.query("SELECT id,owner_job_id,purpose,stamp_id,part_no,CASE WHEN OCTET_LENGTH(payload_json) BETWEEN 1 AND 262144 THEN payload_json ELSE NULL END,payload_hash FROM jobs WHERE id=? AND record_kind='snapshot_part'",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getBytes(7)},manifest.path("part_id").asText());
        if(rows.size()!=1)throw conflict();var row=rows.getFirst();if(row[5]==null)throw conflict();if(!manifest.path("part_id").asText().equals(row[0])||!root.id().equals(row[1])||!"result".equals(row[2])||!root.resultHash().equals(row[3])||(long)row[4]!=0||!Arrays.equals((byte[])row[6],HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse((String)row[5])))))throw conflict();return (String)row[5];
    }
    public void finish(Root root,Identity id,JsonNode claim,String hash,String application,String state,boolean applied,Instant now){
        JdbcFrozenInputs.requireTx();if(root.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        one(jdbc.update("UPDATE jobs SET state=?,application_state=?,applied_at=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND result_hash=? AND applied_at IS NULL",state,application,applied?Timestamp.from(now):null,Timestamp.from(now),root.id(),root.revision(),HexFormat.of().parseHex(hash)));
        var attempts=jdbc.query("SELECT state FROM jobs WHERE id=? AND root_job_id=? AND record_kind='ai_attempt'",(r,n)->r.getString(1),id.attemptId(),id.rootId());if(attempts.size()!=1)throw conflict();String attemptState=attempts.getFirst();if("running".equals(attemptState)){
            one(jdbc.update("UPDATE jobs SET state='succeeded',slot_released_at=?,release_reason='result_complete',revision=revision+1,updated_at=? WHERE id=? AND root_job_id=? AND record_kind='ai_attempt' AND state='running' AND worker_instance_id=? AND dispatch_token=?",Timestamp.from(now),Timestamp.from(now),id.attemptId(),id.rootId(),claim.path("owner_epoch").asText(),id.fencing()));
            var slot=worker.slot();if(slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER||slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
            one(jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,request_deadline=NULL,occupied_since=NULL,fencing_token=fencing_token+1,slot_revision=slot_revision+1 WHERE id=? AND slot_state='occupied' AND job_id=? AND holder=? AND fencing_token=? AND slot_revision=?",slot.id(),id.attemptId(),claim.path("owner_epoch").asText(),id.fencing(),id.slotRevision()));
        }else if(!"succeeded".equals(attemptState))throw conflict();
    }
    public void append(String key,String actor,Identity id,String reason,JsonNode receipt,Instant now){
        var target=JsonMapper.shared().createObjectNode();target.put("root_id",id.rootId());target.put("attempt_id",id.attemptId());
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("result-receipt",key),key,"result_application",JdbcFrozenInputs.canonical(target),actor,reason,JdbcFrozenInputs.canonical(receipt)),now);
    }
    public static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void one(int count){if(count!=1)throw conflict();}
    public static IllegalStateException conflict(){return new IllegalStateException("immutable result or worker proof unavailable");}
}
