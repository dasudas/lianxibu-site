package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One original catalog preparation or committed final observation per step. */
public final class CatalogSourceParentStepService {
 public enum Status {PREPARED,FINALIZED,PENDING,GATED}
 public record Outcome(Status status,String intentId){public Outcome{Objects.requireNonNull(status);WireValues.id(intentId,false);}@Override public String toString(){return "CatalogSourceParentOutcome[redacted]";}}
 private static final Set<String> ACTIVE=Set.of("waiting_input","prepared","queued","running"),TERMINAL=Set.of("succeeded","no_input");
 private record Snapshot(CatalogSourceIntentOriginService.Route route,JdbcFrozenSourcePlans.Owner owner,SourcePartitionPlanner.Plan plan,FrozenSourceStagePlan.Reference reference) { }
 private final CatalogSourceIntentOriginService origins;private final JdbcFrozenSourcePlans parents;private final FrozenSourceStagePlanService plans;private final SourceStagePreparationService parts;private final CatalogSourcePlanPreparationCoordinator preparation;private final SourceStageResolutionService resolutions;private final SourceDedupPartialSealService partials;private final CatalogSourceFinalizationService finals;private final CatalogSourceFinalAvailabilityService observer;private final TransactionTemplate reads;private final Clock clock;private final String category,type;
 public CatalogSourceParentStepService(CatalogSourceIntentOriginService origins,JdbcFrozenSourcePlans parents,FrozenSourceStagePlanService plans,SourceStagePreparationService parts,CatalogSourcePlanPreparationCoordinator preparation,SourceStageResolutionService resolutions,SourceDedupPartialSealService partials,CatalogSourceFinalizationService finals,CatalogSourceFinalAvailabilityService observer,PlatformTransactionManager tx,Clock clock,String category){
  this.origins=Objects.requireNonNull(origins);this.parents=Objects.requireNonNull(parents);this.plans=Objects.requireNonNull(plans);this.parts=Objects.requireNonNull(parts);this.preparation=Objects.requireNonNull(preparation);this.resolutions=Objects.requireNonNull(resolutions);this.partials=Objects.requireNonNull(partials);this.finals=Objects.requireNonNull(finals);this.observer=Objects.requireNonNull(observer);this.clock=Objects.requireNonNull(clock);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};type=category+"_dedup";reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Outcome step(JdbcSourceIntentScan.Candidate candidate){
  try{
   if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(candidate);var original=Objects.requireNonNull(reads.execute(s->snapshot(candidate)));String intent=original.route().intentId(),run=original.route().runId();
   if(original.plan()==null){if(!ACTIVE.contains(original.owner().state()))throw invalid();return prepared(original,preparation.prepareDedup(intent,run,0));}
   var progress=Objects.requireNonNull(reads.execute(s->parts.progress(intent)));if(!original.plan().equals(progress.plan()))throw invalid();
   if(!progress.complete()){if(!ACTIVE.contains(original.owner().state()))throw invalid();return prepared(original,preparation.prepareDedup(intent,run,progress.nextPart()));}
   var committed=reads.execute(s->finals.readCommitted(original.reference()));
   if(committed!=null){Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);var available=reads.execute(s->observer.availableAt(committed,now));return available!=null?outcome(Status.FINALIZED,intent):observe(original);}
   if(!ACTIVE.contains(original.owner().state()))throw invalid();var resolved=Objects.requireNonNull(reads.execute(s->resolutions.read(original.reference())));if(!original.reference().equals(resolved.reference())||!original.plan().equals(resolved.plan()))throw invalid();
   if(resolved.settled()){
    if(resolved.hasFailures()){var sealed=partials.seal(original.reference());if(sealed==null)return outcome(Status.PENDING,intent);if(!original.reference().equals(sealed.proof().planRef())||!SourceDedupStageResult.createResolved(resolved).json().equals(sealed.proof().json()))throw invalid();}
    return finals.finish(original.reference())==null?outcome(Status.PENDING,intent):observe(original);
   }
   return outcome(Status.PENDING,intent);
  }catch(GenerationFlowService.CapacityGuarded guarded){return outcome(Status.GATED,Objects.requireNonNull(candidate).id());}catch(RuntimeException rejected){throw invalid();}
 }
 private Outcome observe(Snapshot original){if(observer.capture(original.reference())==null)throw invalid();return outcome(Status.FINALIZED,original.route().intentId());}
 private Outcome prepared(Snapshot original,CatalogSourcePlanPreparationCoordinator.Prepared prepared){
  if(prepared==null)return outcome(Status.PENDING,original.route().intentId());var plan=prepared.plan();if(!original.route().intentId().equals(plan.reference().ownerId())||!original.route().runId().equals(plan.reference().runId())||!original.owner().key().equals(plan.logicalKey())||original.owner().generation()!=plan.generation()||!original.owner().businessDate().equals(plan.scope().businessDate())||!type.equals(plan.stage().name().toLowerCase(Locale.ROOT)))throw invalid();return outcome(Status.PREPARED,original.route().intentId());
 }
 private Snapshot snapshot(JdbcSourceIntentScan.Candidate candidate){
  var route=origins.read(candidate);var owner=parents.immutableOwner(route.intentId());if(!category.equals(route.category())||!type.equals(route.type())||!candidate.id().equals(owner.id())||!"generation_intent".equals(owner.kind())||!candidate.key().equals(owner.key())||!Arrays.equals(candidate.keyHash(),owner.keyHash())||candidate.generation()!=owner.generation()||!type.equals(owner.type())||!candidate.day().equals(owner.businessDate())||!(ACTIVE.contains(owner.state())||TERMINAL.contains(owner.state())))throw invalid();
  var receipt=parents.immutableReceipt(owner.id());if(receipt==null){if(!parents.immutableParts(owner.id()).isEmpty())throw invalid();return new Snapshot(route,owner,null,null);}var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(receipt.payloadJson()).get("plan_ref")));var plan=plans.readImmutable(reference);if(!owner.id().equals(reference.ownerId())||!route.runId().equals(plan.reference().runId())||!type.equals(plan.stage().name().toLowerCase(Locale.ROOT)))throw invalid();return new Snapshot(route,owner,plan,reference);
 }
 private static Outcome outcome(Status status,String intent){return new Outcome(status,intent);}
 private static IllegalStateException invalid(){return new IllegalStateException("original catalog source parent step unavailable");}
}
