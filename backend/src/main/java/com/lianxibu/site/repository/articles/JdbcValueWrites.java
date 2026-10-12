package com.lianxibu.site.repository.articles;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Value decisions update only their exact accepted source member. */
public final class JdbcValueWrites {
    public record Member(String id,long revision,String category,String entityId,String terminalState,String state,String projectionDecision,boolean valid) { }
    private final JdbcTemplate jdbc;
    public JdbcValueWrites(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Member lock(String id) {
        requireTx();var rows=jdbc.query("SELECT id,revision,category,entity_id,terminal_state,state,projection_decision,effective_valid FROM source_run_items WHERE id=? FOR UPDATE",
            (rs,n)->new Member(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8)==1),id);
        return rows.size()==1?rows.getFirst():null;
    }
    public void save(Member row,boolean highValue,String json) {
        requireTx();if(jdbc.update("UPDATE source_run_items SET high_value=?,value_json=?,revision=revision+1 WHERE id=? AND revision=?",highValue?1:0,json,row.id(),row.revision())!=1)throw new IllegalStateException("value member revision conflict");
    }
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("value writes require shared transaction");}
}
