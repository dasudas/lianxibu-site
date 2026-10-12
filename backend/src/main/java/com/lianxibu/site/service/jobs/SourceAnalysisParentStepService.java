package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One bounded preparation or completion step. Failure termination and scheduling are separate. */
public final class SourceAnalysisParentStepService {
    public enum Status { PREPARED,HANDED_OFF,FINALIZED,PENDING,GATED }
    public record Outcome(Status status,String intentId){
        public Outcome {Objects.requireNonNull(status);WireValues.id(intentId,false);}
        @Override public String toString(){return "SourceAnalysisParentOutcome[redacted]";}
    }
    private static final Set<String> ACTIVE=Set.of("waiting_input","prepared","queued","running"),TERMINAL=Set.of("succeeded","no_input");
    private record Snapshot(SourceIntentOriginService.Route route,JdbcFrozenSourcePlans.Owner owner,
            SourcePartitionPlanner.Plan plan,FrozenSourceStagePlan.Reference reference){ }
    private final SourceIntentOriginService origins;private final JdbcFrozenSourcePlans parents;private final FrozenSourceStagePlanService plans;
    private final SourceStagePreparationService parts;private final SourcePlanPreparationCoordinator preparation;private final SourceStageResolutionService resolutions;
    private final SourceValueIntentService handoffs;private final SourceValueFinalizationService finals;private final SourceValueFinalAvailabilityService observer;
    private final TransactionTemplate reads;private final Clock clock;
    private final SourceDedupPartialSealService dedupPartials;private final SourceValuePartialSealService valuePartials;
    public SourceAnalysisParentStepService(SourceIntentOriginService origins,JdbcFrozenSourcePlans parents,FrozenSourceStagePlanService plans,
            SourceStagePreparationService parts,SourcePlanPreparationCoordinator preparation,SourceStageResolutionService resolutions,SourceValueIntentService handoffs,
            SourceValueFinalizationService finals,SourceValueFinalAvailabilityService observer,PlatformTransactionManager tx,Clock clock){
        this(origins,parents,plans,parts,preparation,resolutions,handoffs,finals,observer,tx,clock,null,null);
    }
    public SourceAnalysisParentStepService(SourceIntentOriginService origins,JdbcFrozenSourcePlans parents,FrozenSourceStagePlanService plans,
            SourceStagePreparationService parts,SourcePlanPreparationCoordinator preparation,SourceStageResolutionService resolutions,SourceValueIntentService handoffs,
            SourceValueFinalizationService finals,SourceValueFinalAvailabilityService observer,PlatformTransactionManager tx,Clock clock,
            SourceDedupPartialSealService dedupPartials,SourceValuePartialSealService valuePartials){
        this.origins=Objects.requireNonNull(origins);this.parents=Objects.requireNonNull(parents);this.plans=Objects.requireNonNull(plans);
        this.parts=Objects.requireNonNull(parts);this.preparation=Objects.requireNonNull(preparation);this.handoffs=Objects.requireNonNull(handoffs);
        this.resolutions=Objects.requireNonNull(resolutions);
        this.finals=Objects.requireNonNull(finals);this.observer=Objects.requireNonNull(observer);this.clock=Objects.requireNonNull(clock);
        this.dedupPartials=dedupPartials;this.valuePartials=valuePartials;
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Outcome step(JdbcSourceIntentScan.Candidate candidate){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(candidate);
            var original=reads.execute(s->snapshot(candidate));Objects.requireNonNull(original);
            if("news_dedup".equals(original.route().type()))return dedup(original);
            return value(original);
        }catch(GenerationFlowService.CapacityGuarded guarded){return outcome(Status.GATED,Objects.requireNonNull(candidate).id());}
        catch(RuntimeException rejected){throw invalid();}
    }
    private Outcome dedup(Snapshot original){
        String intent=original.route().intentId(),run=original.route().runId();
        if(original.plan()==null){if(!ACTIVE.contains(original.owner().state()))throw invalid();return prepared(original,preparation.prepareDedup(intent,run,0));}
        if(ready(original)){
            var completed=handoffs.handoff(original.reference());
            if(completed!=null){if(!completed.p1Ref().equals(original.reference())||!run.equals(completed.runId()))throw invalid();return outcome(Status.HANDED_OFF,intent);}
        }
        if(!ACTIVE.contains(original.owner().state()))throw invalid();var progress=progress(original);
        return progress.complete()?outcome(Status.PENDING,intent):prepared(original,preparation.prepareDedup(intent,run,progress.nextPart()));
    }
    private Outcome value(Snapshot original){
        String intent=original.route().intentId(),run=original.route().runId();var relation=handoffs.handoff(original.route().p1Ref());
        if(relation==null)return outcome(Status.PENDING,intent);
        if(!intent.equals(relation.p2Owner())||!run.equals(relation.runId())||!original.route().p1Ref().equals(relation.p1Ref())
                ||!original.owner().key().equals(relation.p2Key())||original.owner().generation()!=relation.p2Generation()||!original.owner().businessDate().equals(relation.day()))throw invalid();
        if(original.plan()==null){if(!ACTIVE.contains(original.owner().state()))throw invalid();return prepared(original,prepareValue(original,relation,0));}
        if(!ready(original)){
            if(!ACTIVE.contains(original.owner().state()))throw invalid();var progress=progress(original);
            return progress.complete()?outcome(Status.PENDING,intent):prepared(original,prepareValue(original,relation,progress.nextPart()));
        }
        var sealed=reads.execute(s->finals.readCommitted(original.reference()));
        if(sealed!=null){
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);var available=reads.execute(s->observer.availableAt(sealed,now));
            if(available!=null)return outcome(Status.FINALIZED,intent);return observe(original);
        }
        if(!ACTIVE.contains(original.owner().state()))throw invalid();
        if(finals.finish(original.reference())!=null)return observe(original);
        var progress=progress(original);
        return progress.complete()?outcome(Status.PENDING,intent):prepared(original,prepareValue(original,relation,progress.nextPart()));
    }
    private SourcePlanPreparationCoordinator.Prepared prepareValue(Snapshot original,SourceValueIntentReceiptService.Handoff relation,long part){
        var partial=dedupPartials==null?null:reads.execute(s->dedupPartials.readCommitted(original.route().p1Ref()));
        if(partial!=null){
            if(!original.route().p1Ref().equals(partial.proof().planRef())||!relation.p1ResultHash().equals(partial.proof().hash()))throw invalid();
            return preparation.prepareValuePartial(original.route().intentId(),original.route().runId(),original.route().p1Ref(),part);
        }
        if(original.plan()!=null&&partialContext(original.plan()))throw invalid();
        return preparation.prepareValue(original.route().intentId(),original.route().runId(),original.route().p1Ref(),part);
    }
    private boolean ready(Snapshot original){
        var resolved=reads.execute(s->resolutions.read(original.reference()));
        if(resolved==null||!original.reference().equals(resolved.reference())||!original.plan().equals(resolved.plan()))throw invalid();
        if(!resolved.settled())return false;
        if(original.plan().stage()==GenerationFlowService.Stage.NEWS_DEDUP){
            if(!resolved.hasFailures())return true;if(dedupPartials==null)return false;
            var sealed=dedupPartials.seal(original.reference());if(sealed==null)return false;
            if(!original.reference().equals(sealed.proof().planRef())||!SourceDedupStageResult.createResolved(resolved).json().equals(sealed.proof().json()))throw invalid();return true;
        }
        if(!resolved.hasFailures()&&!partialContext(original.plan()))return true;if(valuePartials==null)return false;
        var sealed=valuePartials.seal(original.reference());if(sealed==null)return false;
        if(!original.reference().equals(sealed.proof().planRef())||!SourceValueStageResult.createResolved(resolved).json().equals(sealed.proof().json()))throw invalid();return true;
    }
    private static boolean partialContext(SourcePartitionPlanner.Plan plan){
        if(plan.scope()==null||plan.scope().stageContextJson()==null)return false;
        var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));
        return "source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));
    }
    private Outcome observe(Snapshot original){if(observer.capture(original.reference())==null)throw invalid();return outcome(Status.FINALIZED,original.route().intentId());}
    private SourceStagePreparationService.Progress progress(Snapshot original){
        var progress=reads.execute(s->parts.progress(original.route().intentId()));
        if(progress==null||!original.plan().equals(progress.plan()))throw invalid();return progress;
    }
    private Outcome prepared(Snapshot original,SourcePlanPreparationCoordinator.Prepared prepared){
        if(prepared==null)return outcome(Status.PENDING,original.route().intentId());var plan=prepared.plan();
        if(!plan.reference().ownerId().equals(original.route().intentId())||!plan.reference().runId().equals(original.route().runId())
                ||!plan.logicalKey().equals(original.owner().key())||plan.generation()!=original.owner().generation()
                ||!plan.scope().businessDate().equals(original.owner().businessDate())||!plan.stage().name().toLowerCase(Locale.ROOT).equals(original.route().type()))throw invalid();
        return outcome(Status.PREPARED,original.route().intentId());
    }
    private Snapshot snapshot(JdbcSourceIntentScan.Candidate candidate){
        var route=origins.read(candidate);var owner=parents.immutableOwner(route.intentId());
        if(!candidate.id().equals(owner.id())||!"generation_intent".equals(owner.kind())||!candidate.key().equals(owner.key())
                ||!Arrays.equals(candidate.keyHash(),owner.keyHash())||candidate.generation()!=owner.generation()||!candidate.type().equals(owner.type())
                ||!candidate.day().equals(owner.businessDate())||!(ACTIVE.contains(owner.state())||TERMINAL.contains(owner.state())))throw invalid();
        var receipt=parents.immutableReceipt(owner.id());
        if(receipt==null){if(!parents.immutableParts(owner.id()).isEmpty())throw invalid();return new Snapshot(route,owner,null,null);}
        var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(receipt.payloadJson()).get("plan_ref")));
        var plan=plans.readImmutable(reference);
        if(!owner.id().equals(reference.ownerId())||!route.runId().equals(plan.reference().runId())||!owner.type().equals(plan.stage().name().toLowerCase(Locale.ROOT)))throw invalid();
        return new Snapshot(route,owner,plan,reference);
    }
    private static Outcome outcome(Status status,String intent){return new Outcome(status,intent);}
    private static IllegalStateException invalid(){return new IllegalStateException("original source analysis parent step unavailable");}
}
