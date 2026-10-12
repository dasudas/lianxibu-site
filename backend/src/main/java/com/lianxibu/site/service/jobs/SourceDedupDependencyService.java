package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** The exact complete original P1 proof is usable only with its real committed-observation receipt. */
public final class SourceDedupDependencyService {
    public static final class Proven {
        private final FrozenSourceDedupDependency dependency;private final String runId,sourceId;private final LocalDate day;private final long generation;
        private Proven(FrozenSourceDedupDependency dependency) {
            this.dependency=dependency;var key=CanonicalJson.parse(dependency.observationKeyJson());runId=WireValues.id(key.get("run_id"),false);sourceId=WireValues.id(key.get("source_id"),true);
            day=WireValues.businessDate(key.get("business_date"));generation=WireValues.nonnegativeInteger(key.get("generation"));
        }
        public FrozenSourceDedupDependency dependency(){return dependency;}public String runId(){return runId;}public String sourceId(){return sourceId;}public LocalDate businessDate(){return day;}public long generation(){return generation;}
        @Override public String toString(){return "ProvenSourceDedupDependency[redacted]";}
    }
    private final JdbcSourceStageAvailability observations;private final SourceDedupPartialDependencyService partials;private final SourceDedupPartialDependencyPermitService permits;
    public SourceDedupDependencyService(JdbcSourceStageAvailability observations){this(observations,null,null);}
    public SourceDedupDependencyService(JdbcSourceStageAvailability observations,SourceDedupPartialDependencyService partials,SourceDedupPartialDependencyPermitService permits){this.observations=Objects.requireNonNull(observations);this.partials=partials;this.permits=permits;}
    public Proven read(JsonNode original,Instant cutoff) {
        try {
            if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
            var dependency=FrozenSourceDedupDependency.read(original,cutoff);var observed=observations.read(dependency.observationKeyJson());
            if(observed==null||!observed.equals(dependency.observedAt()))throw invalid();return new Proven(dependency);
        }catch(RuntimeException rejected){throw invalid();}
    }
    /** Explicit read-only partial proof; this cannot be used to bypass the writer permit. */
    public Proven readPartial(JsonNode original,Instant cutoff){
        try {JdbcSourceDedupResults.requireReadOnly();if(partials==null)throw invalid();return observed(partials.read(original,cutoff).dependency());}
        catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Uses native locks in this exact writer; a later writer must verify its capture again. */
    public Proven readPartial(SourceDedupPartialDependencyPermitService.Captured captured,Instant cutoff){
        try {JdbcFrozenInputs.requireTx();if(permits==null)throw invalid();return observed(permits.verify(captured,cutoff));}
        catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private Proven observed(FrozenSourceDedupDependency dependency){Instant observed=observations.read(dependency.observationKeyJson());if(!dependency.observedAt().equals(observed))throw invalid();return new Proven(dependency);}
    private static IllegalStateException invalid(){return new IllegalStateException("committed original source dedup dependency unavailable");}
}
