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

/** Immutable attribution of provider metadata; no result or slot transition occurs here. */
public final class JdbcAttemptUsage {
    private final JdbcTemplate jdbc;
    private final JdbcResultState proof;
    private final JdbcJobRecords records;
    public JdbcAttemptUsage(DataSource source){Objects.requireNonNull(source);jdbc=new JdbcTemplate(source);proof=new JdbcResultState(source);records=new JdbcJobRecords(source);}
    public Boolean committed(String key,JdbcResultState.Identity identity,String owner,String metadata) {
        claim(identity,owner);
        var rows=jdbc.query("SELECT target_type,target_key_json,target_key_hash,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();
        var target=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[4]);
        if(!"attempt_usage".equals(row[0])||!owner.equals(row[3])||!same(target,target(identity))
                ||!Arrays.equals((byte[])row[2],sha(target))||!Arrays.equals((byte[])row[5],sha(payload)))throw conflict();
        var fields=Set.of("schema_version","metadata");WireValues.object(payload,fields,fields);
        if(!"attempt-usage-v1".equals(payload.path("schema_version").asText())||!same(CanonicalJson.parse(metadata),payload.get("metadata")))throw conflict();
        return true;
    }
    public boolean record(String key,JdbcResultState.Identity identity,String owner,String metadata,Instant now) {
        JdbcFrozenInputs.requireTx();var claim=claim(identity,owner);if(!proof.owns(identity,claim,now))throw conflict();
        var incoming=CanonicalJson.parse(metadata);var usage=incoming.get("usage");String provider=incoming.get("provider_request_id").isNull()?null:incoming.get("provider_request_id").asText();
        var rows=jdbc.query("SELECT provider_request_id,usage_json,cost_decimal,revision FROM jobs WHERE id=? AND record_kind='ai_attempt' AND state='running' AND root_job_id=? AND worker_instance_id=? AND dispatch_token=?",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)},identity.attemptId(),identity.rootId(),owner,identity.fencing());
        if(rows.size()!=1)throw conflict();var old=rows.getFirst();long revision=(long)old[3];
        if(old[2]!=null||revision>=CanonicalJson.MAX_SAFE_INTEGER)throw conflict();
        if(old[1]!=null) {
            if(!Objects.equals(old[0],provider)||!same(CanonicalJson.parse((String)old[1]),usage))throw conflict();
        }else {
            if(old[0]!=null&&!Objects.equals(old[0],provider))throw conflict();
            if(jdbc.update("UPDATE jobs SET provider_request_id=?,usage_json=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND state='running' AND revision=? AND worker_instance_id=? AND dispatch_token=? AND usage_json IS NULL",
                    provider,JdbcFrozenInputs.canonical(usage),Timestamp.from(now),identity.attemptId(),revision,owner,identity.fencing())!=1)throw conflict();
        }
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","attempt-usage-v1");payload.set("metadata",incoming);
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("attempt-usage",key),key,"attempt_usage",JdbcFrozenInputs.canonical(target(identity)),owner,"provider reported usage",JdbcFrozenInputs.canonical(payload)),now);
        return true;
    }
    private JsonNode claim(JdbcResultState.Identity identity,String owner){if(identity==null||owner==null)throw conflict();var claim=proof.claim(identity);if(!owner.equals(claim.path("owner_epoch").asText()))throw conflict();return claim;}
    private static JsonNode target(JdbcResultState.Identity id){var node=JsonMapper.shared().createObjectNode();node.put("root_id",id.rootId());node.put("attempt_id",id.attemptId());node.put("dispatch_token",id.dispatchToken());node.put("slot_revision",id.slotRevision());node.put("fencing",id.fencing());node.put("input_hash",id.inputHash());node.put("request_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(id.frozenRequestJson())));node.put("initial_lease_until",id.leaseUntil().toString());return node;}
    private static boolean same(JsonNode a,JsonNode b){return a!=null&&b!=null&&Arrays.equals(CanonicalJson.bytes(a),CanonicalJson.bytes(b));}
    private static byte[] sha(JsonNode value){return HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(value)));}
    private static IllegalStateException conflict(){return new IllegalStateException("provider usage identity conflict");}
}
