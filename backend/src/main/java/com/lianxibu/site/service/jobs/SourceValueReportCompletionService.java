package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original source result and complete original fragments at a fixed report cutoff. */
public final class SourceValueReportCompletionService {
    public record Completed(SourcePartitionPlanner.Plan plan,SourceValueStageResult stage,
                            SourceValueFinalizationService.Committed finalResult,Instant availableAt){
        public Completed{Objects.requireNonNull(plan);Objects.requireNonNull(stage);Objects.requireNonNull(finalResult);Objects.requireNonNull(availableAt);}
        @Override public String toString(){return "OriginalSourceReportCompletion[redacted]";}
    }
    private final FrozenSourceStagePlanService plans;
    private final SourceValueStageProofService stages;
    private final SourceValueFinalizationService finals;
    private final SourceValueFinalAvailabilityService availability;
    private final TransactionTemplate reads;
    public SourceValueReportCompletionService(FrozenSourceStagePlanService plans,SourceValueStageProofService stages,
                                              SourceValueFinalizationService finals,SourceValueFinalAvailabilityService availability,
                                              PlatformTransactionManager manager){
        this.plans=Objects.requireNonNull(plans);this.stages=Objects.requireNonNull(stages);this.finals=Objects.requireNonNull(finals);this.availability=Objects.requireNonNull(availability);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Completed find(FrozenSourceStagePlan.Reference reference,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(s->read(reference,cutoff));
    }
    /** Borrowed RR only; a later whole-day consumer must also prove the original source roster. */
    public Completed read(FrozenSourceStagePlan.Reference reference,Instant cutoff){
        readonly();if(reference==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var plan=plans.readImmutable(reference);if(plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null)throw invalid();
        var complete=finals.readCommitted(reference);if(complete==null)return null;
        var stage=stages.readCommitted(reference);if(stage==null)throw invalid();
        var body=CanonicalJson.parse(complete.result().json());var receipt=complete.receipt();
        if(!reference.equals(stage.result().planRef())||!reference.json().equals(new String(CanonicalJson.bytes(body.get("plan_ref")),java.nio.charset.StandardCharsets.UTF_8))
            ||!stage.result().hash().equals(WireValues.sha256(body.get("whole_result_hash")))
            ||!reference.ownerId().equals(receipt.position().ownerId())||!plan.logicalKey().equals(receipt.position().logicalKey())
            ||plan.generation()!=receipt.position().generation()||!plan.scope().businessDate().equals(receipt.businessDate())
            ||!complete.result().hash().equals(receipt.position().resultHash()))throw invalid();
        var observed=availability.availableAt(complete,cutoff);if(observed==null)return null;
        if(observed.isBefore(stage.minimumObservationTime())||observed.isBefore(complete.minimumObservationTime()))throw invalid();
        return new Completed(plan,stage.result(),complete,observed);
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original source report completion unavailable");}
}
