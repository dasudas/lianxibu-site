package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

/** Entirely fictional pure structure. These bytes confer no database or model permission. */
class NewsValuePartialInputProfileTest {
    final NewsValueSourcePlanBuilderTest f=new NewsValueSourcePlanBuilderTest();
    record Fixture(NewsValueSourcePlanBuilderTest.Fixture original,FrozenSourceDedupDependency partial,Set<String> failed,SourcePartitionPlanner.Plan legacy) { }
    Fixture fixture(){
        var original=f.fixture(501);var profile=(ObjectNode)original.dependency().toJson();var full=(ObjectNode)profile.get("stage_result");var fragments=(ArrayNode)full.get("fragments");var all=new ArrayList<JsonNode>();fragments.forEach(n->all.add(n.deepCopy()));assertTrue(all.size()>1);
        profile.put("schema_version","source-dedup-dependency-v2");full.put("schema_version","source-dedup-stage-result-v2").put("coverage_state","partial");var resolutions=full.putArray("resolutions");var failed=new TreeSet<String>();int applied=0;
        for(int n=0;n<all.size();n++){
            var fragment=all.get(n);boolean success=n!=all.size()-1;var row=resolutions.addObject();for(String field:List.of("root_id","stage_part","input_hash"))row.set(field,fragment.get(field));var ids=row.putArray("item_ids");for(var m:fragment.get("members")){String id=m.get("itemId").asText();ids.add(id);if(success)applied++;else failed.add(id);}
            row.put("state",success?"succeeded":"failed").put("revision",3).put("call_count",success?1:3);if(success){row.put("application_state","APPLIED");row.set("result_hash",fragment.get("result_hash"));row.put("applied_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(f.observed.minusSeconds(1)));}else{row.putNull("application_state");row.putNull("result_hash");row.putNull("applied_at");}
        }
        fragments.remove(fragments.size()-1);((ArrayNode)profile.get("reports")).remove(all.size()-1);full.put("applied_member_count",applied).put("unapplied_member_count",failed.size());((ObjectNode)profile.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));
        var partial=FrozenSourceDedupDependency.readPartial(profile,f.cutoff);return new Fixture(original,partial,Set.copyOf(failed),f.plan(original,new FrozenGenerationSettings(1,List.of(),List.of())));
    }
    ObjectNode snapshot(Fixture x,SourcePartitionPlanner.Chunk chunk,boolean collection){
        var node=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());node.set(FrozenSourceDedupDependency.FIELD,x.partial().toJson());var deps=x.partial().dependencies();node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(deps,deps.stream().map(GenerationFlowService.Dependency::id).toList()));String raw=SourceValueStageContext.create(x.partial(),f.day,f.cutoff,collection);node.put("coverage_state","partial");var missing=node.putArray("missing");SourceValueStageContext.partialSnapshotMissing(raw,f.day,f.cutoff).forEach(missing::add);return node;
    }
    ObjectNode successful(Fixture x,boolean collection){return snapshot(x,x.legacy().chunks().stream().filter(c->Collections.disjoint(c.itemIds(),x.failed())).findFirst().orElseThrow(),collection);}
    @Test void successfulOriginalPartKeepsExactSparseDependenciesAndOnlyQualifiedModelFacts(){
        var x=fixture();var input=successful(x,false);String original=canonical(input);var materials=assertDoesNotThrow(()->NewsValueInputProfile.complete(input));assertEquals(input.get("members").size(),materials.size());var view=NewsValueInputProfile.modelView(input);assertEquals(original,canonical(input));assertEquals("news-value-material-view-v1",view.get("model_input_view").asText());assertEquals("partial",view.get("coverage_state").asText());assertEquals(1,view.get("missing").size());assertFalse(view.get(FrozenSourceDedupDependency.FIELD).has("stage_result"));assertFalse(view.get(FrozenSourceDedupDependency.FIELD).has("reports"));assertEquals(materials.size(),view.get("p1_qualified_report_v1").get("items").size());assertTrue(view.get("members").valueStream().allMatch(n->!n.has("body")));
        for(var item:view.get("p1_qualified_report_v1").get("items")){assertFalse(x.failed().contains(item.get("item_id").asText()));assertEquals("ELIGIBLE",item.get("source_decision").asText());assertTrue(Set.of("new","existing").contains(item.get("dedup_decision").asText()));}
        String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);String request=new GenerationRequestBuilder().build(GenerationFlowService.Stage.NEWS_VALUE,original,hash);assertTrue(request.contains("p1_qualified_report_v1"));assertFalse(request.contains("Fictional fragment summary"));assertFalse(request.contains("\"resolutions\""));assertEquals(x.partial().dependencies().size(),input.get(FrozenDependencyManifest.FIELD).get("dependencies").size());
    }
    @Test void failedOriginalMemberCannotBeRequalifiedByCurrentReadyEntityOrRevision(){
        var x=fixture();var chunk=x.legacy().chunks().stream().filter(c->!Collections.disjoint(c.itemIds(),x.failed())).findFirst().orElseThrow();var input=snapshot(x,chunk,false);assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(input));assertThrows(RuntimeException.class,()->NewsValueInputProfile.modelView(input));
        for(var binding:input.get(FrozenMemberBindings.FIELD).get("members"))((ObjectNode)binding).put("expected_revision",999);assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(input));
    }
    @Test void exactMissingAndSuccessfulManifestCannotHideOrBorrowFailedParts(){
        var x=fixture();assertDoesNotThrow(()->NewsValueInputProfile.complete(successful(x,true)));
        for(String mode:List.of("missing","reason","collection","coverage","manifest","old-version","unknown-version","extra")){
            var input=successful(x,false);switch(mode){case "missing"->input.putArray("missing");case "reason"->((ArrayNode)input.get("missing")).set(0,new StringNode("source_fragment:fictional-wrong:dedup_cancelled"));case "collection"->((ArrayNode)input.get("missing")).add("collection:fictional-other-run");case "coverage"->input.put("coverage_state","complete");case "manifest"->input.set(FrozenDependencyManifest.FIELD,CanonicalJson.parse(x.legacy().chunks().getFirst().snapshotJson()).get(FrozenDependencyManifest.FIELD));case "old-version"->((ObjectNode)input.get(FrozenSourceDedupDependency.FIELD)).put("schema_version","source-dedup-dependency-v1");case "unknown-version"->((ObjectNode)input.get(FrozenSourceDedupDependency.FIELD)).put("schema_version","source-dedup-dependency-v9");case "extra"->((ObjectNode)input.get(FrozenSourceDedupDependency.FIELD)).put("unreviewed",true);}assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(input),mode);
        }
    }
    @Test void OriginalMaterialIdentityMetadataAndHighValueRulesRemainStrict(){
        var x=fixture();for(String mode:List.of("entity","metadata","high","body","source","day","revision","new-field")){
            var input=successful(x,false);var row=(ObjectNode)input.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0);switch(mode){case "entity"->row.put("entity_id","fictional-other-entity");case "metadata"->((ObjectNode)input.get(NewsValueInputProfile.ELIGIBILITY_FIELD)).put("metadata_as_of",f.cutoff.plusSeconds(1).toString());case "high"->row.put("eligible_high_value",false);case "body"->((ObjectNode)input.get("members").get(0)).put("body_hash","b".repeat(64));case "source"->input.put("source_id","fictional-other-source");case "day"->input.put("business_date",f.day.plusDays(1).toString());case "revision"->((ObjectNode)input.get(FrozenMemberBindings.FIELD).get("members").get(0)).put("expected_revision",0);case "new-field"->row.put("unreviewed",true);}assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(input),mode);
        }
    }
    @Test void ExistingV1InputsAndPureSqlBoundaryRemainExplicit(){
        var x=fixture();for(var chunk:x.legacy().chunks()){var old=CanonicalJson.parse(chunk.snapshotJson());assertDoesNotThrow(()->NewsValueInputProfile.complete(old));assertEquals("source-dedup-dependency-v1",old.get(FrozenSourceDedupDependency.FIELD).get("schema_version").asText());}assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(x.partial().toJson(),f.cutoff));assertEquals(501,x.partial().toJson().get("stage_result").get("decisions").size());
    }
    static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
}
