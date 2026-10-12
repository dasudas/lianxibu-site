package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure, complete planning. Decisions need a separate trusted eligibility provider. */
public final class SourcePartitionPlanner {
    public static final String FIELD="source_partition_v1";
    private static final long MAX_PLAN_BYTES=64L*1024*1024;
    public enum Reason { ELIGIBLE,COLLECTOR_EXCLUDED,NOT_AI_ELIGIBLE,ALREADY_APPLIED,PREFERENCE_BLOCKED }
    public record Decision(String itemId,Reason reason) {
        public Decision {WireValues.id(itemId,false);Objects.requireNonNull(reason);}
    }
    @FunctionalInterface public interface SnapshotBuilder {
        /** Build only from frozen facts; no provider calls or latest-data substitution.
         * For a fixed part/root, extending an ordered prefix must retain every existing
         * frozen field unchanged, so complete input/request byte budgets are monotone. */
        String build(List<String> itemIds,long stagePart,String rootId);
    }
    public record Chunk(long stagePart,String rootId,List<String> itemIds,String snapshotJson,String inputHash,String requestJson) {
        public Chunk {itemIds=List.copyOf(itemIds);}
        @Override public String toString(){return "SourceStageChunk[redacted]";}
    }
    public record Scope(LocalDate businessDate,Instant cutoff,FrozenGenerationSettings settings,String stageContextJson) {
        public Scope {if(businessDate==null||cutoff==null||settings==null||cutoff.getNano()%1000!=0
                ||cutoff.isBefore(businessDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();
            if(stageContextJson!=null)SourceValueStageContext.readAny(stageContextJson,businessDate,cutoff);
        }
        public Scope(LocalDate businessDate,Instant cutoff,FrozenGenerationSettings settings){this(businessDate,cutoff,settings,null);}
        @Override public String toString(){return "SourceStageScope[redacted]";}
    }
    public record Plan(Stage stage,String logicalKey,long generation,FrozenSourceUniverse.Reference reference,List<Decision> decisions,List<Chunk> chunks,Scope scope) {
        public Plan {decisions=List.copyOf(decisions);chunks=List.copyOf(chunks);}
        public Plan(Stage stage,String key,long generation,FrozenSourceUniverse.Reference reference,List<Decision> decisions,List<Chunk> chunks) {
            this(stage,key,generation,reference,decisions,chunks,inferScope(chunks));
        }
        @Override public String toString(){return "SourcePartitionPlan[redacted]";}
    }
    private static Scope inferScope(List<Chunk> chunks) {
        if(chunks==null||chunks.isEmpty())return null;
        var n=CanonicalJson.parse(chunks.getFirst().snapshotJson());return new Scope(LocalDate.parse(WireValues.text(n.get("business_date"))),Instant.parse(WireValues.text(n.get("cutoff"))),FrozenGenerationSettings.read(n.get(FrozenGenerationSettings.FIELD)),n.has(FrozenSourceDedupDependency.FIELD)?valueContext(n):null);
    }
    public Plan plan(Stage stage,String key,long generation,MemberUniverse.Universe universe,FrozenSourceUniverse.Reference reference,
                     List<Decision> decisions,Scope scope,SnapshotBuilder snapshots) {
        Objects.requireNonNull(scope);var result=plan(stage,key,generation,universe,reference,decisions,snapshots);
        var complete=new Plan(stage,key,generation,reference,result.decisions(),result.chunks(),scope);validate(complete,universe);return complete;
    }
    public Plan plan(Stage stage,String logicalKey,long generation,MemberUniverse.Universe universe,
                     FrozenSourceUniverse.Reference reference,List<Decision> decisions,SnapshotBuilder snapshots) {
        try {
            if(stage==null||!(stage.dedup()||stage==Stage.NEWS_VALUE)||logicalKey==null||logicalKey.isBlank()||logicalKey.length()>4096
                    ||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||universe==null||reference==null||snapshots==null
                    ||decisions==null||decisions.size()!=universe.members().size())throw invalid();
            if(!reference.equals(FrozenSourceUniverse.encode(reference.ownerId(),universe).reference()))throw invalid();
            var supplied=new HashMap<String,Decision>();for(var decision:decisions)if(decision==null||supplied.putIfAbsent(decision.itemId(),decision)!=null)throw invalid();
            var complete=new TreeMap<String,Decision>();
            for(var member:universe.members()) {
                var decision=supplied.remove(member.itemId());
                if(decision==null||"excluded".equals(member.terminalState())!=(decision.reason()==Reason.COLLECTOR_EXCLUDED)
                        ||stage.dedup()&&decision.reason()==Reason.PREFERENCE_BLOCKED)throw invalid();
                complete.put(member.itemId(),decision);
            }
            if(!supplied.isEmpty())throw invalid();
            var eligible=complete.values().stream().filter(d->d.reason()==Reason.ELIGIBLE).map(Decision::itemId).toList();
            var chunks=new ArrayList<Chunk>();long plannedBytes=0;
            for(int covered=0;covered<eligible.size();) {
                int maximum=Math.min(100,eligible.size()-covered);
                Chunk chosen=candidate(stage,logicalKey,generation,reference,eligible.subList(covered,covered+maximum),chunks.size(),snapshots);
                if(chosen==null) {
                    int low=1,high=maximum-1;
                    while(low<=high) {
                        int middle=low+(high-low)/2;
                        var trial=candidate(stage,logicalKey,generation,reference,eligible.subList(covered,covered+middle),chunks.size(),snapshots);
                        if(trial==null)high=middle-1;
                        else {chosen=trial;low=middle+1;}
                    }
                    if(chosen==null)throw new GenerationFlowService.CapacityGuarded();
                }
                plannedBytes=add(chunks,chosen,plannedBytes);covered+=chosen.itemIds().size();
            }
            return new Plan(stage,logicalKey,generation,reference,new ArrayList<>(complete.values()),chunks);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw invalid();}
    }
    /** Verify an archived complete plan without selecting new facts or rebuilding partitions. */
    public void validate(Plan plan,MemberUniverse.Universe universe) {
        try {
            if(plan==null||universe==null||plan.stage()==null||!(plan.stage().dedup()||plan.stage()==Stage.NEWS_VALUE)
                    ||plan.logicalKey()==null||plan.logicalKey().isBlank()||plan.logicalKey().length()>4096
                    ||plan.generation()<1||plan.generation()>CanonicalJson.MAX_SAFE_INTEGER||plan.reference()==null
                    ||plan.decisions().size()!=universe.members().size()||plan.chunks().size()>MemberUniverse.MAX_MEMBERS
                    ||!plan.reference().equals(FrozenSourceUniverse.encode(plan.reference().ownerId(),universe).reference()))throw invalid();
            var failedP1=new HashSet<String>();
            if(plan.scope()!=null&&plan.scope().stageContextJson()!=null) {
                if(plan.stage()!=Stage.NEWS_VALUE)throw invalid();var context=CanonicalJson.parse(plan.scope().stageContextJson());boolean partial="source-dedup-dependency-v2".equals(WireValues.text(context.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));var dependency=partial?FrozenSourceDedupDependency.readPartial(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff()):FrozenSourceDedupDependency.read(context.get(FrozenSourceDedupDependency.FIELD),plan.scope().cutoff());
                if(partial)for(var resolution:dependency.toJson().get("stage_result").get("resolutions"))if(!"succeeded".equals(WireValues.text(resolution.get("state"))))for(var id:resolution.get("item_ids"))if(!failedP1.add(WireValues.id(id,false)))throw invalid();
                var previous=FrozenSourceUniverse.Reference.fromJson(canonical(dependency.toJson().get("stage_result").get("universe_ref")));
                if(!previous.equals(FrozenSourceUniverse.encode(previous.ownerId(),universe).reference())||!plan.logicalKey().equals(NewsValueSourcePlanBuilder.logicalKey(dependency)))throw invalid();
            }
            var originals=new HashMap<String,MemberUniverse.Member>();universe.members().forEach(m->originals.put(m.itemId(),m));
            var eligible=new ArrayList<String>();String last=null;
            for(var decision:plan.decisions()) {
                if(decision==null||last!=null&&last.compareTo(decision.itemId())>=0)throw invalid();last=decision.itemId();
                var member=originals.remove(decision.itemId());
                if(member==null||"excluded".equals(member.terminalState())!=(decision.reason()==Reason.COLLECTOR_EXCLUDED)
                        ||plan.stage().dedup()&&decision.reason()==Reason.PREFERENCE_BLOCKED)throw invalid();
                if(failedP1.contains(decision.itemId())&&decision.reason()!=Reason.NOT_AI_ELIGIBLE)throw invalid();
                if(decision.reason()==Reason.ELIGIBLE)eligible.add(decision.itemId());
            }
            if(!originals.isEmpty())throw invalid();int covered=0;long bytes=0;tools.jackson.databind.JsonNode common=null;
            var checked=new ArrayList<Chunk>();
            for(int n=0;n<plan.chunks().size();n++) {
                var chunk=plan.chunks().get(n);
                if(chunk==null||chunk.stagePart()!=n||chunk.itemIds().isEmpty()||chunk.itemIds().size()>100
                        ||covered+chunk.itemIds().size()>eligible.size()
                        ||!chunk.itemIds().equals(eligible.subList(covered,covered+chunk.itemIds().size()))
                        ||!rootId(plan.logicalKey(),plan.generation(),n).equals(chunk.rootId()))throw invalid();
                var actual=candidate(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),chunk.itemIds(),n,(ids,p,root)->chunk.snapshotJson());
                if(actual==null||!actual.equals(chunk))throw invalid();
                var snapshot=CanonicalJson.parse(chunk.snapshotJson());
                if(plan.scope()==null||!plan.scope().equals(inferScope(List.of(chunk))))throw invalid();
                if(common!=null)for(String field:List.of("business_date","cutoff","settings_hash",FrozenGenerationSettings.FIELD,
                        FrozenCollectionManifest.FIELD,FrozenDependencyManifest.FIELD,"coverage_state","missing","source_id"))
                    if(!Objects.equals(common.get(field),snapshot.get(field)))throw invalid();
                common=snapshot;covered+=chunk.itemIds().size();bytes=add(checked,chunk,bytes);
            }
            if(covered!=eligible.size())throw invalid();
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw invalid();}
    }
    private static long add(List<Chunk> chunks,Chunk chunk,long total) {
        long next=total+chunk.snapshotJson().getBytes(StandardCharsets.UTF_8).length+chunk.requestJson().getBytes(StandardCharsets.UTF_8).length;
        if(next>MAX_PLAN_BYTES)throw new GenerationFlowService.CapacityGuarded();chunks.add(chunk);return next;
    }
    private Chunk candidate(Stage stage,String key,long generation,FrozenSourceUniverse.Reference reference,List<String> ids,long number,SnapshotBuilder snapshots) {
        String root=rootId(key,generation,number);String raw=snapshots.build(List.copyOf(ids),number,root);
        if(raw==null)throw invalid();
        if(raw.getBytes(StandardCharsets.UTF_8).length>2*1048576)return null;
        var snapshot=CanonicalJson.parse(raw);String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
        if(!key.equals(WireValues.text(snapshot.get("logical_key")))||generation!=WireValues.nonnegativeInteger(snapshot.get("generation")))throw invalid();
        if(snapshot.has("stage_part")?WireValues.nonnegativeInteger(snapshot.get("stage_part"))!=number:number!=0)throw invalid();
        var partition=snapshot.get(FIELD);var fields=Set.of("schema_version","stage_part","universe_ref");WireValues.object(partition,fields,fields);
        if(!"source-partition-v1".equals(WireValues.text(partition.get("schema_version")))||number!=WireValues.nonnegativeInteger(partition.get("stage_part"))
                ||!reference.equals(FrozenSourceUniverse.Reference.fromJson(canonical(partition.get("universe_ref")))))throw invalid();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        var input=context.input(root,"source-planning",hash,0);Set<String> expected=Set.copyOf(ids);
        if(context.stage()!=stage||context.generation()!=generation||!context.referencesComplete()||!expected.equals(input.itemIds()))throw invalid();
        var material=snapshot.get("members");if(material==null||!material.isArray()||material.size()!=ids.size())throw invalid();
        var actual=new ArrayList<String>();
        for(var item:material) {
            actual.add(WireValues.id(item.get("id"),false));
            if(item.get("body")==null||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,item.get("body")).equals(WireValues.text(item.get("body_hash"))))throw invalid();
        }
        if(!actual.equals(ids)||!FrozenMemberBindings.read(snapshot,input).keySet().equals(expected))throw invalid();
        String frozen=canonical(snapshot);var request=new GenerationRequestBuilder().buildWithinBudget(stage,frozen,hash);
        return request.isEmpty()?null:new Chunk(number,root,ids,frozen,hash,request.get());
    }
    public static String rootId(String key,long generation,long number) {
        if(key==null||key.isBlank()||key.length()>4096||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||number<0||number>=MemberUniverse.MAX_MEMBERS)throw invalid();
        var node=JsonMapper.shared().createObjectNode();node.put("scope","source-stage-root-v1");node.put("logical_key",key);node.put("generation",generation);node.put("stage_part",number);
        return CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node);
    }
    private static String valueContext(tools.jackson.databind.JsonNode snapshot){
        if(snapshot.has(FrozenSourceDayDedupDependency.FIELD))return SourceValueStageContext.fromDaySnapshot(snapshot);
        return "source-dedup-dependency-v2".equals(WireValues.text(snapshot.get(FrozenSourceDedupDependency.FIELD).get("schema_version")))?SourceValueStageContext.fromPartialSnapshot(snapshot):SourceValueStageContext.fromSnapshot(snapshot);
    }
    private static String canonical(tools.jackson.databind.JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static GenerationFlowService.InputRejected invalid(){return new GenerationFlowService.InputRejected();}
}
