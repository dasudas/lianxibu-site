package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.NewsValueMaterial;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verified material projection of the original P2 input; never grants SQL qualification. */
public final class NewsValueInputProfile {
    public static final String FIELD="news_value_input_v1";
    public static final String ELIGIBILITY_FIELD="news_value_eligibility_v1";
    private static final String VERSION="news-value-input-v1";
    private static final Set<String> PROFILE=Set.of("schema_version","source_id","materials");
    private static final Set<String> TOP=Set.of("schema_version","logical_key","generation","stage","stage_part","business_date","cutoff","source_id","settings_hash",FrozenGenerationSettings.FIELD,"coverage_state","missing",FrozenDependencyManifest.FIELD,FrozenApplicationContext.FIELD,FrozenMemberBindings.FIELD,SourcePartitionPlanner.FIELD,FIELD,"members",FrozenSourceDedupDependency.FIELD,FrozenSourceDayDedupDependency.FIELD,ELIGIBILITY_FIELD);
    private NewsValueInputProfile(){ }
    public static JsonNode create(String sourceId,List<SourceMemberEvidenceService.Fact> facts) {
        try {
            WireValues.id(sourceId,true);if(facts==null||facts.isEmpty()||facts.size()>100)throw invalid();
            var sorted=new TreeMap<String,NewsValueMaterial>();for(var fact:facts){var material=NewsValueMaterial.from(sourceId,fact);if(sorted.putIfAbsent(material.itemId(),material)!=null)throw invalid();}
            var node=JsonMapper.shared().createObjectNode();node.put("schema_version",VERSION);node.put("source_id",sourceId);var materials=node.putArray("materials");sorted.values().forEach(material->materials.add(material.toJson()));bounded(node);return node;
        }catch(RuntimeException rejected){throw invalid();}
    }
    public static Map<String,NewsValueMaterial> validated(JsonNode snapshot) {
        try {
            WireValues.object(snapshot,TOP,Set.of("schema_version","logical_key","generation","stage","source_id",FrozenApplicationContext.FIELD,FrozenMemberBindings.FIELD,SourcePartitionPlanner.FIELD,FIELD,"members"));
            if(!"1".equals(WireValues.text(snapshot.get("schema_version")))||!"news_value".equals(WireValues.text(snapshot.get("stage"))))throw invalid();
            var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));long generation=WireValues.nonnegativeInteger(snapshot.get("generation"));
            if(context.stage()!=GenerationFlowService.Stage.NEWS_VALUE||context.generation()!=generation)throw invalid();
            var partition=snapshot.get(SourcePartitionPlanner.FIELD);var fields=Set.of("schema_version","stage_part","universe_ref");WireValues.object(partition,fields,fields);
            long part=WireValues.nonnegativeInteger(partition.get("stage_part"));
            if(part>=10000||!"source-partition-v1".equals(WireValues.text(partition.get("schema_version")))||(snapshot.has("stage_part")?WireValues.nonnegativeInteger(snapshot.get("stage_part")):0)!=part)throw invalid();
            FrozenSourceUniverse.Reference.fromJson(JdbcFrozenInputs.canonical(partition.get("universe_ref")));
            String root=SourcePartitionPlanner.rootId(WireValues.text(snapshot.get("logical_key")),generation,part);
            var bindings=FrozenMemberBindings.read(snapshot,context.input(root,"value-material-check",CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),0));
            var profile=snapshot.get(FIELD);WireValues.object(profile,PROFILE,PROFILE);bounded(profile);
            String sourceId=WireValues.id(profile.get("source_id"),true);if(!VERSION.equals(WireValues.text(profile.get("schema_version")))||!sourceId.equals(WireValues.id(snapshot.get("source_id"),true)))throw invalid();
            var materials=profile.get("materials");if(!materials.isArray()||materials.isEmpty()||materials.size()>100)throw invalid();
            var selected=new TreeMap<String,NewsValueMaterial>();var order=new ArrayList<String>();for(var item:materials){var material=NewsValueMaterial.read(item);if(selected.putIfAbsent(material.itemId(),material)!=null)throw invalid();order.add(material.itemId());}
            if(!order.equals(new ArrayList<>(selected.keySet()))||!selected.keySet().equals(bindings.keySet()))throw invalid();
            var members=snapshot.get("members");if(!members.isArray()||members.size()!=selected.size())throw invalid();var seen=new HashSet<String>();var originalOrder=new ArrayList<String>();
            for(var item:members){var memberFields=Set.of("id","kind","business_date","completed_at","body_hash","body","references");WireValues.object(item,memberFields,memberFields);
                String id=WireValues.id(item.get("id"),false);var binding=bindings.get(id);if(binding==null||!seen.add(id)||!"source_member".equals(WireValues.text(item.get("kind")))||!"news_tech".equals(binding.category()))throw invalid();originalOrder.add(id);
                var body=item.get("body");String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body);if(!hash.equals(WireValues.sha256(item.get("body_hash"))))throw invalid();
                var fact=new SourceMemberEvidenceService.Fact(id,binding.expectedRevision(),binding.category(),binding.identityKey(),binding.title(),binding.summary(),JdbcFrozenInputs.canonical(body));
                if(!NewsValueMaterial.from(sourceId,fact).equals(selected.get(id)))throw invalid();
            }
            if(!originalOrder.equals(order))throw invalid();
            if(snapshot.has(FrozenSourceDayDedupDependency.FIELD)&&(!snapshot.has(FrozenSourceDedupDependency.FIELD)||!snapshot.has(ELIGIBILITY_FIELD)))throw invalid();
            if(snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(ELIGIBILITY_FIELD))qualification(snapshot,bindings,selected,context);
            return Collections.unmodifiableMap(selected);
        }catch(RuntimeException rejected){throw invalid();}
    }
    public static JsonNode modelView(JsonNode snapshot) {
        validated(snapshot);var view=((ObjectNode)snapshot).deepCopy();view.remove(FrozenSourceDayDedupDependency.FIELD);for(var member:view.get("members"))((ObjectNode)member).remove("body");
        if(snapshot.has(FrozenSourceDedupDependency.FIELD)) {
            var proof=snapshot.get(FrozenSourceDedupDependency.FIELD);var small=((ObjectNode)proof).deepCopy();small.remove("stage_result");small.remove("reports");view.set(FrozenSourceDedupDependency.FIELD,small);
            var previous=NewsValueSourcePlanBuilder.priorDecisions(proof.get("stage_result"));var report=view.putObject("p1_qualified_report_v1").put("schema_version","p1-qualified-report-v1");var items=report.putArray("items");
            for(var entity:snapshot.get(ELIGIBILITY_FIELD).get("members")){String id=entity.get("item_id").asText();var prior=previous.get(id);var item=items.addObject().put("item_id",id).put("source_decision",prior.get("reason").asText()).put("state","ready").put("entity_id",entity.get("entity_id").asText());if(prior.has("judgment"))item.set("dedup_decision",prior.get("judgment").get("decision"));else item.putNull("dedup_decision");}
        }
        view.put("model_input_view","news-value-material-view-v1");return view;
    }
    public static Map<String,NewsValueMaterial> complete(JsonNode snapshot) {
        if(snapshot==null||!snapshot.has(FrozenSourceDedupDependency.FIELD)||!snapshot.has(ELIGIBILITY_FIELD))throw invalid();return validated(snapshot);
    }
    private static void qualification(JsonNode snapshot,Map<String,FrozenMemberBindings.Member> bindings,Map<String,NewsValueMaterial> materials,FrozenApplicationContext context) {
        var cutoff=java.time.Instant.parse(WireValues.text(snapshot.get("cutoff")));boolean partial="source-dedup-dependency-v2".equals(WireValues.text(snapshot.get(FrozenSourceDedupDependency.FIELD).get("schema_version")));var dependency=partial?FrozenSourceDedupDependency.readPartial(snapshot.get(FrozenSourceDedupDependency.FIELD),cutoff):FrozenSourceDedupDependency.read(snapshot.get(FrozenSourceDedupDependency.FIELD),cutoff);if(partial)SourceValueStageContext.fromPartialSnapshot(snapshot);var full=dependency.toJson().get("stage_result");var key=CanonicalJson.parse(dependency.observationKeyJson());
        boolean batch=true;if(snapshot.has(FrozenSourceDayDedupDependency.FIELD)){SourceValueStageContext.fromDaySnapshot(snapshot);batch=FrozenSourceDayDedupDependency.read(JdbcFrozenInputs.canonical(snapshot.get(FrozenSourceDayDedupDependency.FIELD)),dependency,cutoff).originalBatchEligible();}
        if(!snapshot.get("source_id").equals(key.get("source_id"))||!snapshot.get("business_date").equals(key.get("business_date")))throw invalid();
        var expected=FrozenDependencyManifest.create(dependency.dependencies(),dependency.dependencies().stream().map(GenerationFlowService.Dependency::id).toList());if(!Arrays.equals(CanonicalJson.bytes(expected),CanonicalJson.bytes(snapshot.get(FrozenDependencyManifest.FIELD))))throw invalid();
        var profile=snapshot.get(ELIGIBILITY_FIELD);var fields=Set.of("schema_version","source_id","business_date","metadata_as_of","members");WireValues.object(profile,fields,fields);
        if(!"news-value-eligibility-v1".equals(WireValues.text(profile.get("schema_version")))||!profile.get("source_id").equals(snapshot.get("source_id"))||!profile.get("business_date").equals(snapshot.get("business_date"))||!cutoff.equals(java.time.Instant.parse(WireValues.text(profile.get("metadata_as_of")))))throw invalid();
        var raw=profile.get("members");if(!raw.isArray()||raw.size()!=bindings.size())throw invalid();var previous=NewsValueSourcePlanBuilder.priorDecisions(full);var high=new TreeSet<String>();var seen=new TreeSet<String>();var order=new ArrayList<String>();
        for(var current:raw) {
            var names=Set.of("item_id","entity_id","entity_revision","legacy","eligible_high_value","first_seen_business_date");WireValues.object(current,names,names);String id=WireValues.id(current.get("item_id"),false);var binding=bindings.get(id);var prior=previous.get(id);
            String entity=WireValues.id(current.get("entity_id"),false);if(binding==null||prior==null||!seen.add(id)||WireValues.nonnegativeInteger(current.get("entity_revision"))>=CanonicalJson.MAX_SAFE_INTEGER||!current.get("legacy").isBoolean()||!current.get("eligible_high_value").isBoolean())throw invalid();order.add(id);
            var first=current.get("first_seen_business_date").isNull()?null:WireValues.businessDate(current.get("first_seen_business_date"));boolean legacy=current.get("legacy").booleanValue();if(!legacy&&first==null||first!=null&&first.isAfter(cutoff.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()))throw invalid();
            boolean eligible=batch&&!legacy&&WireValues.businessDate(snapshot.get("business_date")).equals(first)&&!"missing".equals(materials.get(id).inputBasis());if(eligible!=current.get("eligible_high_value").booleanValue())throw invalid();if(eligible)high.add(id);
            if(!"ALREADY_APPLIED".equals(prior.get("reason").asText())) {
                var fact=prior.get("fact");if(!"ELIGIBLE".equals(prior.get("reason").asText())||fact==null||!"ready".equals(fact.get("state").asText())||!entity.equals(fact.get("entityId").asText())||binding.expectedRevision()<WireValues.nonnegativeInteger(fact.get("revision")))throw invalid();
            }
        }
        if(!seen.equals(bindings.keySet())||!order.equals(new ArrayList<>(seen)))throw invalid();
        String root=SourcePartitionPlanner.rootId(WireValues.text(snapshot.get("logical_key")),context.generation(),snapshot.has("stage_part")?WireValues.nonnegativeInteger(snapshot.get("stage_part")):0);
        var input=context.input(root,"value-qualification-check",CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),0);if(!high.equals(input.eligibleHighValueItems()))throw invalid();
        for(var candidate:snapshot.get(FrozenApplicationContext.FIELD).get("candidates_by_item")){String id=WireValues.id(candidate.get("item_id"),false);var ids=candidate.get("entity_ids");if(ids.size()!=1)throw invalid();String entity=WireValues.id(ids.get(0),false);var row=java.util.stream.StreamSupport.stream(raw.spliterator(),false).filter(n->n.get("item_id").asText().equals(id)).findFirst().orElseThrow();if(!entity.equals(row.get("entity_id").asText()))throw invalid();}
    }
    private static void bounded(JsonNode node){if(CanonicalJson.bytes(node).length>2*1048576)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("original news value input material unavailable");}
}
