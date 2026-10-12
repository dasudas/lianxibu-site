package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import java.time.Instant;
import java.util.Objects;

/** Derives final source output exclusively from the actual committed original P2 proof. */
public final class SourceValueFinalProofService {
    public record Committed(SourceValueFinalResult result,Instant minimumObservationTime) {
        public Committed {Objects.requireNonNull(result);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
        @Override public String toString(){return "CommittedSourceValueFinal[redacted]";}
    }
    private final SourceValueStageProofService stages;
    private final FrozenSourceStagePlanService plans;
    private final FrozenSourceUniverseService universes;
    public SourceValueFinalProofService(SourceValueStageProofService stages,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes){this.stages=Objects.requireNonNull(stages);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);}
    public SourceValueFinalResult read(FrozenSourceStagePlan.Reference reference){var committed=readCommitted(reference);return committed==null?null:committed.result();}
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);
            var original=stages.readCommitted(reference);if(original==null)return null;
            var plan=plans.readImmutable(reference);var universe=universes.readImmutable(plan.reference());
            boolean partial="source-value-stage-result-v2".equals(CanonicalJson.parse(original.result().json()).path("schema_version").asText());
            var result=partial?SourceValueFinalResult.createPartial(plan,universe,original.result()):SourceValueFinalResult.create(plan,universe,original.result());
            return new Committed(result,original.minimumObservationTime());
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("complete original source value final proof unavailable");}
}
