package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Bounded immutable result locators. Returned version keys are not completion or publication proof. */
public final class JdbcCatalogAnalysisResultVersions {
    private final JdbcTemplate jdbc;
    public JdbcCatalogAnalysisResultVersions(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public List<ContentVersionKey> read(String root){return read(root,null,null);}
    /** Report consumer also binds the actual original successful request and envelope hashes. */
    public List<ContentVersionKey> read(String root,String expectedInput,String expectedResult){
        if((expectedInput==null)!=(expectedResult==null)||expectedInput!=null&&(!expectedInput.matches("[0-9a-f]{64}")||!expectedResult.matches("[0-9a-f]{64}")))throw invalid();
        readonly();WireValues.id(root,false);
        var attempts=jdbc.query("SELECT id,attempt_no,snapshot_owner_id,LOWER(HEX(input_hash)),record_identity_hash,call_count,max_attempts,priority_no FROM jobs WHERE record_kind='ai_attempt' AND root_job_id=? ORDER BY attempt_no LIMIT 4",(r,n)->new Object[]{r.getString(1),r.getObject(2,Long.class),r.getString(3),r.getString(4),r.getBytes(5),r.getObject(6,Integer.class),r.getObject(7,Integer.class),r.getObject(8,Integer.class)},root);
        if(attempts.size()>3)throw invalid();var found=new LinkedHashSet<ContentVersionKey>();long next=1;
        for(var attempt:attempts){
            String id=(String)attempt[0];WireValues.id(id,false);String hash=(String)attempt[3];
            if(!Long.valueOf(next).equals(attempt[1])||!root.equals(attempt[2])||hash==null||!hash.matches("[0-9a-f]{64}")||!Integer.valueOf(0).equals(attempt[5])||attempt[6]!=null||!Integer.valueOf(0).equals(attempt[7]))throw invalid();
            var identity=JsonMapper.shared().createObjectNode().put("record_kind","ai_attempt").put("natural_key",root+"\u0000"+next++);
            if(!Arrays.equals((byte[])attempt[4],HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,identity))))throw invalid();
            var target=JsonMapper.shared().createObjectNode().put("root_id",root).put("attempt_id",id);String targetJson=JdbcFrozenInputs.canonical(target),targetHash=JdbcResultState.sha(targetJson);
            var receipts=jdbc.query("SELECT id,state,revision,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,call_count,max_attempts,priority_no,operator_id,reason,action_key_hash FROM jobs WHERE record_kind='disposition' AND target_type='result_application' AND target_key_hash=UNHEX(?) ORDER BY id LIMIT 9",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,Long.class),r.getString(4),r.getBytes(5),r.getString(6),r.getBytes(7),r.getObject(8,Integer.class),r.getObject(9,Integer.class),r.getObject(10,Integer.class),r.getString(11),r.getString(12),r.getBytes(13)},targetHash);
            if(receipts.size()>8)throw invalid();
            for(var row:receipts){
                WireValues.id((String)row[0],false);
                if(!"applied".equals(row[1])||!Long.valueOf(0).equals(row[2])||row[3]==null||row[5]==null||!Integer.valueOf(0).equals(row[7])||row[8]!=null||!Integer.valueOf(0).equals(row[9])||row[10]==null||((String)row[10]).isBlank()||row[12]==null||((byte[])row[12]).length!=32)throw invalid();
                if(!targetJson.equals(JdbcFrozenInputs.canonical(CanonicalJson.parse((String)row[3])))||!Arrays.equals((byte[])row[4],HexFormat.of().parseHex(targetHash)))throw invalid();
                var payload=CanonicalJson.parse((String)row[5]);
                if(!Arrays.equals((byte[])row[6],HexFormat.of().parseHex(JdbcResultState.sha(JdbcFrozenInputs.canonical(payload)))))throw invalid();
                var required=Set.of("schema_version","action_hash","input_hash","status","result_hash","content_id","content_revision");var allowed=new HashSet<>(required);allowed.add("content_version");WireValues.object(payload,allowed,required);
                if(!"result-application-receipt-v1".equals(WireValues.text(payload.get("schema_version")))||!hash.equals(WireValues.sha256(payload.get("input_hash"))))throw invalid();
                WireValues.sha256(payload.get("action_hash"));WireValues.sha256(payload.get("result_hash"));String status=WireValues.text(payload.get("status"));
                if(!Set.of("APPLIED","CONTENT_REVISION_CONFLICT","SUPERSEDED","INVALID_EVIDENCE","DISCARDED").contains(status)||!status.equals(row[11]))throw invalid();
                if(!"APPLIED".equals(status)){if(payload.has("content_version"))throw invalid();continue;}
                if(expectedInput!=null){
                    if(!expectedInput.equals(hash)||!expectedResult.equals(WireValues.sha256(payload.get("result_hash"))))throw invalid();
                    originalSuccessfulRequest(id,root,hash,next-1);
                }
                if(!payload.has("content_version"))throw invalid();var version=ContentVersionKey.fromJson(payload.get("content_version"));
                long revision=WireValues.nonnegativeInteger(payload.get("content_revision"));
                if(!root.equals(version.inputJobId())||!version.contentId().equals(WireValues.id(payload.get("content_id"),false))||revision<version.revision())throw invalid();
                found.add(version);
            }
        }
        if(found.size()>1)throw invalid();return List.copyOf(found);
    }
    private void originalSuccessfulRequest(String attempt,String root,String hash,long number){
        if(!JdbcFrozenInputs.stableId("attempt",root+":"+number).equals(attempt))throw invalid();
        var values=jdbc.query("SELECT state,send_authorized_at IS NOT NULL,slot_released_at IS NOT NULL,release_reason,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN request_json ELSE NULL END FROM jobs WHERE id=? AND record_kind='ai_attempt' AND root_job_id=? LIMIT 2",(r,n)->{
            if(!"succeeded".equals(r.getString(1))||!r.getBoolean(2)||!r.getBoolean(3)||!"result_complete".equals(r.getString(4))||r.getString(5)==null)throw invalid();
            var request=CanonicalJson.parse(r.getString(5));if(!hash.equals(WireValues.sha256(request.get("input_hash"))))throw invalid();return true;
        },attempt,root);if(values.size()!=1||!values.getFirst())throw invalid();
    }
    private static void readonly(){var isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(isolation==null?-1:isolation))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog result version locator unavailable");}
}
