package com.lianxibu.site.repository.articles;
import com.lianxibu.site.domain.protocol.WireValues;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact selected members, not a claim of complete historical source coverage. */
public final class JdbcSourceMemberEvidence {
    public record Row(String id,String runId,String runIdentity,String sourceId,String runSource,long revision,String category,Instant createdAt,
                      String terminal,String state,String projection,boolean valid,String payload,byte[] hash,String activation,String delivery,
                      Instant finishedAt,long configVersion,String config) { }
    private final JdbcTemplate jdbc;
    public JdbcSourceMemberEvidence(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Row lock(String id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("member evidence requires shared writer transaction");
        return read(id,true);
    }
    /** Committed observation only; reservation still needs the actual writer lock. */
    public Row observed(String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("member observation requires read-only transaction");
        WireValues.id(id,false);return read(id,false);
    }
    private Row read(String id,boolean lock){
        var rows=jdbc.query("SELECT i.id,i.run_id,r.id,i.source_id,r.source_id,i.revision,i.category,i.created_at,i.terminal_state,i.state,i.projection_decision,i.effective_valid,CASE WHEN OCTET_LENGTH(i.evidence_json) BETWEEN 1 AND 262144 THEN i.evidence_json ELSE NULL END,i.evidence_hash,r.activation_stage,r.delivery_state,r.finished_at,r.config_version,CASE WHEN OCTET_LENGTH(r.config_snapshot_json) BETWEEN 1 AND 65536 THEN r.config_snapshot_json ELSE NULL END,OCTET_LENGTH(i.evidence_json),OCTET_LENGTH(r.config_snapshot_json) FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->{
                long evidenceBytes=rs.getLong(20);if(rs.wasNull()||evidenceBytes<1||evidenceBytes>256*1024)throw new IllegalStateException("member evidence outside storage limit");
                long configBytes=rs.getLong(21);if(rs.wasNull()||configBytes<1||configBytes>64*1024)throw new IllegalStateException("member configuration outside storage limit");
                return new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant(),rs.getString(9),rs.getString(10),rs.getString(11),rs.getInt(12)==1,rs.getString(13),rs.getBytes(14),rs.getString(15),rs.getString(16),rs.getTimestamp(17)==null?null:rs.getTimestamp(17).toInstant(),rs.getLong(18),rs.getString(19));},id);
        return rows.size()==1?rows.getFirst():null;
    }

    /** Sixteen rows bound stored payload bytes to 4MiB; configuration and heap overhead are additional. */
    public Map<String,Row> lockBatch(Set<String> ids){return exactRows(ids,true);}
    public Map<String,Row> observedBatch(Set<String> ids){return exactRows(ids,false);}
    private Map<String,Row> exactRows(Set<String> ids,boolean lock){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()==lock)throw new IllegalStateException("native member batch transaction required");
        var ordered=exactBatch(ids,16);var rows=jdbc.query("SELECT i.id,i.run_id,r.id,i.source_id,r.source_id,i.revision,i.category,i.created_at,i.terminal_state,i.state,i.projection_decision,i.effective_valid,CASE WHEN OCTET_LENGTH(i.evidence_json) BETWEEN 1 AND 262144 THEN i.evidence_json ELSE NULL END,i.evidence_hash,r.activation_stage,r.delivery_state,r.finished_at,r.config_version,CASE WHEN OCTET_LENGTH(r.config_snapshot_json) BETWEEN 1 AND 65536 THEN r.config_snapshot_json ELSE NULL END,OCTET_LENGTH(i.evidence_json),OCTET_LENGTH(r.config_snapshot_json) FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id IN ("+marks(ordered.size())+") ORDER BY i.id"+(lock?" FOR UPDATE":""),(rs,n)->{
                long evidenceBytes=rs.getLong(20);if(rs.wasNull()||evidenceBytes<1||evidenceBytes>256*1024)throw new IllegalStateException("member evidence outside storage limit");
                long configBytes=rs.getLong(21);if(rs.wasNull()||configBytes<1||configBytes>64*1024)throw new IllegalStateException("member configuration outside storage limit");
                return new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant(),rs.getString(9),rs.getString(10),rs.getString(11),rs.getInt(12)==1,rs.getString(13),rs.getBytes(14),rs.getString(15),rs.getString(16),rs.getTimestamp(17)==null?null:rs.getTimestamp(17).toInstant(),rs.getLong(18),rs.getString(19));},ordered.toArray());var result=new LinkedHashMap<String,Row>();
        for(var row:rows)if(!ids.contains(row.id())||result.putIfAbsent(row.id(),row)!=null)throw new IllegalStateException("invalid exact member batch");return Collections.unmodifiableMap(result);
    }

    private static List<String> exactBatch(Set<String> ids,int maximum){
        if(ids==null||ids.isEmpty()||ids.size()>maximum)throw new IllegalArgumentException("invalid exact native batch");
        var ordered=ids.stream().sorted().toList();for(var id:ordered)com.lianxibu.site.domain.protocol.WireValues.id(id,false);return ordered;
    }
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}

}
