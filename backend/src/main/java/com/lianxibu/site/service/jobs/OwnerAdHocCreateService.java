package com.lianxibu.site.service.jobs;
import com.lianxibu.site.service.jobs.JdbcOwnerAdHocCreation;
import com.lianxibu.site.service.jobs.JdbcOwnerAdHocCreation.Accepted;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** An owner command accepts native original input; no model call. */
public final class OwnerAdHocCreateService {
 private final SharedWriteExecutor writes;private final JdbcOwnerAdHocCreation store;private final GenerationFlowService.SettingsSnapshotSource settings;private final BooleanSupplier allowed;private final Clock clock;
 public OwnerAdHocCreateService(SharedWriteExecutor writes,JdbcOwnerAdHocCreation store,GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier allowed,Clock clock){this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.settings=Objects.requireNonNull(settings);this.allowed=Objects.requireNonNull(allowed);this.clock=Objects.requireNonNull(clock);}
 public Accepted create(Actor actor,String target,String prompt,String reason,String requestKey){
  var command=OwnerAdHocCreateCommand.create(actor,target,prompt,reason);String action=JdbcOwnerAdHocCreation.actionKey(requestKey);
  return writes.execute(new SharedWriteExecutor.ActionContext(action,command.intentId(),null),key->store.committed(key,command),context->{if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();return store.accept(context.actionKey(),command,Objects.requireNonNull(settings.freeze()),clock.instant().truncatedTo(ChronoUnit.MICROS));});
 }
}
