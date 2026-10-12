package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure complete P2 assembly. The runtime must provide actual qualification, dependency and coverage proofs. */
public final class NewsValueSourcePlanBuilder {
    private final String valueResultVersion;
    public NewsValueSourcePlanBuilder(){this("1");}
    public NewsValueSourcePlanBuilder(String version){if(version==null||!Set.of("1","2").contains(version))throw invalid();valueResultVersion=version;}
    public static String logicalKey(FrozenSourceDedupDependency dependency) {
        var full=dependency.toJson().get("stage_result");var key=JsonMapper.shared().createObjectNode();
        for(String field:List.of("source_id","business_date"))key.set(field,full.get(field));key.set("p1_logical_key",full.get("logical_key"));
        return "news-value:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,key);
    }
    public SourcePartitionPlanner.Plan build(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,NewsValueEligibilityService.Selection selection,
            FrozenGenerationSettings settings,FrozenSourceDedupDependency dependency,long generation){return build(original,reference,selection,settings,dependency,generation,false);}
    public SourcePartitionPlanner.Plan build(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,NewsValueEligibilityService.Selection selection,
            FrozenGenerationSettings settings,FrozenSourceDedupDependency dependency,long generation,boolean partial) {
        return buildInternal(original,reference,selection,settings,dependency,null,generation,partial);
    }
    public SourcePartitionPlanner.Plan buildDay(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,NewsValueEligibilityService.Selection selection,
            FrozenGenerationSettings settings,FrozenSourceDedupDependency dependency,FrozenSourceDayDedupDependency dayDependency,long generation,boolean partial){
        return buildInternal(original,reference,selection,settings,dependency,Objects.requireNonNull(dayDependency),generation,partial);
    }
    private SourcePartitionPlanner.Plan buildInternal(MemberUniverse.Universe original,FrozenSourceUniverse.Reference reference,NewsValueEligibilityService.Selection selection,
            FrozenGenerationSettings settings,FrozenSourceDedupDependency dependency,FrozenSourceDayDedupDependency dayDependency,long generation,boolean partial){
        try {
            Objects.requireNonNull(original);Objects.requireNonNull(reference);Objects.requireNonNull(selection);Objects.requireNonNull(settings);
            boolean dedupPartial="source-dedup-dependency-v2".equals(WireValues.text(dependency.toJson().get("schema_version")));var frozen=dedupPartial?FrozenSourceDedupDependency.readPartial(dependency.toJson(),selection.cutoff()):FrozenSourceDedupDependency.read(dependency.toJson(),selection.cutoff());var full=frozen.toJson().get("stage_result");String key=logicalKey(frozen);var failed=new HashSet<String>();if(dedupPartial)for(var resolution:full.get("resolutions"))if(!"succeeded".equals(resolution.get("state").asText()))for(var id:resolution.get("item_ids"))failed.add(WireValues.id(id,false));
            var priorRef=FrozenSourceUniverse.Reference.fromJson(canonical(full.get("universe_ref")));
            if(generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER||!reference.ownerId().equals(JdbcFrozenInputs.stableId("intent",key+":"+generation))
                    ||!reference.equals(FrozenSourceUniverse.encode(reference.ownerId(),original).reference())||!priorRef.equals(FrozenSourceUniverse.encode(priorRef.ownerId(),original).reference())
                    ||!original.runId().equals(selection.runId())||!original.sourceId().equals(selection.sourceId())||!selection.day().toString().equals(full.get("business_date").asText())
                    ||selection.entries().size()!=original.members().size())throw invalid();
            var prior=priorDecisions(full);var entries=new HashMap<String,NewsValueEligibilityService.Entry>();for(var e:selection.entries())if(e==null||entries.putIfAbsent(e.itemId(),e)!=null)throw invalid();
            var decisions=new ArrayList<SourcePartitionPlanner.Decision>();var eligible=new TreeMap<String,NewsValueEligibilityService.Entry>();
            for(var member:original.members()) {
                var entry=entries.remove(member.itemId());if(entry==null||member.terminalState().equals("excluded")!=(entry.reason()==NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED))throw invalid();
                var reason=SourcePartitionPlanner.Reason.valueOf(entry.reason().name());if(failed.contains(member.itemId()))reason=SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE;
                if(reason==SourcePartitionPlanner.Reason.ELIGIBLE) {
                    verify(entry,selection);var previous=prior.get(member.itemId());if(previous==null)throw invalid();
                    if(!usable(previous,entry))reason=SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE;
                    else if(NewsPreferenceFilter.blocked(original.sourceId(),entry.fact(),settings.blockedKeywords()))reason=SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED;
                    else eligible.put(entry.itemId(),entry);
                }
                decisions.add(new SourcePartitionPlanner.Decision(member.itemId(),reason));
            }
            if(!entries.isEmpty())throw invalid();var day=dayDependency==null?null:FrozenSourceDayDedupDependency.read(dayDependency.json(),frozen,selection.cutoff());
            var scope=new SourcePartitionPlanner.Scope(selection.day(),selection.cutoff(),settings,day==null?SourceValueStageContext.create(frozen,selection.day(),selection.cutoff(),partial):SourceValueStageContext.createDay(frozen,day,selection.day(),selection.cutoff(),partial));
            return new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_VALUE,key,generation,original,reference,decisions,scope,
                    (ids,part,root)->snapshot(key,generation,original,reference,scope,frozen,day,eligible,ids,part,root,partial,valueResultVersion));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    static Map<String,JsonNode> priorDecisions(JsonNode full) {
        var result=new HashMap<String,JsonNode>();var json=JsonMapper.shared();
        for(var d:full.get("decisions")){String id=WireValues.id(d.get("item_id"),false);if(result.putIfAbsent(id,d.deepCopy())!=null)throw invalid();}
        for(var fragment:full.get("fragments"))for(var fact:fragment.get("members")) {
            String id=WireValues.id(fact.get("itemId"),false);var d=result.get(id);if(d==null||!"ELIGIBLE".equals(d.get("reason").asText()))throw invalid();
            var enriched=json.createObjectNode();enriched.set("item_id",d.get("item_id"));enriched.set("reason",d.get("reason"));enriched.set("fact",fact);result.put(id,enriched);
        }
        for(var fragment:full.get("fragments"))for(var judgment:fragment.get("payload").get("items")) {
            String id=WireValues.id(judgment.get("item_id"),false);var previous=result.get(id);if(previous==null||!previous.has("fact")||previous.has("judgment"))throw invalid();((tools.jackson.databind.node.ObjectNode)previous).set("judgment",judgment);
        }
        return result;
    }
    static boolean usable(JsonNode prior,NewsValueEligibilityService.Entry entry) {
        if("ALREADY_APPLIED".equals(prior.get("reason").asText()))return true;
        if(!"ELIGIBLE".equals(prior.get("reason").asText()))return false;var fact=prior.get("fact");if(fact==null)throw invalid();
        return "ready".equals(fact.get("state").asText())&&entry.entity().id().equals(fact.get("entityId").asText())&&entry.fact().revision()>=WireValues.nonnegativeInteger(fact.get("revision"));
    }
    private static void verify(NewsValueEligibilityService.Entry entry,NewsValueEligibilityService.Selection selection) {
        var entity=entry.entity();var fact=entry.fact();var material=NewsValueMaterial.from(selection.sourceId(),fact);WireValues.id(entity.id(),false);
        if(!selection.day().equals(entry.businessDate())||entry.completedAt().isAfter(selection.cutoff())||!selection.cutoff().equals(entity.metadataAsOf())||entity.revision()<0||entity.revision()>=CanonicalJson.MAX_SAFE_INTEGER
                ||!"news_tech".equals(fact.category())||!entity.legacy()&&entity.firstSeenBusinessDate()==null||entity.firstSeenBusinessDate()!=null&&entity.firstSeenBusinessDate().isAfter(selection.cutoff().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
                ||entry.eligibleHighValue()!=(selection.day().equals(entity.firstSeenBusinessDate())&&!entity.legacy()&&!"missing".equals(material.inputBasis())))throw invalid();
    }
    private static String snapshot(String key,long generation,MemberUniverse.Universe universe,FrozenSourceUniverse.Reference reference,SourcePartitionPlanner.Scope scope,
            FrozenSourceDedupDependency dependency,FrozenSourceDayDedupDependency day,Map<String,NewsValueEligibilityService.Entry> eligible,List<String> ids,long part,String root,boolean partial,String valueResultVersion) {
        var json=JsonMapper.shared();var node=json.createObjectNode().put("schema_version","1").put("logical_key",key).put("generation",generation).put("stage","news_value");if(part!=0)node.put("stage_part",part);
        node.put("business_date",scope.businessDate().toString()).put("cutoff",scope.cutoff().toString()).put("source_id",universe.sourceId()).put("settings_hash",scope.settings().semanticHash());node.set(FrozenGenerationSettings.FIELD,scope.settings().toJson());
        boolean dedupPartial="source-dedup-dependency-v2".equals(dependency.toJson().get("schema_version").asText());node.put("coverage_state",partial||dedupPartial?"partial":"complete");var missing=node.putArray("missing");if(dedupPartial)SourceValueStageContext.partialSnapshotMissing(scope.stageContextJson(),scope.businessDate(),scope.cutoff()).forEach(missing::add);else if(partial)missing.add("collection:"+universe.runId());
        var dependencies=dependency.dependencies();node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(dependencies,dependencies.stream().map(GenerationFlowService.Dependency::id).toList()));node.set(FrozenSourceDedupDependency.FIELD,dependency.toJson());
        if(day!=null)node.set(FrozenSourceDayDedupDependency.FIELD,day.toJson());
        var context=json.createObjectNode().put("schema_version","frozen-application-context-v1").put("stage","news_value").put("generation",generation);
        if("2".equals(valueResultVersion))context.put("value_result_version","2");
        for(String field:List.of("target_content","target_entity_id","period_start","period_end"))context.putNull(field);var items=context.putArray("item_ids");var candidates=context.putArray("candidates_by_item");var high=context.putArray("eligible_high_value_item_ids");
        for(String field:List.of("allowed_citation_ids","version_eligible_item_ids","reference_targets"))context.putArray(field);context.put("references_complete",true);
        var members=node.putArray("members");var bindings=node.putObject(FrozenMemberBindings.FIELD).put("schema_version","frozen-member-bindings-v1");var mapping=bindings.putArray("members");
        var qualification=node.putObject(NewsValueInputProfile.ELIGIBILITY_FIELD).put("schema_version","news-value-eligibility-v1").put("source_id",universe.sourceId()).put("business_date",scope.businessDate().toString()).put("metadata_as_of",scope.cutoff().toString());var metadata=qualification.putArray("members");
        var facts=new ArrayList<SourceMemberEvidenceService.Fact>();var micros=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
        for(String id:ids) {
            var entry=eligible.get(id);if(entry==null)throw invalid();boolean eligibleHigh=entry.eligibleHighValue()&&(day==null||day.originalBatchEligible());var fact=entry.fact();var body=CanonicalJson.parse(fact.payloadJson());facts.add(fact);items.add(id);candidates.addObject().put("item_id",id).putArray("entity_ids").add(entry.entity().id());if(eligibleHigh)high.add(id);
            var member=members.addObject().put("id",id).put("kind","source_member").put("business_date",entry.businessDate().toString()).put("completed_at",micros.format(entry.completedAt())).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));member.set("body",body);member.putArray("references");
            var binding=mapping.addObject().put("item_id",id).put("expected_revision",fact.revision()).put("category",fact.category()).put("identity_key",fact.identityKey()).put("title",fact.title());if(fact.summary()==null)binding.putNull("summary");else binding.put("summary",fact.summary());binding.put("new_entity_id",FrozenMemberBindings.entityId(root,id,fact.category(),fact.identityKey()));
            var current=metadata.addObject().put("item_id",id).put("entity_id",entry.entity().id()).put("entity_revision",entry.entity().revision()).put("legacy",entry.entity().legacy()).put("eligible_high_value",eligibleHigh);if(entry.entity().firstSeenBusinessDate()==null)current.putNull("first_seen_business_date");else current.put("first_seen_business_date",entry.entity().firstSeenBusinessDate().toString());
        }
        node.set(FrozenApplicationContext.FIELD,FrozenApplicationContext.fromJson(context).toJson());node.set(NewsValueInputProfile.FIELD,NewsValueInputProfile.create(universe.sourceId(),facts));var partition=node.putObject(SourcePartitionPlanner.FIELD).put("schema_version","source-partition-v1").put("stage_part",part);partition.set("universe_ref",CanonicalJson.parse(reference.json()));
        NewsValueInputProfile.complete(node);String snapshot=canonical(node);if(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1048576)throw new GenerationFlowService.CapacityGuarded();return snapshot;
    }
    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original news value plan unavailable");}
}
