package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Abandons one verified retained result. No content, slot, attempt, input, or budget mutation. */
public final class JdbcOwnerResultDiscard {
    public record Command(String rootId,long expectedRevision,String actor,String reason,String hash){@Override public String toString(){return "OwnerDiscardCommand[redacted]";}}
    public record Discarded(String rootId,long revision,String resultHash,String operationRef){ }
    public record Pending(JdbcResultState.Root root,String type,JdbcResultState.Identity identity,String envelope){@Override public String toString(){return "PendingOriginalResult[redacted]";}}
    public static final class Rejected extends RuntimeException{public Rejected(String code){super(code);}}
    private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcResultState results;private final JdbcWorkerState worker;private final JdbcWorkerRecovery recovery;
    public JdbcOwnerResultDiscard(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);results=new JdbcResultState(source);worker=new JdbcWorkerState(source);recovery=new JdbcWorkerRecovery(source,worker);}
    public Discarded committed(String key,Command command){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw identity();
        var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,call_count,max_attempts,priority_no FROM jobs WHERE action_key_hash=UNHEX(?)",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getObject(10,Integer.class),r.getObject(11,Integer.class),r.getObject(12,Integer.class)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw identity();var row=rows.getFirst();
        try{
            if(!operation(key).equals(row[0])||!"disposition".equals(row[1])||!command.actor().equals(row[2])||!command.reason().equals(row[3])||!"job_discard_result".equals(row[4])||!Integer.valueOf(0).equals(row[9])||row[10]!=null||!Integer.valueOf(0).equals(row[11])||row[5]==null||row[7]==null)throw identity();
            var target=CanonicalJson.parse((String)row[5]);var payload=CanonicalJson.parse((String)row[7]);if(!Arrays.equals((byte[])row[6],digest(JdbcFrozenInputs.canonical(target)))||!Arrays.equals((byte[])row[8],digest(JdbcFrozenInputs.canonical(payload))))throw identity();
            var targetFields=Set.of("root_id","expected_revision");WireValues.object(target,targetFields,targetFields);var fields=Set.of("schema_version","command_hash","root_id","revision","result_hash","state");WireValues.object(payload,fields,fields);
            String hash=WireValues.sha256(payload.get("result_hash"));long revision=WireValues.nonnegativeInteger(payload.get("revision"));
            if(!command.rootId().equals(WireValues.id(target.get("root_id"),false))||command.expectedRevision()!=WireValues.nonnegativeInteger(target.get("expected_revision"))||!command.rootId().equals(WireValues.id(payload.get("root_id"),false))||!command.hash().equals(WireValues.sha256(payload.get("command_hash")))||!"owner-discard-result-v1".equals(WireValues.text(payload.get("schema_version")))||!"discarded".equals(WireValues.text(payload.get("state")))||revision!=command.expectedRevision()+1)throw identity();return new Discarded(command.rootId(),revision,hash,operation(key));
        }catch(RuntimeException invalid){throw identity();}
    }
    public Pending pending(Command command){
        JdbcFrozenInputs.requireTx();
        var rows=sql.query("SELECT id,state,revision,call_count,max_attempts,input_state,LOWER(HEX(input_hash)),generation,task_type,LOWER(HEX(result_hash)),CASE WHEN OCTET_LENGTH(result_json) BETWEEN 1 AND 16384 THEN result_json ELSE NULL END,OCTET_LENGTH(input_snapshot_json),applied_at,application_state FROM jobs WHERE id=? AND record_kind='logical_task' FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,Long.class),r.getObject(4,Integer.class),r.getObject(5,Integer.class),r.getString(6),r.getString(7),r.getObject(8,Long.class),r.getString(9),r.getString(10),r.getString(11),r.getObject(12,Long.class),r.getTimestamp(13)!=null,r.getString(14)},command.rootId());
        if(rows.isEmpty()||!command.rootId().equals(rows.getFirst()[0]))throw new Rejected("not_found");if(rows.size()!=1)throw identity();var row=rows.getFirst();
        if(row[2]==null||(long)row[2]!=command.expectedRevision())throw new Rejected("revision_conflict");
        if((long)row[2]>=CanonicalJson.MAX_SAFE_INTEGER||row[3]==null||(int)row[3]<1||(int)row[3]>3||!Integer.valueOf(3).equals(row[4])||!Set.of("result_conflict","blocked","superseded").contains(row[1])||(boolean)row[12])throw new Rejected("run_frozen");
        if(!"ready".equals(row[5])||row[6]==null||!((String)row[6]).matches("[0-9a-f]{64}")||row[7]==null||(long)row[7]<1||(long)row[7]>CanonicalJson.MAX_SAFE_INTEGER||row[9]==null||!((String)row[9]).matches("[0-9a-f]{64}")||row[10]==null||row[11]==null||(long)row[11]<1||(long)row[11]>65536)throw proof();
        var sizes=sql.query("SELECT COUNT(*),SUM(OCTET_LENGTH(payload_json)),SUM(payload_json IS NULL) FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id='input'",(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},command.rootId()).getFirst();if(sizes[0]<1||sizes[0]>64||sizes[1]<1||sizes[1]>6L*1024*1024||sizes[2]!=0)throw proof();
        var manifest=CanonicalJson.parse((String)row[10]);var manifestFields=Set.of("schema_version","part_id","result_hash");WireValues.object(manifest,manifestFields,manifestFields);String part=WireValues.id(manifest.get("part_id"),false);if(!"retained-ai-result-manifest-v1".equals(WireValues.text(manifest.get("schema_version")))||!row[9].equals(WireValues.sha256(manifest.get("result_hash"))))throw proof();
        var parts=sql.query("SELECT OCTET_LENGTH(payload_json) FROM jobs WHERE id=? AND record_kind='snapshot_part' AND owner_job_id=? AND purpose='result' AND stamp_id=? AND part_no=0",(r,n)->r.getObject(1,Long.class),part,command.rootId(),row[9]);if(parts.size()!=1||parts.getFirst()==null||parts.getFirst()<1||parts.getFirst()>256*1024)throw proof();
        var attempts=sql.query("SELECT id,state,snapshot_owner_id,LOWER(HEX(input_hash)),CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END,CASE WHEN OCTET_LENGTH(result_json) BETWEEN 1 AND 16384 THEN result_json ELSE NULL END,call_count,max_attempts,priority_no,payload_json IS NULL FROM jobs WHERE root_job_id=? AND record_kind='ai_attempt' AND result_hash=UNHEX(?) LIMIT 2 FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,Integer.class),r.getObject(8,Integer.class),r.getObject(9,Integer.class),r.getBoolean(10)},command.rootId(),row[9]);if(attempts.size()!=1)throw proof();var attempt=attempts.getFirst();
        if(!"succeeded".equals(attempt[1])||!command.rootId().equals(attempt[2])||!row[6].equals(attempt[3])||attempt[4]==null||attempt[5]==null||!Integer.valueOf(0).equals(attempt[6])||attempt[7]!=null||!Integer.valueOf(0).equals(attempt[8])||!Boolean.TRUE.equals(attempt[9])||!Arrays.equals(CanonicalJson.bytes(manifest),CanonicalJson.bytes(CanonicalJson.parse((String)attempt[5]))))throw proof();
        String attemptId=WireValues.id((String)attempt[0],false);var original=recovery.attempt(attemptId);if(original==null||!command.rootId().equals(original.root())||original.calls()!=(int)row[3])throw proof();
        var target=JsonMapper.shared().createObjectNode().put("root_id",command.rootId()).put("attempt_id",attemptId).put("dispatch_token",original.token()).put("slot_revision",original.slotRevision());var claim=CanonicalJson.parse(worker.claimReceipt(JdbcResultState.sha(JdbcFrozenInputs.canonical(target))));
        var identity=new JdbcResultState.Identity(command.rootId(),attemptId,Long.toString(original.token()),original.slotRevision(),original.token(),Instant.parse(WireValues.text(claim.get("lease_until"))),(String)attempt[4],(String)row[6]);results.claim(identity);
        var root=new JdbcResultState.Root(command.rootId(),(String)row[6],(long)row[2],(long)row[7],(String)row[1],(String)row[13],(String)row[9],(String)row[10],null);return new Pending(root,(String)row[8],identity,results.retained(root));
    }
    public Discarded discard(String key,Command command,Pending original,Instant now){
        JdbcFrozenInputs.requireTx();if(now==null||now.getNano()%1000!=0||original==null||!command.rootId().equals(original.root().id())||command.expectedRevision()!=original.root().revision())throw identity();
        var root=original.root();if(sql.update(c->{var statement=c.prepareStatement("UPDATE jobs SET state='discarded',application_state='DISCARDED',revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND state=? AND result_hash=UNHEX(?) AND applied_at IS NULL");statement.setTimestamp(1,java.sql.Timestamp.from(now),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(2,root.id());statement.setLong(3,root.revision());statement.setString(4,root.state());statement.setString(5,root.resultHash());return statement;})!=1)throw new Rejected("revision_conflict");
        var target=JsonMapper.shared().createObjectNode().put("root_id",root.id()).put("expected_revision",command.expectedRevision());var payload=JsonMapper.shared().createObjectNode().put("schema_version","owner-discard-result-v1").put("command_hash",command.hash()).put("root_id",root.id()).put("revision",root.revision()+1).put("result_hash",root.resultHash()).put("state","discarded");records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,"job_discard_result",JdbcFrozenInputs.canonical(target),command.actor(),command.reason(),JdbcFrozenInputs.canonical(payload)),now);return new Discarded(root.id(),root.revision()+1,root.resultHash(),operation(key));
    }
    private static String operation(String key){return JdbcFrozenInputs.stableId("owner-job-discard",key);}
    private static byte[] digest(String value){return HexFormat.of().parseHex(JdbcResultState.sha(value));}
    private static Rejected identity(){return new Rejected("identity_conflict");}
    private static IllegalStateException proof(){return new IllegalStateException("original retained result proof unavailable");}
}
