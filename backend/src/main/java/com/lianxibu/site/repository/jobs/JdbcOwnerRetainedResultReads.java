package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Original bounded native associations only; caller owns semantic result and content validation. */
public final class JdbcOwnerRetainedResultReads {
    public record Frame(JdbcOwnerJobViews.Row root,long generation,long latestGeneration,String inputHash,
            String resultHash,Instant appliedAt,String inputJson,String envelope,JdbcResultState.Identity identity) {
        @Override public String toString(){return "OwnerOriginalResultFrame[redacted]";}
    }
    private final JdbcTemplate sql;
    private final JdbcOwnerJobViews views;
    private final JdbcFrozenInputs inputs;
    private final JdbcWorkerState worker;
    private final JdbcWorkerRecovery recovery;
    private final JdbcResultState results;
    public JdbcOwnerRetainedResultReads(DataSource source){
        sql=new JdbcTemplate(Objects.requireNonNull(source));views=new JdbcOwnerJobViews(source);
        inputs=new JdbcFrozenInputs(source);worker=new JdbcWorkerState(source);
        recovery=new JdbcWorkerRecovery(source,worker);results=new JdbcResultState(source);
    }
    public Frame read(String id){
        requireRead();return boundedFrame(id,false);
    }
    public Frame readForApplication(String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw unavailable();
        return boundedFrame(id,true);
    }
    private Frame boundedFrame(String id,boolean writer){
        WireValues.id(id,false);var root=writer?views.applicationJob(id):views.job(id);
        if(root==null||!"logical_task".equals(root.kind()))return null;
        var headers=sql.query("SELECT r.generation,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash),r.result_hash,CASE WHEN OCTET_LENGTH(r.result_json) BETWEEN 1 AND 16384 THEN r.result_json ELSE NULL END,OCTET_LENGTH(r.result_json),r.applied_at,CASE WHEN OCTET_LENGTH(r.logical_key) BETWEEN 1 AND 4096 THEN r.logical_key ELSE NULL END,r.logical_key_hash FROM jobs r WHERE r.id=? AND r.record_kind='logical_task'",(r,n)->new Object[]{r.getObject(1,Long.class),r.getObject(2,Long.class),r.getBytes(3),r.getString(4),r.getObject(5,Long.class),r.getTimestamp(6,utc()),r.getString(7),r.getBytes(8)},id);
        if(headers.size()!=1)throw unavailable();var header=headers.getFirst();
        if(header[0]==null||header[1]==null)throw unavailable();long generation=(long)header[0],latest=(long)header[1];
        if(generation<1||latest<generation||latest>CanonicalJson.MAX_SAFE_INTEGER||header[6]==null||header[7]==null||((byte[])header[7]).length!=32||!JdbcResultState.sha((String)header[6]).equals(HexFormat.of().formatHex((byte[])header[7])))throw unavailable();
        var intents=sql.query("SELECT CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,call_count,max_attempts,priority_no FROM jobs WHERE record_kind='generation_intent' AND logical_key_hash=? AND generation=? LIMIT 2",(r,n)->new Object[]{r.getString(1),r.getObject(2,Integer.class),r.getObject(3,Integer.class),r.getObject(4,Integer.class)},header[7],latest);
        if(intents.size()!=1||!header[6].equals(intents.getFirst()[0])||!Integer.valueOf(0).equals(intents.getFirst()[1])||intents.getFirst()[2]!=null||!Integer.valueOf(0).equals(intents.getFirst()[3]))throw unavailable();
        var reference=writer?views.applicationInputReference(id):views.inputReference(id);if(reference.generation()!=generation)throw unavailable();
        Instant applied=header[5]==null?null:((java.sql.Timestamp)header[5]).toInstant();
        if(header[2]==null){if(header[4]!=null||applied!=null)throw unavailable();return new Frame(root,generation,latest,reference.hash(),null,null,null,null,null);}
        if(((byte[])header[2]).length!=32||header[3]==null||!reference.present()||!"ready".equals(root.inputState())||root.callCount()<1)throw unavailable();
        String hash=HexFormat.of().formatHex((byte[])header[2]);var manifest=CanonicalJson.parse((String)header[3]);
        var fields=Set.of("schema_version","part_id","result_hash");WireValues.object(manifest,fields,fields);
        if(!"retained-ai-result-manifest-v1".equals(WireValues.text(manifest.get("schema_version")))||!hash.equals(WireValues.sha256(manifest.get("result_hash"))))throw unavailable();
        String part=WireValues.id(manifest.get("part_id"),false);
        var sizes=sql.query("SELECT owner_job_id,purpose,stamp_id,part_no,OCTET_LENGTH(payload_json) FROM jobs WHERE id=? AND record_kind='snapshot_part'",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Integer.class),r.getObject(5,Long.class)},part);
        if(sizes.size()!=1)throw unavailable();var size=sizes.getFirst();
        if(!id.equals(size[0])||!"result".equals(size[1])||!hash.equals(size[2])||!Integer.valueOf(0).equals(size[3])||size[4]==null||(long)size[4]<1||(long)size[4]>262144)throw unavailable();
        var attempts=sql.query("SELECT id,state,snapshot_owner_id,LOWER(HEX(input_hash)),CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END,CASE WHEN OCTET_LENGTH(result_json) BETWEEN 1 AND 16384 THEN result_json ELSE NULL END,call_count,max_attempts,priority_no,payload_json IS NULL,attempt_no,send_authorized_at IS NOT NULL FROM jobs WHERE root_job_id=? AND record_kind='ai_attempt' AND result_hash=UNHEX(?) AND attempt_no=? LIMIT 2",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,Integer.class),r.getObject(8,Integer.class),r.getObject(9,Integer.class),r.getBoolean(10),r.getObject(11,Integer.class),r.getBoolean(12)},id,hash,root.callCount());
        if(attempts.size()!=1)throw unavailable();var attempt=attempts.getFirst();
        if(!"succeeded".equals(attempt[1])||!id.equals(attempt[2])||!reference.hash().equals(attempt[3])||attempt[4]==null||attempt[5]==null||!Integer.valueOf(0).equals(attempt[6])||attempt[7]!=null||!Integer.valueOf(0).equals(attempt[8])||!Boolean.TRUE.equals(attempt[9])||!Integer.valueOf(root.callCount()).equals(attempt[10])||!Boolean.TRUE.equals(attempt[11])||!Arrays.equals(CanonicalJson.bytes(manifest),CanonicalJson.bytes(CanonicalJson.parse((String)attempt[5]))))throw unavailable();
        String attemptId=WireValues.id((String)attempt[0],false);var original=recovery.attempt(attemptId);
        if(original==null||!id.equals(original.root())||original.calls()!=root.callCount())throw unavailable();
        var target=JsonMapper.shared().createObjectNode().put("root_id",id).put("attempt_id",attemptId).put("dispatch_token",original.token()).put("slot_revision",original.slotRevision());
        var claim=CanonicalJson.parse(worker.claimReceipt(JdbcResultState.sha(JdbcFrozenInputs.canonical(target))));
        var request=CanonicalJson.parse((String)attempt[4]);
        if(!reference.hash().equals(WireValues.sha256(claim.get("input_hash")))||!reference.hash().equals(WireValues.sha256(request.get("input_hash")))||!CanonicalJson.hash(CanonicalJson.Domain.ACTION,request).equals(WireValues.sha256(claim.get("request_hash")))||root.revision()<WireValues.nonnegativeInteger(claim.get("root_revision")))throw unavailable();
        var identity=new JdbcResultState.Identity(id,attemptId,Long.toString(original.token()),original.slotRevision(),original.token(),WireValues.utcMicroseconds(claim.get("lease_until")),(String)attempt[4],reference.hash());
        String input=inputs.read(id,reference.hash());if(input.getBytes(StandardCharsets.UTF_8).length>4*1024*1024||!header[6].equals(WireValues.text(CanonicalJson.parse(input).get("logical_key"))))throw unavailable();
        var nativeRoot=new JdbcResultState.Root(id,reference.hash(),root.revision(),latest,root.state(),root.applicationState(),hash,(String)header[3],applied);
        return new Frame(root,generation,latest,reference.hash(),hash,applied,input,results.retained(nativeRoot),identity);
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void requireRead(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw unavailable();}
    private static IllegalStateException unavailable(){return new IllegalStateException("original retained result associations unavailable");}
}
