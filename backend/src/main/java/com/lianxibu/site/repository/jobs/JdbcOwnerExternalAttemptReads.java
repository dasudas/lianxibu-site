package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Original native attempt and INPUT association, independent from current slot or retained result. */
public final class JdbcOwnerExternalAttemptReads {
    public record Frame(JdbcOwnerJobViews.Row root,long generation,String inputJson,long originalRootRevision,
                        int attemptNo,String attemptState,JdbcResultState.Identity identity){
        @Override public String toString(){return "OwnerOriginalExternalAttemptFrame[redacted]";}
    }
    private final JdbcTemplate sql;private final JdbcOwnerJobViews views;private final JdbcFrozenInputs inputs;
    private final JdbcWorkerState worker;private final JdbcResultState results;
    public JdbcOwnerExternalAttemptReads(DataSource source){Objects.requireNonNull(source);sql=new JdbcTemplate(source);views=new JdbcOwnerJobViews(source);inputs=new JdbcFrozenInputs(source);worker=new JdbcWorkerState(source);results=new JdbcResultState(source);}
    public Frame read(String rootId,String attemptId){
        WireValues.id(rootId,false);WireValues.id(attemptId,false);var root=views.applicationJob(rootId);
        if(root==null||!"logical_task".equals(root.kind()))return null;
        var reference=views.applicationInputReference(rootId);if(!reference.present()||!"ready".equals(root.inputState())||root.callCount()<1)throw unavailable();
        var rows=sql.query("SELECT id,record_kind,root_job_id,state,snapshot_owner_id,input_hash,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END,attempt_no,dispatch_token,worker_instance_id,send_authorized_at IS NOT NULL,call_count,max_attempts,priority_no,payload_json IS NULL FROM jobs WHERE id=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBytes(6),r.getString(7),r.getObject(8,Integer.class),r.getObject(9,Long.class),r.getString(10),r.getBoolean(11),r.getObject(12,Integer.class),r.getObject(13,Integer.class),r.getObject(14,Integer.class),r.getBoolean(15)},attemptId);
        if(rows.isEmpty())return null;if(rows.size()!=1)throw unavailable();var attempt=rows.getFirst();
        if(!attemptId.equals(attempt[0])||!"ai_attempt".equals(attempt[1])||!rootId.equals(attempt[2]))return null;
        if(!rootId.equals(attempt[4])||!Arrays.equals(HexFormat.of().parseHex(reference.hash()),(byte[])attempt[5])||attempt[6]==null||attempt[7]==null||attempt[8]==null||attempt[9]==null||!Boolean.TRUE.equals(attempt[10])||!Integer.valueOf(0).equals(attempt[11])||attempt[12]!=null||!Integer.valueOf(0).equals(attempt[13])||!Boolean.TRUE.equals(attempt[14]))throw unavailable();
        int attemptNo=(int)attempt[7];long token=(long)attempt[8];if(attemptNo<1||attemptNo>root.callCount()||token<1||token>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();
        String state=WireValues.id((String)attempt[3],false);WireValues.id((String)attempt[9],false);
        var claims=sql.query("SELECT target_key_hash,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE record_kind='disposition' AND target_type='worker_claim' AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.root_id'))=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.attempt_id'))=? AND JSON_UNQUOTE(JSON_EXTRACT(target_key_json,'$.dispatch_token'))=? LIMIT 2",(r,n)->new Object[]{r.getBytes(1),r.getObject(2,Long.class),r.getObject(3,Long.class)},rootId,attemptId,Long.toString(token));
        if(claims.size()!=1)throw unavailable();var claimHeader=claims.getFirst();if(claimHeader[0]==null||((byte[])claimHeader[0]).length!=32||claimHeader[1]==null||(long)claimHeader[1]<1||(long)claimHeader[1]>16384||claimHeader[2]==null||(long)claimHeader[2]<1||(long)claimHeader[2]>16384)throw unavailable();
        String raw=worker.claimReceipt(HexFormat.of().formatHex((byte[])claimHeader[0]));if(raw==null)throw unavailable();var claim=CanonicalJson.parse(raw);
        if(!attempt[9].equals(WireValues.id(claim.get("owner_epoch"),false)))throw unavailable();
        var identity=new JdbcResultState.Identity(rootId,attemptId,Long.toString(token),WireValues.nonnegativeInteger(claim.get("slot_revision")),token,WireValues.utcMicroseconds(claim.get("lease_until")),(String)attempt[6],reference.hash());
        claim=results.claim(identity);long originalRevision=WireValues.nonnegativeInteger(claim.get("root_revision"));if(originalRevision>root.revision())throw unavailable();
        var keys=sql.query("SELECT CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash FROM jobs WHERE id=? AND record_kind='logical_task'",(r,n)->new Object[]{r.getString(1),r.getBytes(2)},rootId);
        if(keys.size()!=1||keys.getFirst()[0]==null||keys.getFirst()[1]==null||!JdbcResultState.sha((String)keys.getFirst()[0]).equals(HexFormat.of().formatHex((byte[])keys.getFirst()[1])))throw unavailable();
        String input=inputs.read(rootId,reference.hash());if(input.getBytes(StandardCharsets.UTF_8).length>4*1024*1024||!keys.getFirst()[0].equals(WireValues.text(CanonicalJson.parse(input).get("logical_key"))))throw unavailable();
        return new Frame(root,reference.generation(),input,originalRevision,attemptNo,state,identity);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("original external attempt associations unavailable");}
}
