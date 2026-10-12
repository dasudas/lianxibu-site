package com.lianxibu.site.service.content;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Metadata-only initial report identity guard. Never reads current content bodies. */
public final class ReportPreparationContentBaselineService {
 private final DataSource source;private final JdbcTemplate jdbc;
 public ReportPreparationContentBaselineService(DataSource source){this.source=Objects.requireNonNull(source);jdbc=new JdbcTemplate(source);}
 public void requireAbsent(String id){WireValues.id(id,false);if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
  ||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.hasResource(source))throw invalid();
  Long count=jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE id=?",Long.class,id);if(count==null||count!=0)throw invalid();
 }
 private static IllegalStateException invalid(){return new IllegalStateException("new report content identity unavailable");}
}
