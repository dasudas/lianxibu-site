package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Lease-only mutation plus immutable heartbeat receipts in the existing jobs table. */
public final class JdbcWorkerLease {
    private final JdbcTemplate jdbc;
    private final JdbcWorkerState worker;
    private final JdbcResultState proof;
    private final JdbcJobRecords records;
    public JdbcWorkerLease(DataSource source) {
        Objects.requireNonNull(source);jdbc=new JdbcTemplate(source);worker=new JdbcWorkerState(source);
        proof=new JdbcResultState(source);records=new JdbcJobRecords(source);
    }
    public Instant committed(String key,JdbcResultState.Identity identity,String owner) {
        claim(identity,owner);
        var rows=jdbc.query("SELECT target_type,target_key_json,target_key_hash,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=UNHEX(?)",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getString(4),rs.getString(5),rs.getBytes(6)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw conflict();var row=rows.getFirst();
        var target=target(identity);var persisted=CanonicalJson.parse((String)row[1]);var payload=CanonicalJson.parse((String)row[4]);
        if(!"worker_heartbeat".equals(row[0])||!owner.equals(row[3])||!Arrays.equals(CanonicalJson.bytes(target),CanonicalJson.bytes(persisted))
                ||!Arrays.equals((byte[])row[2],sha(persisted))||!Arrays.equals((byte[])row[5],sha(payload)))throw conflict();
        var fields=Set.of("schema_version","identity_hash","lease_until");WireValues.object(payload,fields,fields);
        if(!"worker-heartbeat-v1".equals(payload.path("schema_version").asText())
                ||!identityHash(target).equals(payload.path("identity_hash").asText()))throw conflict();
        Instant lease=WireValues.utcMicroseconds(payload.get("lease_until"));
        if(lease.isBefore(identity.leaseUntil()))throw conflict();return lease;
    }
    public Instant renew(String key,JdbcResultState.Identity identity,String owner,Instant now) {
        JdbcFrozenInputs.requireTx();var claim=claim(identity,owner);
        if(now==null||now.getNano()%1000!=0||!proof.owns(identity,claim,now))throw conflict();
        var deadline=jdbc.query("SELECT request_deadline FROM jobs WHERE id=? AND record_kind='ai_attempt' AND state='running' AND worker_instance_id=? AND dispatch_token=? AND send_authorized_at IS NOT NULL",
                (rs,n)->rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toInstant(),identity.attemptId(),owner,identity.fencing());
        if(deadline.size()!=1||deadline.getFirst()==null||!now.isBefore(deadline.getFirst()))throw conflict();
        var slot=worker.slot();Instant lease=now.plusSeconds(150);if(slot.lease().isAfter(lease))lease=slot.lease();
        if(jdbc.update("UPDATE worker_lock SET lease_until=? WHERE id=? AND slot_state='occupied' AND job_id=? AND holder=? AND fencing_token=? AND slot_revision=? AND lease_until=?",
                Timestamp.from(lease),slot.id(),identity.attemptId(),owner,identity.fencing(),identity.slotRevision(),Timestamp.from(slot.lease()))!=1)throw conflict();
        var target=target(identity);var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version","worker-heartbeat-v1");payload.put("identity_hash",identityHash(target));
        payload.put("lease_until",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(lease));
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("heartbeat",key),key,"worker_heartbeat",JdbcFrozenInputs.canonical(target),owner,"worker lease renewal",JdbcFrozenInputs.canonical(payload)),now);
        return lease;
    }
    private JsonNode claim(JdbcResultState.Identity identity,String owner) {
        if(identity==null||owner==null)throw conflict();var claim=proof.claim(identity);
        if(!owner.equals(claim.path("owner_epoch").asText()))throw conflict();return claim;
    }
    private static JsonNode target(JdbcResultState.Identity identity) {
        var n=JsonMapper.shared().createObjectNode();n.put("root_id",identity.rootId());n.put("attempt_id",identity.attemptId());
        n.put("dispatch_token",identity.dispatchToken());n.put("slot_revision",identity.slotRevision());n.put("fencing",identity.fencing());
        n.put("initial_lease_until",identity.leaseUntil().toString());n.put("input_hash",identity.inputHash());
        n.put("request_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(identity.frozenRequestJson())));return n;
    }
    private static String identityHash(JsonNode target){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,target);}
    private static byte[] sha(JsonNode node){return HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(node)));}
    private static IllegalStateException conflict(){return new IllegalStateException("worker lease identity unavailable");}
}
