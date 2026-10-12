package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Atomic known-failure transition, bounded retry preparation and conditional slot release. */
public final class JdbcAttemptFailure {
    public record Current(long rootRevision,long attemptRevision,int calls,long generation,long latestGeneration) { }
    public record Receipt(String state,String nextAttemptId,Instant eligibleAt) { }
    private final JdbcTemplate jdbc;
    private final JdbcWorkerState worker;
    private final JdbcResultState proof;
    private final JdbcJobRecords records;
    public JdbcAttemptFailure(DataSource source){Objects.requireNonNull(source);jdbc=new JdbcTemplate(source);worker=new JdbcWorkerState(source);proof=new JdbcResultState(source);records=new JdbcJobRecords(source);}
    public Receipt committed(String key,JdbcResultState.Identity identity,String owner,String failure,int delay) {
        claim(identity,owner);
        var rows=jdbc.query("SELECT target_type,target_key_json,target_key_hash,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();var target=CanonicalJson.parse((String)row[1]);var value=CanonicalJson.parse((String)row[4]);
        if(!"attempt_failure".equals(row[0])||!owner.equals(row[3])||!same(target,target(identity))||!Arrays.equals((byte[])row[2],sha(target))||!Arrays.equals((byte[])row[5],sha(value)))throw conflict();
        var fields=Set.of("schema_version","failure","retry_after_seconds","state","next_attempt_id","eligible_at");WireValues.object(value,fields,fields);
        if(!"attempt-failure-v1".equals(value.path("schema_version").asText())||!failure.equals(value.path("failure").asText())||delay!=WireValues.nonnegativeInteger(value.get("retry_after_seconds")))throw conflict();
        String state=WireValues.text(value.get("state"));String next=value.get("next_attempt_id").isNull()?null:WireValues.id(value.get("next_attempt_id"),false);
        Instant eligible=value.get("eligible_at").isNull()?null:Instant.parse(WireValues.text(value.get("eligible_at")));
        if(!Set.of("waiting_retry","failed","superseded","blocked_configuration").contains(state)||"waiting_retry".equals(state)!=(next!=null&&eligible!=null)||(next==null)!=(eligible==null))throw conflict();
        return new Receipt(state,next,eligible);
    }
    public Current current(JdbcResultState.Identity identity,String owner,Instant now) {
        JdbcFrozenInputs.requireTx();var claim=claim(identity,owner);if(!proof.owns(identity,claim,now))throw conflict();
        var rows=jdbc.query("SELECT r.revision,a.revision,r.call_count,r.generation,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash) FROM jobs r JOIN jobs a ON a.root_job_id=r.id WHERE r.id=? AND a.id=? AND r.record_kind='logical_task' AND a.record_kind='ai_attempt' AND r.state='running' AND a.state='running' AND r.result_hash IS NULL AND a.result_hash IS NULL AND r.applied_at IS NULL",
                (rs,n)->new Current(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getLong(4),rs.getLong(5)),identity.rootId(),identity.attemptId());
        if(rows.size()!=1)throw conflict();var result=rows.getFirst();
        if(result.calls()<1||result.calls()>3||result.rootRevision()>=CanonicalJson.MAX_SAFE_INTEGER||result.attemptRevision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();return result;
    }
    public Receipt finish(String key,JdbcResultState.Identity id,String owner,String failure,int delay,Current expected,Receipt result,String error,Instant now) {
        JdbcFrozenInputs.requireTx();if(!expected.equals(current(id,owner,now)))throw conflict();
        var slot=worker.slot();if(slot.revision()>=CanonicalJson.MAX_SAFE_INTEGER||slot.fencing()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        if(result.nextAttemptId()!=null) {
            records.prepareAttempt(new JdbcJobRecords.Attempt(result.nextAttemptId(),id.rootId(),expected.calls()+1,id.frozenRequestJson(),id.rootId(),id.inputHash()),now);
            one(jdbc.update("UPDATE jobs SET retry_of_job_id=? WHERE id=? AND record_kind='ai_attempt' AND state='prepared' AND retry_of_job_id IS NULL",id.attemptId(),result.nextAttemptId()));
        }
        one(jdbc.update("UPDATE jobs SET state=?,error_code=?,eligible_at=COALESCE(?,eligible_at),revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='running' AND revision=? AND call_count=? AND result_hash IS NULL AND applied_at IS NULL",
                result.state(),error,result.eligibleAt()==null?null:Timestamp.from(result.eligibleAt()),Timestamp.from(now),id.rootId(),expected.rootRevision(),expected.calls()));
        one(jdbc.update("UPDATE jobs SET state='failed',error_code=?,release_reason='provider_failure',slot_released_at=?,revision=revision+1,updated_at=? WHERE id=? AND root_job_id=? AND record_kind='ai_attempt' AND state='running' AND revision=? AND worker_instance_id=? AND dispatch_token=? AND result_hash IS NULL",
                error,Timestamp.from(now),Timestamp.from(now),id.attemptId(),id.rootId(),expected.attemptRevision(),owner,id.fencing()));
        one(jdbc.update("UPDATE worker_lock SET slot_state='free',holder=NULL,job_id=NULL,lease_until=NULL,request_deadline=NULL,occupied_since=NULL,fencing_token=fencing_token+1,slot_revision=slot_revision+1 WHERE id=? AND slot_state='occupied' AND job_id=? AND holder=? AND fencing_token=? AND slot_revision=?",
                slot.id(),id.attemptId(),owner,id.fencing(),id.slotRevision()));
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","attempt-failure-v1");payload.put("failure",failure);payload.put("retry_after_seconds",delay);payload.put("state",result.state());
        payload.put("next_attempt_id",result.nextAttemptId());payload.put("eligible_at",result.eligibleAt()==null?null:result.eligibleAt().toString());
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("attempt-failure",key),key,"attempt_failure",JdbcFrozenInputs.canonical(target(id)),owner,"known provider failure",JdbcFrozenInputs.canonical(payload)),now);
        return result;
    }
    private JsonNode claim(JdbcResultState.Identity id,String owner){if(id==null||owner==null)throw conflict();var claim=proof.claim(id);if(!owner.equals(claim.path("owner_epoch").asText()))throw conflict();return claim;}
    private static JsonNode target(JdbcResultState.Identity id){var n=JsonMapper.shared().createObjectNode();n.put("root_id",id.rootId());n.put("attempt_id",id.attemptId());n.put("dispatch_token",id.dispatchToken());n.put("slot_revision",id.slotRevision());n.put("fencing",id.fencing());n.put("input_hash",id.inputHash());n.put("request_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(id.frozenRequestJson())));n.put("initial_lease_until",id.leaseUntil().toString());return n;}
    private static boolean same(JsonNode a,JsonNode b){return Arrays.equals(CanonicalJson.bytes(a),CanonicalJson.bytes(b));}
    private static byte[] sha(JsonNode value){return HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(value)));}
    private static void one(int count){if(count!=1)throw conflict();}
    private static IllegalStateException conflict(){return new IllegalStateException("known attempt failure identity conflict");}
}
