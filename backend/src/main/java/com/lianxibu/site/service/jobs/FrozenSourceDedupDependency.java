package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure original P1 reports and conservative availability. A real ledger consumer grants SQL eligibility. */
public final class FrozenSourceDedupDependency {
    public static final String FIELD="source_dedup_dependency_v1";
    private final String json,observationKey;private final Instant observedAt;private final List<GenerationFlowService.Dependency> dependencies;
    private FrozenSourceDedupDependency(String json,String observationKey,Instant observed,List<GenerationFlowService.Dependency> dependencies) {
        this.json=json;this.observationKey=observationKey;this.observedAt=observed;this.dependencies=List.copyOf(dependencies);
    }
    public String json(){return json;}public JsonNode toJson(){return CanonicalJson.parse(json);}public String observationKeyJson(){return observationKey;}
    public Instant observedAt(){return observedAt;}public List<GenerationFlowService.Dependency> dependencies(){return dependencies;}
    public static FrozenSourceDedupDependency create(SourceDedupStageResult original,Instant observed,Instant cutoff){return createWithinCapacity(original,observed,cutoff,2*1024*1024);}
    static FrozenSourceDedupDependency createWithinCapacity(SourceDedupStageResult original,Instant observed,Instant cutoff,int maximum) {
        try {
            if(original==null||observed==null||cutoff==null||observed.getNano()%1000!=0||cutoff.getNano()%1000!=0||observed.isAfter(cutoff)||maximum<1||maximum>2*1024*1024)throw invalid();
            var full=CanonicalJson.parse(original.json());if(observed.isBefore(Instant.parse(full.get("cutoff").asText())))throw invalid();
            String version=WireValues.text(full.get("schema_version"));boolean partial="source-dedup-stage-result-v2".equals(version);if(!partial&&!"source-dedup-stage-result-v1".equals(version))throw invalid();if(partial)validatePartial(full,observed);var node=JsonMapper.shared().createObjectNode().put("schema_version",partial?"source-dedup-dependency-v2":"source-dedup-dependency-v1");node.set("stage_key",CanonicalJson.parse(original.observationKeyJson()));node.set("stage_result",full);
            node.put("observed_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(observed));var reports=node.putArray("reports");var dependencies=new ArrayList<GenerationFlowService.Dependency>();
            long bytes=CanonicalJson.bytes(node).length;
            for(var fragment:full.get("fragments")) {
                var report=JsonMapper.shared().createObjectNode();for(String field:List.of("stage_part","root_id","input_hash","result_hash","handoff_hash"))report.set(field,fragment.get(field).deepCopy());
                report.set("summary",fragment.get("payload").get("summary").deepCopy());bytes+=CanonicalJson.bytes(report).length+1;if(bytes>maximum)throw new GenerationFlowService.CapacityGuarded();reports.add(report);
                dependencies.add(new GenerationFlowService.Dependency(fragment.get("root_id").asText(),GenerationFlowService.Stage.NEWS_DEDUP,"succeeded",observed));
            }
            byte[] canonical=CanonicalJson.bytes(node);if(canonical.length>maximum)throw new GenerationFlowService.CapacityGuarded();
            return new FrozenSourceDedupDependency(new String(canonical,StandardCharsets.UTF_8),original.observationKeyJson(),observed,dependencies);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    public static FrozenSourceDedupDependency read(JsonNode profile,SourceDedupStageResult original,Instant cutoff) {
        try {
            if(profile==null||!profile.isObject()||CanonicalJson.bytes(profile).length>2*1024*1024)throw invalid();
            Instant observed=com.lianxibu.site.domain.protocol.WireValues.utcMicroseconds(profile.get("observed_at"));var expected=create(original,observed,cutoff);
            if(!Arrays.equals(CanonicalJson.bytes(profile),CanonicalJson.bytes(expected.toJson())))throw invalid();return expected;
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Verifies immutable proof bytes; callers must still check the exact committed-observation ledger. */
    public static FrozenSourceDedupDependency read(JsonNode profile,Instant cutoff){return readStructured(profile,cutoff,false);}
    /** Pure partial structure only. A separate qualified SQL consumer must verify real seal and observation. */
    public static FrozenSourceDedupDependency readPartial(JsonNode profile,Instant cutoff){return readStructured(profile,cutoff,true);}
    private static FrozenSourceDedupDependency readStructured(JsonNode profile,Instant cutoff,boolean partial) {
        try {
            var fields=Set.of("schema_version","stage_key","stage_result","observed_at","reports");WireValues.object(profile,fields,fields);
            if(CanonicalJson.bytes(profile).length>2*1024*1024||!(partial?"source-dedup-dependency-v2":"source-dedup-dependency-v1").equals(WireValues.text(profile.get("schema_version")))||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
            var key=profile.get("stage_key");var names=Set.of("schema_version","owner_job_id","plan_hash","plan_index_hash","result_hash","stage","generation","business_date","universe_hash","universe_index_hash","run_id","source_id");WireValues.object(key,names,names);
            if(CanonicalJson.bytes(key).length>2048||!"source-dedup-stage-key-v1".equals(WireValues.text(key.get("schema_version")))||!"news_dedup".equals(WireValues.text(key.get("stage")))||WireValues.nonnegativeInteger(key.get("generation"))<1)throw invalid();
            WireValues.id(key.get("owner_job_id"),false);WireValues.id(key.get("run_id"),false);WireValues.id(key.get("source_id"),true);WireValues.businessDate(key.get("business_date"));
            for(String field:List.of("plan_hash","plan_index_hash","result_hash","universe_hash","universe_index_hash"))WireValues.sha256(key.get(field));
            var full=profile.get("stage_result");var fullFields=new HashSet<>(Set.of("schema_version","run_id","source_id","logical_key","generation","business_date","cutoff","settings_hash","accepted","excluded","stage_part_count","plan_ref","universe_ref","decisions","eligible_count","fragments"));if(partial)fullFields.addAll(Set.of("coverage_state","applied_member_count","unapplied_member_count","resolutions"));WireValues.object(full,fullFields,fullFields);
            if(!(partial?"source-dedup-stage-result-v2":"source-dedup-stage-result-v1").equals(WireValues.text(full.get("schema_version")))||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full).equals(key.get("result_hash").asText()))throw invalid();
            for(String field:List.of("generation","business_date","run_id","source_id"))if(!full.get(field).equals(key.get(field)))throw invalid();
            var plan=FrozenSourceStagePlan.Reference.fromJson(canonical(full.get("plan_ref")));var universe=FrozenSourceUniverse.Reference.fromJson(canonical(full.get("universe_ref")));
            if(!plan.ownerId().equals(key.get("owner_job_id").asText())||!plan.planHash().equals(key.get("plan_hash").asText())||!plan.indexHash().equals(key.get("plan_index_hash").asText())
                    ||!universe.ownerId().equals(plan.ownerId())||!universe.runId().equals(key.get("run_id").asText())||!universe.universeHash().equals(key.get("universe_hash").asText())||!universe.indexHash().equals(key.get("universe_index_hash").asText()))throw invalid();
            Instant observed=WireValues.utcMicroseconds(profile.get("observed_at"));if(observed.isAfter(cutoff)||observed.isBefore(WireValues.utcMicroseconds(full.get("cutoff"))))throw invalid();
            if(partial)validatePartial(full,observed);var fragments=full.get("fragments");if(!fragments.isArray()||fragments.size()>10000||(!partial&&fragments.size()!=WireValues.nonnegativeInteger(full.get("stage_part_count"))))throw invalid();
            var expected=JsonMapper.shared().createArrayNode();var dependencies=new ArrayList<GenerationFlowService.Dependency>();var roots=new HashSet<String>();
            for(int n=0;n<fragments.size();n++) {
                var fragment=fragments.get(n);var fragmentFields=Set.of("stage_part","root_id","input_hash","result_hash","handoff_hash","payload","members");WireValues.object(fragment,fragmentFields,fragmentFields);
                String root=WireValues.id(fragment.get("root_id"),false);if(!roots.add(root)||(!partial&&WireValues.nonnegativeInteger(fragment.get("stage_part"))!=n))throw invalid();
                var report=expected.addObject();for(String field:List.of("stage_part","root_id","input_hash","result_hash","handoff_hash")){if(field.endsWith("hash"))WireValues.sha256(fragment.get(field));report.set(field,fragment.get(field).deepCopy());}
                String summary=WireValues.text(fragment.get("payload").get("summary"));if(summary.codePointCount(0,summary.length())>16384)throw invalid();report.put("summary",summary);
                dependencies.add(new GenerationFlowService.Dependency(root,GenerationFlowService.Stage.NEWS_DEDUP,"succeeded",observed));
            }
            if(!Arrays.equals(CanonicalJson.bytes(expected),CanonicalJson.bytes(profile.get("reports"))))throw invalid();
            return new FrozenSourceDedupDependency(canonical(profile),canonical(key),observed,dependencies);
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static void validatePartial(JsonNode full,Instant observed){
        if(!"partial".equals(WireValues.text(full.get("coverage_state"))))throw invalid();String key=WireValues.text(full.get("logical_key"));if(key.isBlank()||key.getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();WireValues.sha256(full.get("settings_hash"));long generation=WireValues.nonnegativeInteger(full.get("generation")),accepted=WireValues.nonnegativeInteger(full.get("accepted")),excluded=WireValues.nonnegativeInteger(full.get("excluded"));if(accepted+excluded>10000||accepted>10000||excluded>10000)throw invalid();
        var decisions=full.get("decisions");if(!decisions.isArray()||decisions.size()!=accepted+excluded)throw invalid();var eligible=new ArrayList<String>();String last=null;long excludedDecisions=0;
        for(var decision:decisions){var fields=Set.of("item_id","reason");WireValues.object(decision,fields,fields);String id=WireValues.id(decision.get("item_id"),false);if(last!=null&&last.compareTo(id)>=0)throw invalid();last=id;var reason=SourcePartitionPlanner.Reason.valueOf(WireValues.text(decision.get("reason")));if(reason==SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED)throw invalid();if(reason==SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED)excludedDecisions++;if(reason==SourcePartitionPlanner.Reason.ELIGIBLE)eligible.add(id);}
        if(excludedDecisions!=excluded||eligible.size()!=WireValues.nonnegativeInteger(full.get("eligible_count"))||eligible.size()>accepted)throw invalid();
        var resolutions=full.get("resolutions");var fragments=full.get("fragments");long planned=WireValues.nonnegativeInteger(full.get("stage_part_count"));if(!resolutions.isArray()||planned<1||planned>10000||resolutions.size()!=planned||!fragments.isArray())throw invalid();int covered=0,success=0,applied=0,failures=0;
        for(int n=0;n<resolutions.size();n++){
            var row=resolutions.get(n);var fields=Set.of("root_id","stage_part","input_hash","item_ids","state","revision","call_count","application_state","result_hash","applied_at");WireValues.object(row,fields,fields);String root=WireValues.id(row.get("root_id"),false);if(WireValues.nonnegativeInteger(row.get("stage_part"))!=n||!SourcePartitionPlanner.rootId(key,generation,n).equals(root))throw invalid();WireValues.sha256(row.get("input_hash"));WireValues.nonnegativeInteger(row.get("revision"));long calls=WireValues.nonnegativeInteger(row.get("call_count"));if(calls>3)throw invalid();
            var ids=row.get("item_ids");if(!ids.isArray()||ids.isEmpty()||ids.size()>100||covered+ids.size()>eligible.size())throw invalid();var originalIds=new ArrayList<String>();for(var id:ids)originalIds.add(WireValues.id(id,false));if(!originalIds.equals(eligible.subList(covered,covered+ids.size())))throw invalid();covered+=ids.size();String state=WireValues.text(row.get("state"));
            if(state.equals("succeeded")){
                if(calls<1||!"APPLIED".equals(WireValues.text(row.get("application_state")))||row.get("applied_at").isNull()||WireValues.utcMicroseconds(row.get("applied_at")).isAfter(observed)||success>=fragments.size())throw invalid();WireValues.sha256(row.get("result_hash"));var fragment=fragments.get(success++);var fragmentFields=Set.of("stage_part","root_id","input_hash","result_hash","handoff_hash","payload","members");WireValues.object(fragment,fragmentFields,fragmentFields);
                for(String field:List.of("stage_part","root_id","input_hash","result_hash"))if(!fragment.get(field).equals(row.get(field)))throw invalid();WireValues.sha256(fragment.get("handoff_hash"));if(!"dedup".equals(WireValues.text(fragment.get("payload").get("kind"))))throw invalid();var members=fragment.get("members");if(!members.isArray()||members.size()!=ids.size())throw invalid();
                for(int index=0;index<members.size();index++){
                    var member=members.get(index);var memberFields=Set.of("itemId","runId","sourceId","sourceItemId","category","entityId","state","revision","businessDate","targetBusinessDate","generation","activationStage");WireValues.object(member,memberFields,memberFields);
                    if(!originalIds.get(index).equals(WireValues.id(member.get("itemId"),false))||!full.get("run_id").equals(member.get("runId"))||!full.get("source_id").equals(member.get("sourceId"))||!full.get("business_date").equals(member.get("businessDate"))||!full.get("generation").equals(member.get("generation"))||!"news_tech".equals(WireValues.text(member.get("category")))||!"production".equals(WireValues.text(member.get("activationStage")))||WireValues.nonnegativeInteger(member.get("revision"))<2||WireValues.text(member.get("sourceItemId")).getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();
                    if(!member.get("targetBusinessDate").isNull()&&!full.get("business_date").equals(member.get("targetBusinessDate")))throw invalid();String memberState=WireValues.text(member.get("state"));if(!Set.of("ready","needs_review").contains(memberState)||(memberState.equals("needs_review")!=member.get("entityId").isNull()))throw invalid();if(!member.get("entityId").isNull())WireValues.id(member.get("entityId"),false);
                }applied+=members.size();
            }else {
                failures++;if(!row.get("applied_at").isNull())throw invalid();switch(state){
                    case "failed"->{if(calls!=3||!row.get("application_state").isNull()||!row.get("result_hash").isNull())throw invalid();}
                    case "cancelled"->{if(!row.get("application_state").isNull()||!row.get("result_hash").isNull())throw invalid();}
                    case "discarded","superseded"->{if(row.get("result_hash").isNull()){if(!row.get("application_state").isNull())throw invalid();}else {WireValues.sha256(row.get("result_hash"));if(!state.toUpperCase(Locale.ROOT).equals(WireValues.text(row.get("application_state"))))throw invalid();}}
                    default->throw invalid();
                }
            }
        }
        if(failures==0||covered!=eligible.size()||success!=fragments.size()||applied!=WireValues.nonnegativeInteger(full.get("applied_member_count"))||eligible.size()-applied!=WireValues.nonnegativeInteger(full.get("unapplied_member_count")))throw invalid();
    }

    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    @Override public String toString(){return "FrozenSourceDedupDependency[redacted]";}
    private static IllegalStateException invalid(){return new IllegalStateException("original complete source dedup dependency unavailable");}
}
