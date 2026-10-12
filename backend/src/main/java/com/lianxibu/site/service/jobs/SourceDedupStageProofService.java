package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import java.time.Instant;
import java.util.*;

/** Original complete P1/P3-P6 proof, with explicitly injected durable partial proof capability. */
public final class SourceDedupStageProofService {
 public record Committed(SourceDedupStageResult proof,Instant minimumObservationTime) {
  public Committed {Objects.requireNonNull(proof);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
  @Override public String toString(){return "CommittedSourceDedupStage[redacted]";}
 }
 private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;private final SourceDedupResultProofService fragments;private final SourceDedupPartialSealService partials;
 public SourceDedupStageProofService(FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceDedupResultProofService fragments){this(plans,universes,fragments,null);}
 public SourceDedupStageProofService(FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,SourceDedupResultProofService fragments,SourceDedupPartialSealService partials){this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.fragments=Objects.requireNonNull(fragments);this.partials=partials;}
 public SourceDedupStageResult read(FrozenSourceStagePlan.Reference reference){var committed=readCommitted(reference);return committed==null?null:committed.proof();}
 public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
  try {
   JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);if(partials!=null){var sealed=partials.readCommitted(reference);if(sealed!=null)return new Committed(sealed.proof(),sealed.minimumObservationTime());}var plan=plans.readImmutable(reference);if(!plan.stage().dedup()||plan.scope()==null)throw invalid();
   var original=universes.readImmutable(plan.reference());var results=new ArrayList<SourceDedupFragmentProof>();Instant minimum=plan.scope().cutoff();
   var appliedRoots=fragments.readAppliedBatch(reference,plan.chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList());
   for(var chunk:plan.chunks()){var applied=appliedRoots.get(chunk.rootId());if(applied==null)return null;results.add(applied.proof());if(applied.appliedAt().isAfter(minimum))minimum=applied.appliedAt();}
   return new Committed(SourceDedupStageResult.create(plan,original,reference,results),minimum);
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
  catch(RuntimeException rejected){throw invalid();}
 }
 private static IllegalStateException invalid(){return new IllegalStateException("complete original source dedup proof unavailable");}
}
