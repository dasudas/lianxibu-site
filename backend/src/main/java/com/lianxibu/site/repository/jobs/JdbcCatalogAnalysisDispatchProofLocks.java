package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Holds native original INPUT/request/receipt rows without loading any wide JSON; every held attempt is size-checked before a later request read. */
public final class JdbcCatalogAnalysisDispatchProofLocks {
    private final JdbcTemplate jdbc;
    public JdbcCatalogAnalysisDispatchProofLocks(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public void lock(String root,String intent){
        JdbcFrozenInputs.requireTx();if(!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        WireValues.id(root,false);WireValues.id(intent,false);
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root);
        byte[] hash=JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(target));
        String prepare=JdbcFrozenInputs.stableId("prepare-action",JdbcCatalogAnalysisPreparedInputs.action(root));
        var rows=jdbc.query("SELECT id,CASE WHEN record_kind<>'ai_attempt' OR (OCTET_LENGTH(request_json) BETWEEN 1 AND 1048576 AND (payload_json IS NULL OR OCTET_LENGTH(payload_json)<=65536)) THEN 1 ELSE 0 END FROM jobs WHERE id IN (?,?,?) OR owner_job_id=? OR root_job_id=? OR snapshot_owner_id=? OR target_key_hash=? ORDER BY id LIMIT 16385 FOR UPDATE",(r,n)->{if(!r.getBoolean(2))throw invalid();return r.getString(1);},root,intent,prepare,root,root,root,hash);
        if(rows.size()>16384)throw new GenerationFlowCapacity();
        if(!rows.contains(root)||!rows.contains(intent)||!rows.contains(prepare)||new HashSet<>(rows).size()!=rows.size())throw invalid();
    }
    public static final class GenerationFlowCapacity extends IllegalStateException{public GenerationFlowCapacity(){super("original catalog analysis dispatch proof capacity unavailable");}}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog analysis dispatch proof locks unavailable");}
}
