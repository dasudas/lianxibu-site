package com.lianxibu.site.repository.articles;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the analysis state, generation and ordinary entity revision are changed. */
public final class JdbcEntityAnalysisState {
    public record Row(String id,String category,long revision,long generation,String state,boolean legacy) { }
    private final JdbcTemplate jdbc;
    public JdbcEntityAnalysisState(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Row lock(String id){requireTx();var rows=jdbc.query("SELECT id,category,revision,analysis_generation,analysis_state,legacy_flag FROM analyzed_entities WHERE id=? FOR UPDATE",(r,n)->new Row(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getString(5),r.getObject(6)==null||r.getInt(6)!=0),id);return rows.size()==1?rows.getFirst():null;}
    public boolean change(Row expected,long generation,String state){requireTx();return jdbc.update("UPDATE analyzed_entities SET analysis_generation=?,analysis_state=?,revision=revision+1 WHERE id=? AND category=? AND revision=? AND analysis_generation=? AND analysis_state=? AND legacy_flag=0",generation,state,expected.id(),expected.category(),expected.revision(),expected.generation(),expected.state())==1;}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("entity analysis requires shared writer transaction");}
}
