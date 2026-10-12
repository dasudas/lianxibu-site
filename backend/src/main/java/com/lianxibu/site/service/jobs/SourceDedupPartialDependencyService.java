package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Actual read-only partial P1 proof. A separate writer consumer must recheck before using it. */
public final class SourceDedupPartialDependencyService {
    public static final class Proven {
        private final FrozenSourceDedupDependency dependency;private final SourceDedupPartialSealService.Committed seal;
        private Proven(FrozenSourceDedupDependency dependency,SourceDedupPartialSealService.Committed seal){this.dependency=dependency;this.seal=seal;}
        public FrozenSourceDedupDependency dependency(){return dependency;}public SourceDedupPartialSealService.Committed seal(){return seal;}
        @Override public String toString(){return "ProvenPartialSourceDedupDependency[redacted]";}
    }
    private final SourceDedupPartialSealService seals;private final JdbcSourceStageAvailability observations;
    public SourceDedupPartialDependencyService(SourceDedupPartialSealService seals,JdbcSourceStageAvailability observations){this.seals=Objects.requireNonNull(seals);this.observations=Objects.requireNonNull(observations);}
    public Proven read(JsonNode profile,Instant cutoff){
        try {
            JdbcSourceDedupResults.requireReadOnly();var structural=FrozenSourceDedupDependency.readPartial(profile,cutoff);
            var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(structural.toJson().get("stage_result").get("plan_ref")));
            var seal=seals.readCommitted(reference);if(seal==null)throw invalid();
            var original=FrozenSourceDedupDependency.read(profile,seal.proof(),cutoff);Instant observed=observations.read(original.observationKeyJson());
            if(observed==null||!observed.equals(original.observedAt())||observed.isBefore(seal.minimumObservationTime()))throw invalid();return new Proven(original,seal);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("committed original partial source dedup dependency unavailable");}
}
