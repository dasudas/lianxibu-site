package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Fresh original authority, captured outside a writer and rechecked under its native locks. */
public final class CatalogSourceFinalCommitPermitService {
 public static final class Captured {
  private final Object issuer;private final CatalogSourceFinalProofService.Committed original;private final FrozenCatalogSourceFinal.Reference archive;private final JdbcCatalogSourceFinalCommitFacts.Snapshot nativeFacts;
  private Captured(Object issuer,CatalogSourceFinalProofService.Committed original,FrozenCatalogSourceFinal.Reference archive,JdbcCatalogSourceFinalCommitFacts.Snapshot nativeFacts){this.issuer=issuer;this.original=original;this.archive=archive;this.nativeFacts=nativeFacts;}
  public CatalogSourceFinalProofService.Committed proof(){return original;}public FrozenCatalogSourceFinal.Reference archiveRef(){return archive;}
  @Override public String toString(){return "CapturedCatalogSourceFinalCommit[redacted]";}
 }
 private final Object issuer=new Object();private final CatalogSourceFinalProofService proofs;private final CatalogSourceFinalArchiveService archives;private final FrozenSourceStagePlanService plans;private final SourceStageResolutionService resolutions;private final JdbcCatalogSourceFinalCommitFacts nativeFacts;private final TransactionTemplate reads;private final GenerationFlowService.Stage stage;
 public CatalogSourceFinalCommitPermitService(CatalogSourceFinalProofService proofs,CatalogSourceFinalArchiveService archives,FrozenSourceStagePlanService plans,SourceStageResolutionService resolutions,JdbcCatalogSourceFinalCommitFacts nativeFacts,PlatformTransactionManager tx,String category){
  this.proofs=Objects.requireNonNull(proofs);this.archives=Objects.requireNonNull(archives);this.plans=Objects.requireNonNull(plans);this.resolutions=Objects.requireNonNull(resolutions);this.nativeFacts=Objects.requireNonNull(nativeFacts);stage=switch(Objects.requireNonNull(category)){case "agent"->GenerationFlowService.Stage.AGENT_DEDUP;case "skill"->GenerationFlowService.Stage.SKILL_DEDUP;case "workflow"->GenerationFlowService.Stage.WORKFLOW_DEDUP;case "model"->GenerationFlowService.Stage.MODEL_DEDUP;default->throw invalid();};reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Captured capture(FrozenSourceStagePlan.Reference reference,FrozenCatalogSourceFinal.Reference archive){
  try{
   if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Objects.requireNonNull(reference);Objects.requireNonNull(archive);return reads.execute(status->{
    var plan=plans.readImmutable(reference);if(plan.stage()!=stage)throw invalid();var roots=plan.chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList();var before=nativeFacts.capture(reference.ownerId(),roots);
    var snapshot=resolutions.read(reference);if(!snapshot.settled())return null;var original=proofs.readCommitted(reference);if(original==null)return null;var whole=SourceDedupStageResult.createResolved(snapshot);var body=CanonicalJson.parse(original.result().json());if(!whole.hash().equals(WireValues.sha256(body.get("whole_result_hash")))||!whole.json().equals(JdbcFrozenInputs.canonical(body.get("stage_result"))))throw invalid();archives.verifyStaged(archive,original.result());
    var after=nativeFacts.capture(reference.ownerId(),roots);if(!before.equals(after))throw invalid();return new Captured(issuer,original,archive,before);
   });
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcCatalogSourceFinalCommitFacts.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
 }
 /** Valid only while the caller retains all native locks until its writer commits. */
 public CatalogSourceFinalProofService.Committed verify(Captured captured){
  try{
   JdbcFrozenInputs.requireTx();if(captured==null||captured.issuer!=issuer)throw invalid();var original=captured.nativeFacts;if(!original.equals(nativeFacts.lockAndRead(original.owner(),original.roots())))throw invalid();return captured.original;
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(JdbcCatalogSourceFinalCommitFacts.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
 }
 private static IllegalStateException invalid(){return new IllegalStateException("original catalog source final writer permission unavailable");}
}
