package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Exact permanent preparation locators, bounded before original JSON is loaded. */
public final class JdbcCatalogAnalysisPreparedInputs {
    public static final String ACTOR="catalog-analysis-coordinator";
    private final JdbcTemplate jdbc;
    public JdbcCatalogAnalysisPreparedInputs(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public String read(String root,String action){transaction();WireValues.id(root,false);if(!action(root).equals(action))throw invalid();byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action)));String id=JdbcFrozenInputs.stableId("prepare-action",action);String target=JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root));
        var rows=jdbc.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 4096 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 65536 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
            String key=r.getString(11),body=r.getString(15);if(key==null||body==null)throw invalid();key=JdbcFrozenInputs.canonical(CanonicalJson.parse(key));body=JdbcFrozenInputs.canonical(CanonicalJson.parse(body));if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||r.getLong(4)!=0||r.getLong(5)!=0||r.getObject(6)!=null||r.getLong(7)!=0||!Arrays.equals(identity,r.getBytes(8))||!Arrays.equals(JdbcGenerationLineage.hash(action),r.getBytes(9))||!"generation_prepare".equals(r.getString(10))||!target.equals(key)||!Arrays.equals(JdbcGenerationLineage.hash(key),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!"Frozen generation input and prepared request".equals(r.getString(14))||!Arrays.equals(JdbcGenerationLineage.hash(body),r.getBytes(16))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(17))||!"disposition".equals(r.getString(18))||!id.equals(r.getString(19)))throw invalid();return body;
        },id,JdbcGenerationLineage.hash(action),identity);if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    /** Guards the exact original first attempt before the existing generic reader loads its request. */
    public void originalAttemptBounded(String root){transaction();String id=JdbcFrozenInputs.stableId("attempt",root+":1");var rows=jdbc.query("SELECT id,record_kind,root_job_id,attempt_no,snapshot_owner_id,CASE WHEN OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 THEN 1 ELSE 0 END,CASE WHEN payload_json IS NULL OR OCTET_LENGTH(payload_json)<=65536 THEN 1 ELSE 0 END FROM jobs WHERE id=? OR (root_job_id=? AND attempt_no=1) LIMIT 2",(r,n)->r.getString(1)!=null&&id.equals(r.getString(1))&&"ai_attempt".equals(r.getString(2))&&root.equals(r.getString(3))&&r.getLong(4)==1&&root.equals(r.getString(5))&&r.getBoolean(6)&&r.getBoolean(7),id,root);if(rows.size()!=1||!rows.getFirst())throw invalid();}
    public static String root(String intent){WireValues.id(intent,false);return JdbcFrozenInputs.stableId("catalog-analysis-root",intent);}
    public static String action(String root){WireValues.id(root,false);return "catalog-analysis-prepare:"+root;}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native original catalog analysis preparation unavailable");}
}
