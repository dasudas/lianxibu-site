package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Root cancellation CAS and its original receipt. Never edits a slot, attempt, INPUT or budget. */
public final class JdbcOwnerJobCancellation {
    public record Command(String rootId,long expectedRevision,String actor,String reason,String hash){ }
    public record Cancelled(String rootId,long revision,String state,String operationRef){ }
    public static final class Rejected extends RuntimeException{public Rejected(String code){super(code);}}
    private static final Set<String> UNSENT=Set.of("waiting_input","prepared","queued","waiting_retry","blocked_configuration");
    private final JdbcTemplate sql;private final JdbcJobRecords records;
    public JdbcOwnerJobCancellation(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);}
    public Cancelled committed(String key,Command command){
        transaction();var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,call_count,max_attempts,priority_no FROM jobs WHERE action_key_hash=UNHEX(?)",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getInt(10),r.getObject(11),r.getInt(12)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw identity();var row=rows.getFirst();
        try{
            if(!operation(key).equals(row[0])||!"disposition".equals(row[1])||!command.actor().equals(row[2])||!command.reason().equals(row[3])||!"job_cancel".equals(row[4])||(int)row[9]!=0||row[10]!=null||(int)row[11]!=0||row[5]==null||row[7]==null)throw identity();
            var target=CanonicalJson.parse((String)row[5]);var payload=CanonicalJson.parse((String)row[7]);
            if(!Arrays.equals((byte[])row[6],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(target))))||!Arrays.equals((byte[])row[8],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(payload)))))throw identity();
            WireValues.object(target,Set.of("root_id","expected_revision"),Set.of("root_id","expected_revision"));var fields=Set.of("schema_version","command_hash","root_id","revision","from_state","state","changed");WireValues.object(payload,fields,fields);
            if(!command.rootId().equals(WireValues.text(target.get("root_id")))||command.expectedRevision()!=WireValues.nonnegativeInteger(target.get("expected_revision"))||!command.rootId().equals(WireValues.text(payload.get("root_id")))||!command.hash().equals(WireValues.sha256(payload.get("command_hash")))||!"owner-job-cancel-v1".equals(WireValues.text(payload.get("schema_version")))||!payload.get("changed").isBoolean())throw identity();
            String before=WireValues.text(payload.get("from_state")),state=WireValues.text(payload.get("state"));boolean changed=payload.get("changed").booleanValue();long revision=WireValues.nonnegativeInteger(payload.get("revision"));
            if(!Set.of("cancelled","cancel_requested").contains(state)||(changed?(revision!=command.expectedRevision()+1||!state.equals("running".equals(before)?"cancel_requested":UNSENT.contains(before)?"cancelled":"invalid")):(revision!=command.expectedRevision()||!state.equals(before))))throw identity();
            return new Cancelled(command.rootId(),revision,state,operation(key));
        }catch(RuntimeException invalid){throw identity();}
    }
    public Cancelled cancel(String key,Command command,Instant now){
        JdbcFrozenInputs.requireTx();if(now==null||now.getNano()%1000!=0)throw identity();
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,result_hash,applied_at,task_type FROM jobs WHERE id=? FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Long.class),r.getObject(5,Integer.class),r.getObject(6,Integer.class),r.getBytes(7),r.getTimestamp(8),r.getString(9)},command.rootId());
        if(rows.isEmpty()||!command.rootId().equals(rows.getFirst()[0]))throw new Rejected("not_found");if(rows.size()!=1)throw identity();var row=rows.getFirst();
        if(!"logical_task".equals(row[1])||"source_day_roster".equals(row[8])||row[3]==null||row[4]==null||!Integer.valueOf(3).equals(row[5])||(int)row[4]<0||(int)row[4]>3)throw new Rejected("run_frozen");
        long revision=(long)row[3];if(revision!=command.expectedRevision())throw new Rejected("revision_conflict");if(revision>=CanonicalJson.MAX_SAFE_INTEGER||row[6]!=null||row[7]!=null)throw new Rejected("run_frozen");
        String before=(String)row[2],state;if(UNSENT.contains(before))state="cancelled";else if("running".equals(before))state="cancel_requested";else if(Set.of("cancelled","cancel_requested").contains(before))state=before;else throw new Rejected("run_frozen");boolean changed=!state.equals(before);long resultRevision=revision+(changed?1:0);
        if(changed&&sql.update(connection->{var statement=connection.prepareStatement("UPDATE jobs SET state=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=? AND state=? AND call_count=? AND max_attempts=3 AND result_hash IS NULL AND applied_at IS NULL");statement.setString(1,state);statement.setTimestamp(2,java.sql.Timestamp.from(now),Calendar.getInstance(TimeZone.getTimeZone("UTC")));statement.setString(3,command.rootId());statement.setLong(4,revision);statement.setString(5,before);statement.setInt(6,(int)row[4]);return statement;})!=1)throw new Rejected("revision_conflict");
        var target=JsonMapper.shared().createObjectNode().put("root_id",command.rootId()).put("expected_revision",command.expectedRevision());var payload=JsonMapper.shared().createObjectNode().put("schema_version","owner-job-cancel-v1").put("command_hash",command.hash()).put("root_id",command.rootId()).put("revision",resultRevision).put("from_state",before).put("state",state).put("changed",changed);
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,"job_cancel",JdbcFrozenInputs.canonical(target),command.actor(),command.reason(),JdbcFrozenInputs.canonical(payload)),now);
        return new Cancelled(command.rootId(),resultRevision,state,operation(key));
    }
    public static String operation(String key){return JdbcFrozenInputs.stableId("owner-job-cancel",key);}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw identity();}
    private static Rejected identity(){return new Rejected("identity_conflict");}
}
