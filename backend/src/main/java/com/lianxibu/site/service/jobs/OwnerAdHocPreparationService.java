package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.JdbcOwnerAdHocCreation.Accepted;
import com.lianxibu.site.service.jobs.GenerationFlowService.Frozen;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Build before the short writer; fresh original receipt in every writer. */
public final class OwnerAdHocPreparationService {
 private final DataSource source;private final JdbcOwnerAdHocCreation accepted;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;private final BooleanSupplier allowed;private final OwnerAdHocInputBuilder builder=new OwnerAdHocInputBuilder();
 public OwnerAdHocPreparationService(DataSource source,JdbcOwnerAdHocCreation accepted,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed){this.source=Objects.requireNonNull(source);this.accepted=Objects.requireNonNull(accepted);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
 public Frozen prepare(String action){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
  Accepted original=reads.execute(s->accepted.original(action));if(original==null)throw unavailable();
  var plan=builder.build(original);var command=original.command();var store=new GenerationJdbcStore(source,clock,command.actor(),command.rootId(),plan.command());String key="owner-ad-hoc-prepare:"+command.intentId();
  return writes.execute(new SharedWriteExecutor.ActionContext(key,command.rootId(),plan.frozen().inputHash()),ignored->{var again=accepted.original(action);if(again==null||!again.json().equals(original.json()))throw unavailable();return store.committed(key);},context->{
   if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();accepted.verify(original);
   var states=new JdbcTemplate(source).query("SELECT state,revision FROM jobs WHERE id=? AND record_kind='generation_intent' AND task_type='ad_hoc_ai' LIMIT 2 FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getObject(2,Long.class)},command.intentId());
   if(states.size()!=1||!"waiting_input".equals(states.getFirst()[0])||!Long.valueOf(0).equals(states.getFirst()[1]))throw new JdbcOwnerAdHocCreation.Rejected("revision_conflict");
   store.save(plan.frozen(),context.actionKey());return Objects.requireNonNull(store.committed(context.actionKey()));
  });
 }
 private static IllegalStateException unavailable(){return new IllegalStateException("original temporary preparation unavailable");}
}
