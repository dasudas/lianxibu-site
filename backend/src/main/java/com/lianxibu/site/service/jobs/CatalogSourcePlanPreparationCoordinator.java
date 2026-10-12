package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owns capture, complete plan and one original P3-P6 fragment in separate shared writers. */
public final class CatalogSourcePlanPreparationCoordinator {
    public record Prepared(SourcePartitionPlanner.Plan plan,GenerationFlowService.Frozen root){
        public Prepared {Objects.requireNonNull(plan);}
        @Override public String toString(){return "PreparedOriginalCatalogFragment[redacted]";}
    }
    private static final Set<String> ACTIVE=Set.of("waiting_input","prepared","queued","running");
    private final CatalogSourcePlanPreparationService preparation;
    private final SourceStagePreparationService parts;
    private final JdbcFrozenSourcePlans parents;
    private final SharedWriteExecutor writes;
    private final BooleanSupplier allowed;
    private final GenerationFlowService.Stage stage;
    public CatalogSourcePlanPreparationCoordinator(CatalogSourcePlanPreparationService preparation,
            SourceStagePreparationService parts,JdbcFrozenSourcePlans parents,SharedWriteExecutor writes,
            String category,BooleanSupplier allowed){
        this.preparation=Objects.requireNonNull(preparation);this.parts=Objects.requireNonNull(parts);
        this.parents=Objects.requireNonNull(parents);this.writes=Objects.requireNonNull(writes);this.allowed=Objects.requireNonNull(allowed);
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw invalid();
        this.stage=GenerationFlowService.Stage.valueOf(category.toUpperCase(Locale.ROOT)+"_DEDUP");
    }
    public Prepared prepareDedup(String intent,String run,long part){
        try{
            identity(intent,run,part);
            var captured=writes.execute(new SharedWriteExecutor.ActionContext("source-plan-capture:"+intent,intent,run),
                key->preparation.captureCommitted(intent,run),action->{newWork();return preparation.capture(intent,run);});
            var built=preparation.build(captured);
            var plan=writes.execute(new SharedWriteExecutor.ActionContext("source-plan-freeze:"+intent,intent,run),
                key->preparation.committed(intent,run),action->{newWork();return preparation.commit(built);});
            return preparePart(intent,run,part,plan);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw invalid();}
    }
    private Prepared preparePart(String intent,String run,long part,SourcePartitionPlanner.Plan plan){
        if(plan==null||plan.stage()!=stage||!intent.equals(plan.reference().ownerId())||!run.equals(plan.reference().runId()))throw invalid();
        if(plan.chunks().isEmpty()){if(part!=0)throw invalid();return new Prepared(plan,null);}
        if(part>=plan.chunks().size())throw invalid();
        String action=SourcePlanPreparationCoordinator.partAction(intent,part);
        var captured=parts.capturePart(intent,part,action);var chunk=plan.chunks().get((int)part);
        var root=writes.execute(new SharedWriteExecutor.ActionContext(action,chunk.rootId(),chunk.inputHash()),
            key->parts.committedCaptured(captured),context->{
                newWork();var owner=parents.lockOwner(intent);
                if(owner==null||!ACTIVE.contains(owner.state())||!plan.logicalKey().equals(owner.key())
                        ||plan.generation()!=owner.generation()||!plan.scope().businessDate().equals(owner.businessDate())
                        ||!stage.name().toLowerCase(Locale.ROOT).equals(owner.type()))throw invalid();
                return parts.prepareCaptured(captured);
            });return new Prepared(plan,root);
    }
    private void newWork(){if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();}
    private static void identity(String intent,String run,long part){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        WireValues.id(intent,false);WireValues.id(run,false);if(part<0||part>=10000)throw invalid();
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog fragment coordination unavailable");}
    @Override public String toString(){return "CatalogSourcePlanPreparationCoordinator[redacted]";}
}
