package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Original root recovery CAS. This queues an exact original request without changing its used budget. */
public final class JdbcOwnerJobRecovery {
    public record Command(String rootId,long expectedRevision,String actor,String reason,String hash){ }
    public record Recovered(String rootId,long revision,int calls,String attemptId,String operationRef){ }
    public static final class Rejected extends RuntimeException{public Rejected(String code){super(code);}}
    private record Root(String id,String state,Long revision,Integer calls,Integer maximum,String inputState,byte[] inputHash,byte[] resultHash,boolean applied,Long generation,Long latest,String error){ }
    private record Attempt(String id,String root,Integer number,String state,String owner,byte[] hash,String request,byte[] result,Integer calls,Integer maximum,Integer priority){@Override public String toString(){return "RecoveryAttempt[redacted]";}}
    private final JdbcTemplate sql;private final JdbcJobRecords records;
    public JdbcOwnerJobRecovery(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public Recovered committed(String key,Command command){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw identity();
        var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,call_count,max_attempts,priority_no FROM jobs WHERE action_key_hash=UNHEX(?)",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getObject(10,Integer.class),r.getObject(11,Integer.class),r.getObject(12,Integer.class)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw identity();var row=rows.getFirst();
        try{
            if(!operation(key).equals(row[0])||!"disposition".equals(row[1])||!command.actor().equals(row[2])||!command.reason().equals(row[3])||!"job_recover".equals(row[4])||!Integer.valueOf(0).equals(row[9])||row[10]!=null||!Integer.valueOf(0).equals(row[11])||row[5]==null||row[7]==null)throw identity();
            var target=CanonicalJson.parse((String)row[5]);var payload=CanonicalJson.parse((String)row[7]);
            if(!Arrays.equals((byte[])row[6],digest(JdbcFrozenInputs.canonical(target)))||!Arrays.equals((byte[])row[8],digest(JdbcFrozenInputs.canonical(payload))))throw identity();
            var targetFields=Set.of("root_id","expected_revision");WireValues.object(target,targetFields,targetFields);
            var fields=Set.of("schema_version","command_hash","root_id","revision","call_count","attempt_id","state");WireValues.object(payload,fields,fields);
            long revision=WireValues.nonnegativeInteger(payload.get("revision")),calls=WireValues.nonnegativeInteger(payload.get("call_count"));String attempt=WireValues.id(payload.get("attempt_id"),false);
            if(!command.rootId().equals(WireValues.id(target.get("root_id"),false))||command.expectedRevision()!=WireValues.nonnegativeInteger(target.get("expected_revision"))||!command.rootId().equals(WireValues.id(payload.get("root_id"),false))||!command.hash().equals(WireValues.sha256(payload.get("command_hash")))||!"owner-job-recover-v1".equals(WireValues.text(payload.get("schema_version")))||!"queued".equals(WireValues.text(payload.get("state")))||revision!=command.expectedRevision()+1||calls>2||!attempt.equals(attempt(command.rootId(),(int)calls+1)))throw identity();
            return new Recovered(command.rootId(),revision,(int)calls,attempt,operation(key));
        }catch(RuntimeException invalid){throw identity();}
    }
    public Recovered recover(String key,Command command,Instant now,BooleanSupplier originalReady){
        JdbcFrozenInputs.requireTx();Objects.requireNonNull(originalReady);if(now==null||now.getNano()%1000!=0)throw identity();
        var root=checked(command,true);
        var attempts=sql.query("SELECT id,root_job_id,attempt_no,state,snapshot_owner_id,input_hash,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END,result_hash,call_count,max_attempts,priority_no FROM jobs WHERE record_kind='ai_attempt' AND root_job_id=? ORDER BY attempt_no,id LIMIT 4 FOR UPDATE",(r,n)->new Attempt(r.getString(1),r.getString(2),r.getObject(3,Integer.class),r.getString(4),r.getString(5),r.getBytes(6),r.getString(7),r.getBytes(8),r.getObject(9,Integer.class),r.getObject(10,Integer.class),r.getObject(11,Integer.class)),root.id());
        if(attempts.isEmpty()||attempts.size()>3)throw identity();String request=attempts.getFirst().request();if(request==null)throw identity();int next=root.calls()+1;String nextId=attempt(root.id(),next);boolean existing=false;var seen=new HashSet<Integer>();String previous=null;
        for(var value:attempts){
            if("unknown_external".equals(value.state()))throw new Rejected("external_result_unresolved");
            if(!root.id().equals(value.root())||!root.id().equals(value.owner())||value.number()==null||value.number()<1||value.number()>next||!seen.add(value.number())||!Arrays.equals(root.inputHash(),value.hash())||!request.equals(value.request())||value.result()!=null||!Integer.valueOf(0).equals(value.calls())||value.maximum()!=null||!Integer.valueOf(0).equals(value.priority()))throw identity();WireValues.id(value.id(),false);
            if(value.number()==next){if(!nextId.equals(value.id())||!"prepared".equals(value.state()))throw new Rejected("run_frozen");existing=true;}
            else if(!Set.of("failed","cancelled").contains(value.state()))throw new Rejected("run_frozen");
            if(value.number()==root.calls())previous=value.id();
        }
        for(int number=1;number<=root.calls();number++)if(!seen.contains(number))throw identity();
        if(!existing){records.prepareAttempt(new JdbcJobRecords.Attempt(nextId,root.id(),next,request,root.id(),HexFormat.of().formatHex(root.inputHash())),now);if(previous!=null&&sql.update("UPDATE jobs SET retry_of_job_id=? WHERE id=? AND record_kind='ai_attempt' AND state='prepared' AND retry_of_job_id IS NULL",previous,nextId)!=1)throw identity();}
        if(sql.update(c->{var statement=c.prepareStatement("UPDATE jobs SET state='queued',error_code=NULL,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND state=? AND call_count=? AND max_attempts=3 AND result_hash IS NULL AND applied_at IS NULL");statement.setTimestamp(1,java.sql.Timestamp.from(now),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(2,root.id());statement.setLong(3,root.revision());statement.setString(4,root.state());statement.setInt(5,root.calls());return statement;})!=1)throw new Rejected("revision_conflict");
        if(!originalReady.getAsBoolean())throw new Rejected("run_frozen");
        var target=JsonMapper.shared().createObjectNode().put("root_id",root.id()).put("expected_revision",command.expectedRevision());var payload=JsonMapper.shared().createObjectNode().put("schema_version","owner-job-recover-v1").put("command_hash",command.hash()).put("root_id",root.id()).put("revision",root.revision()+1).put("call_count",root.calls()).put("attempt_id",nextId).put("state","queued");
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,"job_recover",JdbcFrozenInputs.canonical(target),command.actor(),command.reason(),JdbcFrozenInputs.canonical(payload)),now);
        return new Recovered(root.id(),root.revision()+1,root.calls(),nextId,operation(key));
    }
    /** Read-only boundary check supplies precise refusals; the writer repeats every check under lock. */
    public void check(Command command){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw identity();checked(command,false);}
    private Root checked(Command command,boolean lock){
        var roots=sql.query("SELECT r.id,r.state,r.revision,r.call_count,r.max_attempts,r.input_state,r.input_hash,r.result_hash,r.applied_at,r.generation,(SELECT MAX(g.generation) FROM jobs g WHERE g.record_kind='generation_intent' AND g.logical_key_hash=r.logical_key_hash),r.error_code FROM jobs r WHERE r.id=? AND r.record_kind='logical_task'"+(lock?" FOR UPDATE":""),(r,n)->new Root(r.getString(1),r.getString(2),r.getObject(3,Long.class),r.getObject(4,Integer.class),r.getObject(5,Integer.class),r.getString(6),r.getBytes(7),r.getBytes(8),r.getTimestamp(9)!=null,r.getObject(10,Long.class),r.getObject(11,Long.class),r.getString(12)),command.rootId());
        if(roots.isEmpty()||!command.rootId().equals(roots.getFirst().id()))throw new Rejected("not_found");if(roots.size()!=1)throw identity();var root=roots.getFirst();
        if(root.revision()==null||root.calls()==null||root.calls()<0||root.calls()>3||!Integer.valueOf(3).equals(root.maximum()))throw identity();
        if(root.revision()!=command.expectedRevision())throw new Rejected("revision_conflict");
        if("unknown_external".equals(root.state())||"closed_unresolved".equals(root.error()))throw new Rejected("external_result_unresolved");
        if(root.calls()==3)throw new Rejected("retry_exhausted");
        if(root.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!Set.of("cancelled","failed","blocked_configuration").contains(root.state())||!"ready".equals(root.inputState())||root.inputHash()==null||root.inputHash().length!=32||root.resultHash()!=null||root.applied()||root.generation()==null||root.generation()<1||!root.generation().equals(root.latest()))throw new Rejected("run_frozen");
        return root;
    }
    private static String attempt(String root,int number){return JdbcFrozenInputs.stableId("attempt",root+":"+number);}
    private static String operation(String key){return JdbcFrozenInputs.stableId("owner-job-recover",key);}
    private static byte[] digest(String value){return HexFormat.of().parseHex(JdbcResultState.sha(value));}
    private static Rejected identity(){return new Rejected("identity_conflict");}
}
