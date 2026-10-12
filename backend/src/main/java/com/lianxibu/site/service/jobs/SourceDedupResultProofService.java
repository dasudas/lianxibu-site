package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.*;
import com.lianxibu.site.domain.sources.MemberUniverse;

/** Applied P1/P3-P6 fragments from original archives. Cutoff availability is separate. */
public final class SourceDedupResultProofService {
 public record Applied(SourceDedupFragmentProof proof,Instant appliedAt) {
  public Applied {Objects.requireNonNull(proof);if(appliedAt==null||appliedAt.getNano()%1000!=0)throw invalid();}
  @Override public String toString(){return "AppliedSourceDedupFragment[redacted]";}
 }
 private static final Set<String> TYPES=Set.of("news_dedup","agent_dedup","skill_dedup","workflow_dedup","model_dedup");
 private final JdbcSourceDedupResults storage;private final FrozenSourceStagePlanService plans;private final FrozenSourceUniverseService universes;
 public SourceDedupResultProofService(JdbcSourceDedupResults storage,FrozenSourceStagePlanService plans,FrozenSourceUniverseService universes){this.storage=Objects.requireNonNull(storage);this.plans=Objects.requireNonNull(plans);this.universes=Objects.requireNonNull(universes);}
 public SourceDedupFragmentProof read(String rootId){var applied=readApplied(rootId);return applied==null?null:applied.proof();}
 public Applied readApplied(String rootId){
  try {return readApplied(rootId,null,null);}catch(RuntimeException rejected){throw invalid();}
 }
 /** Reuses only this call's actual original archives; every requested native result is read again. */
 public Map<String,Applied> readAppliedBatch(FrozenSourceStagePlan.Reference reference,List<String> rootIds){
  try {
   JdbcSourceDedupResults.requireReadOnly();Objects.requireNonNull(reference);Objects.requireNonNull(rootIds);
   if(rootIds.size()>10000)throw new GenerationFlowService.CapacityGuarded();var requested=new LinkedHashSet<String>();
   for(String id:rootIds){WireValues.id(id,false);if(!requested.add(id))throw invalid();}
   var plan=plans.readImmutable(reference);if(!plan.stage().dedup()||plan.scope()==null)throw invalid();
   var allowed=new HashSet<String>();for(var chunk:plan.chunks())if(!allowed.add(chunk.rootId()))throw invalid();if(!allowed.containsAll(requested))throw invalid();
   var original=universes.readImmutable(plan.reference());var applied=new LinkedHashMap<String,Applied>();
   for(String id:requested){var proof=readApplied(id,plan,original);if(proof!=null)applied.put(id,proof);}
   return Collections.unmodifiableMap(applied);
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
 }
 private Applied readApplied(String rootId,SourcePartitionPlanner.Plan originalPlan,MemberUniverse.Universe originalUniverse){
   JdbcSourceDedupResults.requireReadOnly();var root=storage.root(rootId);WireValues.id(root.intent(),false);
   if(root.key()==null||root.key().isBlank()||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.key()))||!TYPES.contains(root.type())
      ||!rootId.equals(SourcePartitionPlanner.rootId(root.key(),root.generation(),root.part()))||!root.intent().equals(JdbcFrozenInputs.stableId("intent",root.key()+":"+root.generation()))
      ||root.maximum()!=3||root.calls()<0||root.calls()>3)throw invalid();
   if(originalPlan!=null)originalRoot(root,originalPlan);
   String handoff=storage.handoff(rootId);boolean succeeded="succeeded".equals(root.state()),applied="APPLIED".equals(root.application()),at=root.appliedAt()!=null;
   if(!(succeeded&&applied&&at)){if(succeeded||applied||at||handoff!=null)throw invalid();return null;}
   if(root.calls()<1||root.appliedAt().getNano()%1000!=0||root.inputHash()==null||!root.inputHash().matches("[0-9a-f]{64}")||handoff==null)throw invalid();
   var plan=originalPlan==null?plans.readImmutable(root.intent()):originalPlan;originalRoot(root,plan);
   if(root.appliedAt().isBefore(plan.scope().cutoff()))throw invalid();
   var original=originalUniverse==null?universes.readImmutable(plan.reference()):originalUniverse;var proof=SourceDedupFragmentProof.read(plan,original,rootId,storage.retained(root),handoff);
   if(!Objects.equals(root.resultHash(),proof.result().resultHash()))throw invalid();return new Applied(proof,root.appliedAt());
 }
 private static void originalRoot(JdbcSourceDedupResults.Root root,SourcePartitionPlanner.Plan plan){
  if(!plan.stage().dedup()||!plan.stage().name().toLowerCase(Locale.ROOT).equals(root.type())||!root.intent().equals(plan.reference().ownerId())||!root.key().equals(plan.logicalKey())||root.generation()!=plan.generation()
     ||root.part()<0||root.part()>=plan.chunks().size()||plan.scope()==null)throw invalid();
  var chunk=plan.chunks().get((int)root.part());if(!root.id().equals(chunk.rootId())||!Objects.equals(root.inputHash(),chunk.inputHash()))throw invalid();
 }
 private static IllegalStateException invalid(){return new IllegalStateException("original applied source dedup result unavailable");}
}
