package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Identity;
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
    public record Root(String id,String hash,long revision,long generation,String state,
                       String application,String resultHash,String resultJson,Instant appliedAt) { }
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    private final JdbcWorkerState worker;
    public JdbcResultState(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);worker=new JdbcWorkerState(source);}
    public Root root(String id){
        var rows=jdbc.query("SELECT r.id,LOWER(HEX(r.input_hash)),r.revision,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash),r.state,r.application_state,LOWER(HEX(r.result_hash)),r.result_json,r.applied_at FROM jobs r WHERE r.id=? AND r.record_kind='logical_task'",(rs,n)->new Root(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant()),id);
        if(rows.size()!=1||!id.equals(rows.getFirst().id()))throw conflict();return rows.getFirst();
    }
    public JsonNode claim(Identity id){
        var target=JsonMapper.shared().createObjectNode();target.put("root_id",id.rootId());target.put("attempt_id",id.attemptId());target.put("dispatch_token",id.fencing());target.put("slot_revision",id.slotRevision());
        String raw=worker.claimReceipt(sha(JdbcFrozenInputs.canonical(target)));if(raw==null)throw conflict();var claim=CanonicalJson.parse(raw);
        if(!id.rootId().equals(claim.path("root_id").asText())||!id.attemptId().equals(claim.path("attempt_id").asText())||id.fencing()!=claim.path("dispatch_token").asLong()||id.fencing()!=claim.path("fencing").asLong()||!Long.toString(id.fencing()).equals(id.dispatchToken())||id.slotRevision()!=claim.path("slot_revision").asLong()||!id.leaseUntil().equals(Instant.parse(claim.path("lease_until").asText()))||!id.inputHash().equals(claim.path("input_hash").asText())||!claim.path("root_revision").isIntegralNumber()||!claim.path("owner_epoch").isTextual())throw conflict();
        var attempt=records.byId(id.attemptId());if(attempt==null||!id.attemptId().equals(attempt.id())||!"ai_attempt".equals(attempt.kind())||!id.rootId().equals(attempt.rootId())||attempt.requestJson()==null)throw conflict();
        var request=CanonicalJson.parse(attempt.requestJson());if(!id.inputHash().equals(request.path("input_hash").asText())||!Arrays.equals(CanonicalJson.bytes(request),CanonicalJson.bytes(CanonicalJson.parse(id.frozenRequestJson())))||!claim.path("request_hash").asText().equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,request)))throw conflict();
        if(!jdbc.queryForObject("SELECT input_hash=? AND snapshot_owner_id=? AND send_authorized_at IS NOT NULL FROM jobs WHERE id=?",Boolean.class,HexFormat.of().parseHex(id.inputHash()),id.rootId(),id.attemptId()))throw conflict();return claim;
    }
    public boolean owns(Identity id,JsonNode claim,Instant now){return worker.owns(id.rootId(),id.attemptId(),claim.path("owner_epoch").asText(),id.fencing(),id.slotRevision(),claim.path("root_revision").asLong(),now,id.frozenRequestJson(),id.inputHash());}
    public JsonNode receipt(String key,String actor,String root,String attempt){
        var rows=jdbc.query("SELECT target_type,target_key_json,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5)},sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();var target=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[3]);
        if(!"result_application".equals(row[0])||!actor.equals(row[2])||!root.equals(target.path("root_id").asText())||!attempt.equals(target.path("attempt_id").asText())||!Arrays.equals((byte[])row[4],HexFormat.of().parseHex(sha(JdbcFrozenInputs.canonical(payload)))))throw conflict();return payload;
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
        if(root.resultHash()==null)return null;var manifest=CanonicalJson.parse(root.resultJson());if(!manifest.isObject()||manifest.size()!=3||!"retained-ai-result-manifest-v1".equals(manifest.path("schema_version").asText())||!root.resultHash().equals(manifest.path("result_hash").asText()))throw conflict();
        var rows=jdbc.query("SELECT id,owner_job_id,purpose,stamp_id,part_no,payload_json,payload_hash FROM jobs WHERE id=? AND record_kind='snapshot_part'",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getBytes(7)},manifest.path("part_id").asText());
        if(rows.size()!=1)throw conflict();var row=rows.getFirst();if(!manifest.path("part_id").asText().equals(row[0])||!root.id().equals(row[1])||!"result".equals(row[2])||!root.resultHash().equals(row[3])||(long)row[4]!=0||!Arrays.equals((byte[])row[6],HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse((String)row[5])))))throw conflict();return (String)row[5];
    }
    public void finish(Root root,Identity id,JsonNode claim,String hash,String application,String state,boolean applied,Instant now){
        JdbcFrozenInputs.requireTx();if(root.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        one(jdbc.update("UPDATE jobs SET state=?,application_state=?,applied_at=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND result_hash=? AND applied_at IS NULL",state,application,applied?Timestamp.from(now):null,Timestamp.from(now),root.id(),root.revision(),HexFormat.of().parseHex(hash)));
        var attempt=records.byId(id.attemptId());if(attempt==null)throw conflict();if("running".equals(attempt.state())){
            one(jdbc.update("UPDATE jobs SET state='succeeded',slot_released_at=?,release_reason='result_complete',revision=revision+1,updated_at=? WHERE id=? AND root_job_id=? AND record_kind='ai_attempt' AND state='running' AND worker_instance_id=? AND dispatch_token=?",Timestamp.from(now),Timestamp.from(now),id.attemptId(),id.rootId(),claim.path("owner_epoch").asText(),id.fencing()));
            var slot=worker.slot();if(slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER||slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
            one(jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,request_deadline=NULL,occupied_since=NULL,fencing_token=fencing_token+1,slot_revision=slot_revision+1 WHERE id=? AND slot_state='occupied' AND job_id=? AND holder=? AND fencing_token=? AND slot_revision=?",slot.id(),id.attemptId(),claim.path("owner_epoch").asText(),id.fencing(),id.slotRevision()));
        }else if(!"succeeded".equals(attempt.state()))throw conflict();
    }
    public void append(String key,String actor,Identity id,String reason,JsonNode receipt,Instant now){
        var target=JsonMapper.shared().createObjectNode();target.put("root_id",id.rootId());target.put("attempt_id",id.attemptId());
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("result-receipt",key),key,"result_application",JdbcFrozenInputs.canonical(target),actor,reason,JdbcFrozenInputs.canonical(receipt)),now);
    }
    public static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void one(int count){if(count!=1)throw conflict();}
    public static IllegalStateException conflict(){return new IllegalStateException("immutable result or worker proof unavailable");}
}
