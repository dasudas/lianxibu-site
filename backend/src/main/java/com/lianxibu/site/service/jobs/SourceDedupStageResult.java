package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Complete structural P1/P3-P6 output. Natural-language merging and storage remain separate. */
public final class SourceDedupStageResult {
 public static final int MAX_BYTES=16*1024*1024;
 private final String json,hash,observationKeyJson;private final FrozenSourceStagePlan.Reference planRef;
 private final List<Fact> members;private final List<SourcePartitionPlanner.Decision> decisions;
 private SourceDedupStageResult(String json,String hash,String observationKeyJson,FrozenSourceStagePlan.Reference ref,List<Fact> members,List<SourcePartitionPlanner.Decision> decisions){this.json=json;this.hash=hash;this.observationKeyJson=observationKeyJson;this.planRef=ref;this.members=List.copyOf(members);this.decisions=List.copyOf(decisions);}
 public String json(){return json;}public String hash(){return hash;}public FrozenSourceStagePlan.Reference planRef(){return planRef;}
 public List<Fact> members(){return members;}public List<SourcePartitionPlanner.Decision> decisions(){return decisions;}
 public String observationKeyJson(){return observationKeyJson;}
 public static SourceDedupStageResult create(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Reference ref,List<SourceDedupFragmentProof> fragments){return createWithinCapacity(plan,universe,ref,fragments,MAX_BYTES);}
 static SourceDedupStageResult createWithinCapacity(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Reference ref,List<SourceDedupFragmentProof> fragments,int maximum){
  try {
   if(maximum<1||maximum>MAX_BYTES||plan==null||!plan.stage().dedup()||plan.scope()==null||ref==null||fragments==null||fragments.size()!=plan.chunks().size())throw invalid();
   if(!ref.equals(FrozenSourceStagePlan.encode(plan,universe).reference()))throw invalid();var byRoot=new HashMap<String,SourceDedupFragmentProof>();
   for(var f:fragments)if(f==null||byRoot.putIfAbsent(f.rootId(),f)!=null)throw invalid();
   var json=JsonMapper.shared().createObjectNode().put("schema_version","source-dedup-stage-result-v1").put("run_id",universe.runId()).put("source_id",universe.sourceId())
    .put("logical_key",plan.logicalKey()).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString())
    .put("cutoff",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(plan.scope().cutoff())).put("settings_hash",plan.scope().settings().semanticHash())
    .put("accepted",universe.accepted()).put("excluded",universe.excluded()).put("stage_part_count",plan.chunks().size());
   json.set("plan_ref",CanonicalJson.parse(ref.json()));json.set("universe_ref",CanonicalJson.parse(plan.reference().json()));
   var decisions=json.putArray("decisions");long eligible=0;for(var d:plan.decisions()){decisions.addObject().put("item_id",d.itemId()).put("reason",d.reason().name());if(d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE)eligible++;}json.put("eligible_count",eligible);
   var parts=json.putArray("fragments");long estimated=CanonicalJson.bytes(json).length;var members=new ArrayList<Fact>();
   for(var chunk:plan.chunks()){
    var f=byRoot.remove(chunk.rootId());if(f==null||f.generation()!=plan.generation()||f.stagePart()!=chunk.stagePart()||!chunk.inputHash().equals(f.result().inputHash())||!"dedup".equals(f.result().kind())
      ||!chunk.itemIds().equals(f.members().stream().map(Fact::itemId).toList()))throw invalid();
    var part=JsonMapper.shared().createObjectNode().put("root_id",f.rootId()).put("stage_part",f.stagePart()).put("input_hash",f.result().inputHash()).put("result_hash",f.result().resultHash()).put("handoff_hash",f.handoffHash());
    part.set("payload",CanonicalJson.parse(f.result().payloadJson()));part.set("members",JsonMapper.shared().valueToTree(f.members()));
    estimated+=CanonicalJson.bytes(part).length+1;if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();parts.add(part);members.addAll(f.members());
   }
   if(!byRoot.isEmpty()||members.size()!=eligible)throw invalid();byte[] bytes=CanonicalJson.bytes(json);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();
   String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json);var key=JsonMapper.shared().createObjectNode().put("schema_version","source-dedup-stage-key-v1").put("owner_job_id",ref.ownerId()).put("plan_hash",ref.planHash()).put("plan_index_hash",ref.indexHash()).put("result_hash",hash)
    .put("stage",plan.stage().name().toLowerCase(Locale.ROOT)).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString()).put("universe_hash",plan.reference().universeHash()).put("universe_index_hash",plan.reference().indexHash()).put("run_id",universe.runId()).put("source_id",universe.sourceId());
   byte[] identity=CanonicalJson.bytes(key);if(identity.length>2048)throw invalid();return new SourceDedupStageResult(new String(bytes,StandardCharsets.UTF_8),hash,new String(identity,StandardCharsets.UTF_8),ref,members,plan.decisions());
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
  catch(RuntimeException rejected){throw invalid();}
 }
 public static SourceDedupStageResult createResolved(SourceStageResolutionService.Snapshot snapshot){return createResolvedWithinCapacity(snapshot,MAX_BYTES);}
 static SourceDedupStageResult createResolvedWithinCapacity(SourceStageResolutionService.Snapshot snapshot,int maximum){
  try {
   if(maximum<1||maximum>MAX_BYTES||snapshot==null||!snapshot.settled())throw invalid();
   var plan=snapshot.plan();var universe=snapshot.universe();var ref=snapshot.reference();
   if(!plan.stage().dedup()||plan.scope()==null||!ref.equals(FrozenSourceStagePlan.encode(plan,universe).reference())||snapshot.fragments().size()!=plan.chunks().size())throw invalid();
   var proofs=new ArrayList<SourceDedupFragmentProof>();for(var resolved:snapshot.fragments())if(resolved.state()==SourceStageResolutionService.State.APPLIED){if(resolved.dedup()==null||resolved.value()!=null)throw invalid();proofs.add(resolved.dedup().proof());}
   if(!snapshot.hasFailures())return createWithinCapacity(plan,universe,ref,proofs,maximum);
   var json=JsonMapper.shared().createObjectNode().put("schema_version","source-dedup-stage-result-v2").put("run_id",universe.runId()).put("source_id",universe.sourceId())
    .put("logical_key",plan.logicalKey()).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString())
    .put("cutoff",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(plan.scope().cutoff())).put("settings_hash",plan.scope().settings().semanticHash())
    .put("accepted",universe.accepted()).put("excluded",universe.excluded()).put("stage_part_count",plan.chunks().size()).put("coverage_state","partial");
   json.set("plan_ref",CanonicalJson.parse(ref.json()));json.set("universe_ref",CanonicalJson.parse(plan.reference().json()));
   var decisions=json.putArray("decisions");long eligible=0;for(var d:plan.decisions()){decisions.addObject().put("item_id",d.itemId()).put("reason",d.reason().name());if(d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE)eligible++;}json.put("eligible_count",eligible);
   var resolutions=json.putArray("resolutions");var parts=json.putArray("fragments");var members=new ArrayList<Fact>();long planned=0;
   for(int i=0;i<plan.chunks().size();i++){
    var resolved=snapshot.fragments().get(i);var chunk=plan.chunks().get(i);var root=resolved.nativeRoot();
    if(!chunk.equals(resolved.chunk())||root==null||!chunk.rootId().equals(root.id())||chunk.stagePart()!=root.part()||!chunk.inputHash().equals(root.inputHash()))throw invalid();
    planned+=chunk.itemIds().size();var row=resolutions.addObject().put("root_id",root.id()).put("stage_part",chunk.stagePart()).put("input_hash",chunk.inputHash()).put("state",root.state()).put("revision",root.revision()).put("call_count",root.calls());
    row.set("item_ids",JsonMapper.shared().valueToTree(chunk.itemIds()));if(root.application()==null)row.putNull("application_state");else row.put("application_state",root.application());
    if(root.resultHash()==null)row.putNull("result_hash");else row.put("result_hash",root.resultHash());if(root.appliedAt()==null)row.putNull("applied_at");else row.put("applied_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(root.appliedAt()));
    if(resolved.state()==SourceStageResolutionService.State.APPLIED){
     var applied=resolved.dedup();var f=applied.proof();
     if(!"succeeded".equals(root.state())||!"APPLIED".equals(root.application())||root.calls()<1||!Objects.equals(root.appliedAt(),applied.appliedAt())||!Objects.equals(root.resultHash(),f.result().resultHash())
       ||!chunk.rootId().equals(f.rootId())||f.generation()!=plan.generation()||f.stagePart()!=chunk.stagePart()||!chunk.inputHash().equals(f.result().inputHash())||!"dedup".equals(f.result().kind())||!chunk.itemIds().equals(f.members().stream().map(Fact::itemId).toList()))throw invalid();
     var part=parts.addObject().put("root_id",f.rootId()).put("stage_part",f.stagePart()).put("input_hash",f.result().inputHash()).put("result_hash",f.result().resultHash()).put("handoff_hash",f.handoffHash());part.set("payload",CanonicalJson.parse(f.result().payloadJson()));part.set("members",JsonMapper.shared().valueToTree(f.members()));members.addAll(f.members());
    }else {
     if(resolved.dedup()!=null||resolved.value()!=null||root.appliedAt()!=null)throw invalid();
     switch(resolved.state()){
      case FAILED->{if(!"failed".equals(root.state())||root.calls()!=3||root.application()!=null||root.resultHash()!=null)throw invalid();}
      case CANCELLED->{if(!"cancelled".equals(root.state())||root.application()!=null||root.resultHash()!=null)throw invalid();}
      case DISCARDED,SUPERSEDED->{String expected=resolved.state().name();if(!expected.toLowerCase(Locale.ROOT).equals(root.state())||(root.resultHash()==null?root.application()!=null:!expected.equals(root.application())))throw invalid();}
      default->throw invalid();
     }
    }
   }
   if(planned!=eligible||members.size()>eligible)throw invalid();json.put("applied_member_count",members.size()).put("unapplied_member_count",eligible-members.size());
   byte[] bytes=CanonicalJson.bytes(json);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json);
   var key=JsonMapper.shared().createObjectNode().put("schema_version","source-dedup-stage-key-v1").put("owner_job_id",ref.ownerId()).put("plan_hash",ref.planHash()).put("plan_index_hash",ref.indexHash()).put("result_hash",hash)
    .put("stage",plan.stage().name().toLowerCase(Locale.ROOT)).put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString()).put("universe_hash",plan.reference().universeHash()).put("universe_index_hash",plan.reference().indexHash()).put("run_id",universe.runId()).put("source_id",universe.sourceId());
   byte[] identity=CanonicalJson.bytes(key);if(identity.length>2048)throw invalid();return new SourceDedupStageResult(new String(bytes,StandardCharsets.UTF_8),hash,new String(identity,StandardCharsets.UTF_8),ref,members,plan.decisions());
  }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
 }

 private static IllegalStateException invalid(){return new IllegalStateException("complete original source dedup result unavailable");}
 @Override public String toString(){return "SourceDedupStageResult[redacted]";}
}
