package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSourceDedupResults;
import java.time.Instant;
import java.util.*;

/** Complete original native catalog stage and immutable collection proof; no current source substitution. */
public final class CatalogSourceFinalProofService {
 public record Committed(CatalogSourceFinalResult result,Instant minimumObservationTime){
  public Committed {Objects.requireNonNull(result);if(minimumObservationTime==null||minimumObservationTime.getNano()%1000!=0)throw invalid();}
  @Override public String toString(){return "CommittedCatalogSourceFinal[redacted]";}
 }
 private final SourceDedupStageProofService stages;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;private final CatalogSourceIntentOriginService collections;private final GenerationFlowService.Stage stage;
 public CatalogSourceFinalProofService(SourceDedupStageProofService stages,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes,CatalogSourceIntentOriginService collections,String category){
  this.stages=Objects.requireNonNull(stages);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);this.collections=Objects.requireNonNull(collections);
  stage=switch(Objects.requireNonNull(category)){case "agent"->GenerationFlowService.Stage.AGENT_DEDUP;case "skill"->GenerationFlowService.Stage.SKILL_DEDUP;case "workflow"->GenerationFlowService.Stage.WORKFLOW_DEDUP;case "model"->GenerationFlowService.Stage.MODEL_DEDUP;default->throw invalid();};
 }
 public CatalogSourceFinalResult read(FrozenSourceStagePlan.Reference reference){var committed=readCommitted(reference);return committed==null?null:committed.result();}
 public Committed readCommitted(FrozenSourceStagePlan.Reference reference){
  try {
   JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);var original=stages.readCommitted(reference);if(original==null)return null;
   var plan=plans.readImmutable(reference);if(plan.stage()!=stage)throw invalid();var universe=universes.readImmutable(plan.reference());
   var collection=collections.readCollection(reference.ownerId());if(collection==null)throw invalid();
   boolean partial="source-dedup-stage-result-v2".equals(CanonicalJson.parse(original.proof().json()).path("schema_version").asText());
   var result=partial?CatalogSourceFinalResult.createPartial(plan,universe,original.proof(),collection.collection()):CatalogSourceFinalResult.create(plan,universe,original.proof(),collection.collection());
   Instant minimum=original.minimumObservationTime().isAfter(collection.actedAt())?original.minimumObservationTime():collection.actedAt();if(minimum.isBefore(plan.scope().cutoff()))throw invalid();return new Committed(result,minimum);
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
 }
 private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog final proof unavailable");}
}
