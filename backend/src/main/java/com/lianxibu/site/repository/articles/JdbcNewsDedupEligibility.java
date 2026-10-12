package com.lianxibu.site.repository.articles;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact original transport identity and current P1 qualification metadata. */
public final class JdbcNewsDedupEligibility {
    public record Row(String id,String runId,String sourceId,String sourceItemId,String category,String entityId,
                      String terminal,String state,String projection,boolean valid,long revision,LocalDate targetDate,
                      Instant created,String actualRun,String runSource,LocalDate day,String activation,String delivery,
                      Instant finished,byte[] payloadHash) { }
    private final JdbcTemplate jdbc;
    public JdbcNewsDedupEligibility(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Row lock(String id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("news qualification requires shared writer transaction");
        var rows=jdbc.query("SELECT i.id,i.run_id,i.source_id,i.source_item_id,i.category,i.entity_id,i.terminal_state,i.state,i.projection_decision,i.effective_valid,i.revision,i.target_business_date,i.created_at,r.id,r.source_id,r.business_date,r.activation_stage,r.delivery_state,r.finished_at,i.payload_hash"
                +" FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id=? FOR UPDATE",
                (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getInt(10)==1,r.getLong(11),r.getDate(12)==null?null:r.getDate(12).toLocalDate(),r.getTimestamp(13)==null?null:r.getTimestamp(13).toInstant(),r.getString(14),r.getString(15),r.getDate(16)==null?null:r.getDate(16).toLocalDate(),r.getString(17),r.getString(18),r.getTimestamp(19)==null?null:r.getTimestamp(19).toInstant(),r.getBytes(20)),id);
        return rows.size()==1?rows.getFirst():null;
    }

    /** Exact bounded current rows under the same native writer locks. */
    public Map<String,Row> lockBatch(Set<String> ids){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("news qualification requires shared writer transaction");
        var ordered=exactBatch(ids,100);var rows=jdbc.query("SELECT i.id,i.run_id,i.source_id,i.source_item_id,i.category,i.entity_id,i.terminal_state,i.state,i.projection_decision,i.effective_valid,i.revision,i.target_business_date,i.created_at,r.id,r.source_id,r.business_date,r.activation_stage,r.delivery_state,r.finished_at,i.payload_hash"
                +" FROM source_run_items i JOIN source_runs r ON r.id=i.run_id WHERE i.id IN ("+marks(ordered.size())+") ORDER BY i.id FOR UPDATE",(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getInt(10)==1,r.getLong(11),r.getDate(12)==null?null:r.getDate(12).toLocalDate(),r.getTimestamp(13)==null?null:r.getTimestamp(13).toInstant(),r.getString(14),r.getString(15),r.getDate(16)==null?null:r.getDate(16).toLocalDate(),r.getString(17),r.getString(18),r.getTimestamp(19)==null?null:r.getTimestamp(19).toInstant(),r.getBytes(20)),ordered.toArray());var result=new LinkedHashMap<String,Row>();
        for(var row:rows)if(!ids.contains(row.id())||result.putIfAbsent(row.id(),row)!=null)throw new IllegalStateException("invalid exact qualification batch");return Collections.unmodifiableMap(result);
    }

    private static List<String> exactBatch(Set<String> ids,int maximum){
        if(ids==null||ids.isEmpty()||ids.size()>maximum)throw new IllegalArgumentException("invalid exact native batch");
        var ordered=ids.stream().sorted().toList();for(var id:ordered)com.lianxibu.site.domain.protocol.WireValues.id(id,false);return ordered;
    }
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}

}
