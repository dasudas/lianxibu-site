package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Owns permanent plan and single original fragment transactions; never sends a model request. */
public final class SourcePlanPreparationCoordinator {
    public record Prepared(SourcePartitionPlanner.Plan plan,GenerationFlowService.Frozen root){
        public Prepared {Objects.requireNonNull(plan);}
        @Override public String toString(){return "PreparedOriginalSourceFragment[redacted]";}
    }
    private static final Set<String> ACTIVE=Set.of("waiting_input","prepared","queued","running");
    private final NewsSourcePlanPreparationService dedup;private final NewsValueSourcePlanPreparationService value;private final SourceStagePreparationService parts;
    private final SourceValueIntentService handoffs;private final SourceDedupStageProofService proofs;private final JdbcFrozenSourcePlans parents;
    private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;private final BooleanSupplier allowed;private final SourceDedupPartialDependencyPermitService partialPermits;
    private final SourceDayNewsValuePermitService dayPermits;
    public SourcePlanPreparationCoordinator(NewsSourcePlanPreparationService dedup,NewsValueSourcePlanPreparationService value,SourceStagePreparationService parts,SourceValueIntentService handoffs,SourceDedupStageProofService proofs,JdbcFrozenSourcePlans parents,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed){
        this(dedup,value,parts,handoffs,proofs,parents,writes,tx,clock,allowed,null);
    }
    public SourcePlanPreparationCoordinator(NewsSourcePlanPreparationService dedup,NewsValueSourcePlanPreparationService value,SourceStagePreparationService parts,SourceValueIntentService handoffs,SourceDedupStageProofService proofs,JdbcFrozenSourcePlans parents,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed,SourceDedupPartialDependencyPermitService partialPermits){
        this(dedup,value,parts,handoffs,proofs,parents,writes,tx,clock,allowed,partialPermits,null);
    }
    public SourcePlanPreparationCoordinator(NewsSourcePlanPreparationService dedup,NewsValueSourcePlanPreparationService value,SourceStagePreparationService parts,SourceValueIntentService handoffs,SourceDedupStageProofService proofs,JdbcFrozenSourcePlans parents,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed,SourceDedupPartialDependencyPermitService partialPermits,SourceDayNewsValuePermitService dayPermits){
        this.dayPermits=dayPermits;
        this.partialPermits=partialPermits;
        this.dedup=Objects.requireNonNull(dedup);this.value=Objects.requireNonNull(value);this.parts=Objects.requireNonNull(parts);this.handoffs=Objects.requireNonNull(handoffs);this.proofs=Objects.requireNonNull(proofs);this.parents=Objects.requireNonNull(parents);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Prepared prepareDedup(String intent,String run,long part){
        try {
            identity(intent,run,part);
            var captured=writes.execute(captureContext(intent,run),key->dedup.captureCommitted(intent,run),action->{newWork();return dedup.capture(intent,run);});
            var prepared=dedup.build(captured);
            var plan=writes.execute(context(intent,run),key->dedup.committed(intent,run),action->{newWork();return dedup.commit(prepared);});return preparePart(intent,run,part,plan);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public Prepared prepareValue(String intent,String run,FrozenSourceStagePlan.Reference p1,long part){
        try {
            identity(intent,run,part);Objects.requireNonNull(p1);
            var dependency=reads.execute(s->{var relation=handoffs.readCommitted(p1);if(relation==null)return null;var original=proofs.read(p1);if(original==null||!intent.equals(relation.p2Owner())||!run.equals(relation.runId())||!original.hash().equals(relation.p1ResultHash()))throw invalid();return new Original(original,relation.observedAt());});
            if(dependency==null)return null;Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);if(now.isBefore(dependency.observed()))throw invalid();
            var profile=FrozenSourceDedupDependency.create(dependency.proof(),dependency.observed(),now).toJson();
            var day=dayPermits==null?null:dayPermits.capture(profile,now);if(dayPermits!=null&&day==null)return null;
            var captured=writes.execute(captureContext(intent,run),key->day==null?value.captureCommitted(intent,run):value.captureCommittedDay(intent,run,day,null),action->{newWork();return day==null?value.capture(intent,run,profile):value.captureDay(intent,run,profile,day);});
            var prepared=value.build(captured);
            var plan=writes.execute(context(intent,run),key->day==null?value.committed(intent,run):value.committedDay(intent,run,day,null),action->{newWork();return value.commit(prepared);});return preparePart(intent,run,part,plan,null,day);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private record Original(SourceDedupStageResult proof,Instant observed){ }
    public Prepared prepareValuePartial(String intent,String run,FrozenSourceStagePlan.Reference p1,long part){
        try {
            identity(intent,run,part);Objects.requireNonNull(p1);if(partialPermits==null)throw invalid();
            var dependency=reads.execute(s->{var relation=handoffs.readCommitted(p1);if(relation==null)return null;var original=proofs.read(p1);if(original==null||!intent.equals(relation.p2Owner())||!run.equals(relation.runId())||!original.hash().equals(relation.p1ResultHash())||!"source-dedup-stage-result-v2".equals(CanonicalJson.parse(original.json()).path("schema_version").asText()))throw invalid();return new Original(original,relation.observedAt());});
            if(dependency==null)return null;Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);if(now.isBefore(dependency.observed()))throw invalid();var profile=FrozenSourceDedupDependency.create(dependency.proof(),dependency.observed(),now);
            var permit=partialPermits.capture(profile.toJson(),now);if(permit==null||!profile.json().equals(permit.dependency().json()))throw invalid();
            var day=dayPermits==null?null:dayPermits.capture(profile.toJson(),now);if(dayPermits!=null&&day==null)return null;
            var captured=writes.execute(captureContext(intent,run),key->day==null?value.captureCommittedPartial(intent,run,permit):value.captureCommittedDay(intent,run,day,permit),action->{newWork();return day==null?value.capturePartial(intent,run,permit):value.captureDayPartial(intent,run,permit,day);});var prepared=value.build(captured);
            var plan=writes.execute(context(intent,run),key->day==null?value.committedPartial(intent,run,permit):value.committedDay(intent,run,day,permit),action->{newWork();return value.commit(prepared);});return preparePart(intent,run,part,plan,permit,day);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared preparePart(String intent,String run,long part,SourcePartitionPlanner.Plan plan){
        return preparePart(intent,run,part,plan,null);
    }
    private Prepared preparePart(String intent,String run,long part,SourcePartitionPlanner.Plan plan,SourceDedupPartialDependencyPermitService.Captured permit){
        return preparePart(intent,run,part,plan,permit,null);
    }
    private Prepared preparePart(String intent,String run,long part,SourcePartitionPlanner.Plan plan,SourceDedupPartialDependencyPermitService.Captured permit,SourceDayNewsValuePermitService.Captured day){
        if(plan==null||!intent.equals(plan.reference().ownerId())||!run.equals(plan.reference().runId()))throw invalid();
        if(plan.chunks().isEmpty()){if(part!=0)throw invalid();return new Prepared(plan,null);}if(part>=plan.chunks().size())throw invalid();
        String action=partAction(intent,part);var captured=permit==null?parts.capturePart(intent,part,action):parts.capturePartPartial(intent,part,action,permit);var root=writes.execute(new SharedWriteExecutor.ActionContext(action,plan.chunks().get((int)part).rootId(),plan.chunks().get((int)part).inputHash()),key->{checkDayPlan(plan,day);return parts.committedCaptured(captured);},context->{
            checkDayPlan(plan,day);
            newWork();var owner=parents.lockOwner(intent);if(!ACTIVE.contains(owner.state())||!plan.logicalKey().equals(owner.key())||plan.generation()!=owner.generation()||!plan.scope().businessDate().equals(owner.businessDate())||!plan.stage().name().toLowerCase(Locale.ROOT).equals(owner.type()))throw invalid();return parts.prepareCaptured(captured);
        });return new Prepared(plan,root);
    }
    private void checkDayPlan(SourcePartitionPlanner.Plan plan,SourceDayNewsValuePermitService.Captured day){
        if(plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE)return;var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));
        if(dayPermits==null&&!context.has(FrozenSourceDayDedupDependency.FIELD))return;if(dayPermits==null||day==null||!context.has(FrozenSourceDayDedupDependency.FIELD))throw invalid();
        var actual=TransactionSynchronizationManager.isCurrentTransactionReadOnly()?dayPermits.verifyObserved(day,plan.scope().cutoff()):dayPermits.verify(day,plan.scope().cutoff());
        if(actual==null||!actual.json().equals(day.dependency().json())||!actual.json().equals(new String(CanonicalJson.bytes(context.get(FrozenSourceDayDedupDependency.FIELD)),java.nio.charset.StandardCharsets.UTF_8)))throw invalid();
    }
    private void newWork(){if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();}
    private static void identity(String intent,String run,long part){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();WireValues.id(intent,false);WireValues.id(run,false);if(part<0||part>=10000)throw invalid();}
    private static SharedWriteExecutor.ActionContext context(String intent,String run){return new SharedWriteExecutor.ActionContext("source-plan-freeze:"+intent,intent,run);}
    private static SharedWriteExecutor.ActionContext captureContext(String intent,String run){return new SharedWriteExecutor.ActionContext("source-plan-capture:"+intent,intent,run);}
    public static String partAction(String intent,long part){WireValues.id(intent,false);if(part<0||part>=10000)throw invalid();return "source-stage-part:"+intent+":"+part;}
    private static IllegalStateException invalid(){return new IllegalStateException("original source fragment coordination unavailable");}
}
