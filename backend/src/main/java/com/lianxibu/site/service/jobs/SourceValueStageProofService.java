package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import java.time.Instant;
import java.util.*;

/** Original complete P2 proof, with explicitly injected durable partial proof capability. */
public final class SourceValueStageProofService {
    public record Committed(SourceValueStageResult result,Instant minimumObservationTime) {
        public Committed {Objects.requireNonNull(result);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
        @Override public String toString(){return "CommittedSourceValueStage[redacted]";}
    }
    private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
    private final SourceValueResultProofService fragments;private final SourceDedupDependencyService dependencies;private final SourceValuePartialSealService partials;
    public SourceValueStageProofService(FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueResultProofService fragments,SourceDedupDependencyService dependencies){this(plans,universes,fragments,dependencies,null);}
    public SourceValueStageProofService(FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceValueResultProofService fragments,SourceDedupDependencyService dependencies,SourceValuePartialSealService partials){this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.fragments=Objects.requireNonNull(fragments);this.dependencies=Objects.requireNonNull(dependencies);this.partials=partials;}
    public SourceValueStageResult read(FrozenSourceStagePlan.Reference reference){var committed=readCommitted(reference);return committed==null?null:committed.result();}
    public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
        try {
            JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var plan=plans.readImmutable(reference);
            if(plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null||plan.scope().stageContextJson()==null)throw invalid();
            var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));
            if(partials!=null){var sealed=partials.readCommitted(reference);if(sealed!=null)return new Committed(sealed.proof(),sealed.minimumObservationTime());}
            if("source-dedup-dependency-v2".equals(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version").asText())){
                dependencies.readPartial(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff());
                if(partials==null)throw invalid();return null;
            }
            dependencies.read(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff());
            var original=universes.readImmutable(plan.reference());var results=new ArrayList<SourceValueFragmentProof>();Instant minimum=plan.scope().cutoff();
            var appliedRoots=fragments.readAppliedBatch(reference,plan.chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList());
            for(var chunk:plan.chunks()){var applied=appliedRoots.get(chunk.rootId());if(applied==null)return null;results.add(applied.proof());if(applied.appliedAt().isAfter(minimum))minimum=applied.appliedAt();}
            return new Committed(SourceValueStageResult.create(plan,original,reference,results),minimum);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("complete original source value proof unavailable");}
}
