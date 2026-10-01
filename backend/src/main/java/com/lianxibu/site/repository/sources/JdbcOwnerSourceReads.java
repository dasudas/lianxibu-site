package com.lianxibu.site.repository.sources;
import com.lianxibu.site.domain.sources.OwnerSourceSnapshot;
import java.util.Objects;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.TransactionDefinition;import org.springframework.transaction.support.TransactionTemplate;
/** Current typed columns only. Does not select legacy config_json or probe_json. */
public final class JdbcOwnerSourceReads implements OwnerSourceSnapshot.Store {
 private final JdbcTemplate jdbc;private final TransactionTemplate reads;
 public JdbcOwnerSourceReads(DataSource source,PlatformTransactionManager tx){jdbc=new JdbcTemplate(Objects.requireNonNull(source));reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 @Override public OwnerSourceSnapshot snapshot(String id){return reads.execute(status->{var rows=jdbc.query("SELECT id,source_kind,name,revision,config_revision,activation_stage,category,draft_config_json,effective_config_json,config_valid_until FROM sources WHERE id=?",(rs,n)->{var until=rs.getTimestamp("config_valid_until");return new OwnerSourceSnapshot(rs.getString("id"),rs.getString("source_kind"),rs.getString("name"),rs.getObject("revision",Long.class),rs.getObject("config_revision",Long.class),rs.getString("activation_stage"),rs.getString("category"),rs.getString("draft_config_json"),rs.getString("effective_config_json"),until==null?null:until.toInstant());},id);return rows.isEmpty()?null:rows.getFirst();});}
}
