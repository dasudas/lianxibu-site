package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ends the original authorized attempt conservatively; never prepares another attempt. */
public final class JdbcTransportUnknown {
    public record Receipt(boolean slotReleased,int callCount) { }
    private final JdbcTemplate jdbc;
    private final JdbcResultState proof;
    private final JdbcWorkerRecovery recovery;
    private final JdbcJobRecords records;
    public JdbcTransportUnknown(DataSource source) {
        Objects.requireNonNull(source);jdbc=new JdbcTemplate(source);proof=new JdbcResultState(source);
        recovery=new JdbcWorkerRecovery(source,new JdbcWorkerState(source));records=new JdbcJobRecords(source);
    }
    public Receipt committed(String key,JdbcResultState.Identity id,String owner,String cause) {
        claim(id,owner,cause);
        var rows=jdbc.query("SELECT target_type,target_key_json,target_key_hash,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();
        var target=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[4]);
        if(!"transport_unknown".equals(row[0])||!owner.equals(row[3])||!Arrays.equals(CanonicalJson.bytes(target(id)),CanonicalJson.bytes(target))
                ||!Arrays.equals((byte[])row[2],sha(target))||!Arrays.equals((byte[])row[5],sha(payload)))throw conflict();
        var fields=Set.of("schema_version","cause","slot_released","call_count");WireValues.object(payload,fields,fields);
        if(!"transport-unknown-v1".equals(payload.path("schema_version").asText())||!cause.equals(payload.path("cause").asText())||!payload.path("slot_released").isBoolean())throw conflict();
        long count=WireValues.nonnegativeInteger(payload.get("call_count"));if(count<1||count>3)throw conflict();
        return new Receipt(payload.path("slot_released").booleanValue(),(int)count);
    }
    public Receipt release(String key,JdbcResultState.Identity id,String owner,String cause,Instant now) {
        JdbcFrozenInputs.requireTx();claim(id,owner,cause);
        if(now==null||now.getNano()%1000!=0)throw conflict();
        var value=recovery.attempt(id.attemptId());
        if(value==null||!id.rootId().equals(value.root())||!owner.equals(value.owner())||id.fencing()!=value.token()||id.slotRevision()!=value.slotRevision()
                ||value.calls()<1||value.calls()>3||value.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        var valid=jdbc.queryForObject("SELECT COUNT(*) FROM jobs r JOIN jobs a ON a.root_job_id=r.id WHERE r.id=? AND a.id=? AND r.record_kind='logical_task' AND a.record_kind='ai_attempt' AND r.result_hash IS NULL AND a.result_hash IS NULL AND r.applied_at IS NULL AND r.revision<? AND a.revision<? AND ((r.state IN ('running','cancel_requested') AND a.state='running') OR (r.state IN ('unknown_external','blocked') AND a.state='unknown_external'))",
                Integer.class,id.rootId(),id.attemptId(),CanonicalJson.MAX_SAFE_INTEGER,CanonicalJson.MAX_SAFE_INTEGER);
        if(valid==null||valid!=1)throw conflict();
        recovery.unknown(value,cause,now);boolean released=recovery.release(value);
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","transport-unknown-v1");payload.put("cause",cause);payload.put("slot_released",released);payload.put("call_count",value.calls());
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("transport-unknown",key),key,"transport_unknown",JdbcFrozenInputs.canonical(target(id)),owner,cause,JdbcFrozenInputs.canonical(payload)),now);
        return new Receipt(released,value.calls());
    }
    private void claim(JdbcResultState.Identity id,String owner,String cause) {
        if(id==null||owner==null||!Set.of("TRANSPORT_UNKNOWN","LOCAL_CANCELLED").contains(cause))throw conflict();
        var claim=proof.claim(id);if(!owner.equals(claim.path("owner_epoch").asText()))throw conflict();
    }
    private static JsonNode target(JdbcResultState.Identity id) {
        var n=JsonMapper.shared().createObjectNode();n.put("root_id",id.rootId());n.put("attempt_id",id.attemptId());n.put("dispatch_token",id.dispatchToken());
        n.put("slot_revision",id.slotRevision());n.put("fencing",id.fencing());n.put("initial_lease_until",id.leaseUntil().toString());n.put("input_hash",id.inputHash());
        n.put("request_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(id.frozenRequestJson())));return n;
    }
    private static byte[] sha(JsonNode value){return HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(value)));}
    private static IllegalStateException conflict(){return new IllegalStateException("transport recovery identity conflict");}
}
