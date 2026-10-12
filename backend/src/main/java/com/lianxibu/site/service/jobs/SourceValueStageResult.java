package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Complete structural P2 output. Source-wide selection and prose merging are separate. */
public final class SourceValueStageResult {
    public static final int MAX_BYTES=16*1024*1024;
    private final String json,hash,observationKeyJson;private final FrozenSourceStagePlan.Reference planRef;
    private final List<Fact> members;private final List<SourcePartitionPlanner.Decision> decisions;private final List<SourceValueFragmentProof> fragments;
    private SourceValueStageResult(String json,String hash,String key,FrozenSourceStagePlan.Reference ref,List<Fact> members,List<SourcePartitionPlanner.Decision> decisions,List<SourceValueFragmentProof> fragments){this.json=json;this.hash=hash;observationKeyJson=key;planRef=ref;this.members=List.copyOf(members);this.decisions=List.copyOf(decisions);this.fragments=List.copyOf(fragments);}
    public String json(){return json;}public String hash(){return hash;}public String observationKeyJson(){return observationKeyJson;}
    public FrozenSourceStagePlan.Reference planRef(){return planRef;}public List<Fact> members(){return members;}public List<SourcePartitionPlanner.Decision> decisions(){return decisions;}public List<SourceValueFragmentProof> fragments(){return fragments;}
    public static SourceValueStageResult create(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Reference ref,List<SourceValueFragmentProof> fragments){return createWithinCapacity(plan,universe,ref,fragments,MAX_BYTES);}
    static SourceValueStageResult createWithinCapacity(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,FrozenSourceStagePlan.Reference ref,List<SourceValueFragmentProof> fragments,int maximum){
        try {
            if(maximum<1||maximum>MAX_BYTES||plan==null||plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null||plan.scope().stageContextJson()==null||ref==null||fragments==null||fragments.size()!=plan.chunks().size())throw invalid();
            if(!ref.equals(FrozenSourceStagePlan.encode(plan,universe).reference()))throw invalid();var byRoot=new HashMap<String,SourceValueFragmentProof>();for(var fragment:fragments)if(fragment==null||byRoot.putIfAbsent(fragment.rootId(),fragment)!=null)throw invalid();
            var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));
            if(partialP1(context))throw invalid();
            var json=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-result-v1").put("run_id",universe.runId()).put("source_id",universe.sourceId()).put("logical_key",plan.logicalKey()).put("generation",plan.generation())
                    .put("business_date",plan.scope().businessDate().toString()).put("cutoff",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(plan.scope().cutoff())).put("settings_hash",plan.scope().settings().semanticHash())
                    .put("accepted",universe.accepted()).put("excluded",universe.excluded()).put("stage_part_count",plan.chunks().size());
            json.set("plan_ref",CanonicalJson.parse(ref.json()));json.set("universe_ref",CanonicalJson.parse(plan.reference().json()));json.set("stage_context",context);
            var decisions=json.putArray("decisions");long eligible=0;for(var d:plan.decisions()){decisions.addObject().put("item_id",d.itemId()).put("reason",d.reason().name());if(d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE)eligible++;}json.put("eligible_count",eligible);
            var parts=json.putArray("fragments");long estimated=CanonicalJson.bytes(json).length;var members=new ArrayList<Fact>();var ordered=new ArrayList<SourceValueFragmentProof>();
            for(var chunk:plan.chunks()){
                var f=byRoot.remove(chunk.rootId());if(f==null||f.generation()!=plan.generation()||f.stagePart()!=chunk.stagePart()||!chunk.inputHash().equals(f.result().inputHash())||!"value".equals(f.result().kind())||!chunk.itemIds().equals(f.members().stream().map(Fact::itemId).toList()))throw invalid();
                var part=JsonMapper.shared().createObjectNode().put("root_id",f.rootId()).put("stage_part",f.stagePart()).put("input_hash",f.result().inputHash()).put("result_hash",f.result().resultHash()).put("handoff_hash",f.handoffHash());part.set("payload",CanonicalJson.parse(f.result().payloadJson()));part.set("members",JsonMapper.shared().valueToTree(f.members()));
                estimated+=CanonicalJson.bytes(part).length+1;if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();parts.add(part);members.addAll(f.members());ordered.add(f);
            }
            if(!byRoot.isEmpty()||members.size()!=eligible)throw invalid();byte[] bytes=CanonicalJson.bytes(json);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json);
            var key=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-key-v1").put("owner_job_id",ref.ownerId()).put("plan_hash",ref.planHash()).put("plan_index_hash",ref.indexHash()).put("result_hash",hash).put("stage","news_value")
                    .put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString()).put("universe_hash",plan.reference().universeHash()).put("universe_index_hash",plan.reference().indexHash()).put("run_id",universe.runId()).put("source_id",universe.sourceId());
            byte[] identity=CanonicalJson.bytes(key);if(identity.length>2048)throw invalid();return new SourceValueStageResult(new String(bytes,StandardCharsets.UTF_8),hash,new String(identity,StandardCharsets.UTF_8),ref,members,plan.decisions(),ordered);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static SourceValueStageResult createResolved(SourceStageResolutionService.Snapshot snapshot){return createResolvedWithinCapacity(snapshot,MAX_BYTES);}
    static SourceValueStageResult createResolvedWithinCapacity(SourceStageResolutionService.Snapshot snapshot,int maximum){
        try {
            if(maximum<1||maximum>MAX_BYTES||snapshot==null||!snapshot.settled())throw invalid();
            var plan=snapshot.plan();var universe=snapshot.universe();var ref=snapshot.reference();
            if(plan.stage()!=GenerationFlowService.Stage.NEWS_VALUE||plan.scope()==null||plan.scope().stageContextJson()==null||!ref.equals(FrozenSourceStagePlan.encode(plan,universe).reference())||snapshot.fragments().size()!=plan.chunks().size())throw invalid();
            var context=CanonicalJson.parse(SourceValueStageContext.readAny(plan.scope().stageContextJson(),plan.scope().businessDate(),plan.scope().cutoff()));boolean inputPartial=partialP1(context);
            var proofs=new ArrayList<SourceValueFragmentProof>();
            for(var resolved:snapshot.fragments())if(resolved.state()==SourceStageResolutionService.State.APPLIED){if(resolved.value()==null||resolved.dedup()!=null)throw invalid();proofs.add(resolved.value().proof());}
            if(!inputPartial&&!snapshot.hasFailures())return createWithinCapacity(plan,universe,ref,proofs,maximum);
            var json=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-result-v2").put("run_id",universe.runId()).put("source_id",universe.sourceId()).put("logical_key",plan.logicalKey()).put("generation",plan.generation())
                .put("business_date",plan.scope().businessDate().toString()).put("cutoff",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(plan.scope().cutoff())).put("settings_hash",plan.scope().settings().semanticHash())
                .put("accepted",universe.accepted()).put("excluded",universe.excluded()).put("stage_part_count",plan.chunks().size()).put("coverage_state","partial");
            json.set("plan_ref",CanonicalJson.parse(ref.json()));json.set("universe_ref",CanonicalJson.parse(plan.reference().json()));json.set("stage_context",context);
            var decisions=json.putArray("decisions");long eligible=0;
            for(var decision:plan.decisions()){decisions.addObject().put("item_id",decision.itemId()).put("reason",decision.reason().name());if(decision.reason()==SourcePartitionPlanner.Reason.ELIGIBLE)eligible++;}
            json.put("eligible_count",eligible);var resolutions=json.putArray("resolutions");var parts=json.putArray("fragments");var missing=json.putArray("missing");
            var omissions=new TreeSet<Omission>(Comparator.comparing(Omission::kind).thenComparing(Omission::id).thenComparing(Omission::reason));
            if(inputPartial){for(var item:context.get("missing"))if(!omissions.add(new Omission(WireValues.text(item.get("kind")),WireValues.text(item.get("id")),WireValues.text(item.get("reason")))))throw invalid();}
            else if("partial".equals(WireValues.text(context.get("coverage_state"))))omissions.add(new Omission("source_run",universe.runId(),"collection_partial"));
            var members=new ArrayList<Fact>();long planned=0,estimated=CanonicalJson.bytes(json).length;
            if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();
            for(int i=0;i<plan.chunks().size();i++){
                var resolved=snapshot.fragments().get(i);var chunk=plan.chunks().get(i);var root=resolved.nativeRoot();
                if(!chunk.equals(resolved.chunk())||root==null||!chunk.rootId().equals(root.id())||chunk.stagePart()!=root.part()||!chunk.inputHash().equals(root.inputHash()))throw invalid();
                planned+=chunk.itemIds().size();var row=JsonMapper.shared().createObjectNode().put("root_id",root.id()).put("stage_part",chunk.stagePart()).put("input_hash",chunk.inputHash()).put("state",root.state()).put("revision",root.revision()).put("call_count",root.calls());
                row.set("item_ids",JsonMapper.shared().valueToTree(chunk.itemIds()));if(root.application()==null)row.putNull("application_state");else row.put("application_state",root.application());
                if(root.resultHash()==null)row.putNull("result_hash");else row.put("result_hash",root.resultHash());if(root.appliedAt()==null)row.putNull("applied_at");else row.put("applied_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(root.appliedAt()));
                estimated+=CanonicalJson.bytes(row).length+1;if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();resolutions.add(row);
                if(resolved.state()==SourceStageResolutionService.State.APPLIED){
                    var applied=resolved.value();var proof=applied.proof();
                    if(!"succeeded".equals(root.state())||!"APPLIED".equals(root.application())||root.calls()<1||root.calls()>3||!Objects.equals(root.appliedAt(),applied.appliedAt())||!Objects.equals(root.resultHash(),proof.result().resultHash())
                        ||!chunk.rootId().equals(proof.rootId())||proof.generation()!=plan.generation()||proof.stagePart()!=chunk.stagePart()||!chunk.inputHash().equals(proof.result().inputHash())||!"value".equals(proof.result().kind())||!chunk.itemIds().equals(proof.members().stream().map(Fact::itemId).toList()))throw invalid();
                    var part=JsonMapper.shared().createObjectNode().put("root_id",proof.rootId()).put("stage_part",proof.stagePart()).put("input_hash",proof.result().inputHash()).put("result_hash",proof.result().resultHash()).put("handoff_hash",proof.handoffHash());part.set("payload",CanonicalJson.parse(proof.result().payloadJson()));part.set("members",JsonMapper.shared().valueToTree(proof.members()));
                    estimated+=CanonicalJson.bytes(part).length+1;if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();parts.add(part);members.addAll(proof.members());
                }else{
                    if(resolved.dedup()!=null||resolved.value()!=null||root.appliedAt()!=null)throw invalid();
                    switch(resolved.state()){
                        case FAILED->{if(!"failed".equals(root.state())||root.calls()!=3||root.application()!=null||root.resultHash()!=null)throw invalid();}
                        case CANCELLED->{if(!"cancelled".equals(root.state())||root.application()!=null||root.resultHash()!=null)throw invalid();}
                        case DISCARDED,SUPERSEDED->{String expected=resolved.state().name();if(!expected.toLowerCase(Locale.ROOT).equals(root.state())||(root.resultHash()==null?root.application()!=null:!expected.equals(root.application())))throw invalid();}
                        default->throw invalid();
                    }
                    if(!omissions.add(new Omission("source_fragment",root.id(),"value_"+resolved.state().name().toLowerCase(Locale.ROOT))))throw invalid();
                }
            }
            if(planned!=eligible||members.size()>eligible)throw invalid();json.put("applied_member_count",members.size()).put("unapplied_member_count",eligible-members.size());
            for(var omission:omissions){var row=JsonMapper.shared().createObjectNode().put("kind",omission.kind()).put("id",omission.id()).put("reason",omission.reason());estimated+=CanonicalJson.bytes(row).length+1;if(estimated>maximum)throw new GenerationFlowService.CapacityGuarded();missing.add(row);}
            if(missing.isEmpty())throw invalid();byte[] bytes=CanonicalJson.bytes(json);if(bytes.length>maximum)throw new GenerationFlowService.CapacityGuarded();String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json);
            var key=JsonMapper.shared().createObjectNode().put("schema_version","source-value-stage-key-v1").put("owner_job_id",ref.ownerId()).put("plan_hash",ref.planHash()).put("plan_index_hash",ref.indexHash()).put("result_hash",hash).put("stage","news_value")
                .put("generation",plan.generation()).put("business_date",plan.scope().businessDate().toString()).put("universe_hash",plan.reference().universeHash()).put("universe_index_hash",plan.reference().indexHash()).put("run_id",universe.runId()).put("source_id",universe.sourceId());
            byte[] identity=CanonicalJson.bytes(key);if(identity.length>2048)throw invalid();return new SourceValueStageResult(new String(bytes,StandardCharsets.UTF_8),hash,new String(identity,StandardCharsets.UTF_8),ref,members,plan.decisions(),proofs);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private record Omission(String kind,String id,String reason){}
    private static boolean partialP1(tools.jackson.databind.JsonNode context){return "source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));}

    private static IllegalStateException invalid(){return new IllegalStateException("complete original source value result unavailable");}
    @Override public String toString(){return "SourceValueStageResult[redacted]";}
}
