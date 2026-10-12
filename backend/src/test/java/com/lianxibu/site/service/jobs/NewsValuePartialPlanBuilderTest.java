package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Fictional pure partial proofs. Runtime database qualification is independently required. */
class NewsValuePartialPlanBuilderTest {
    final NewsValuePartialInputProfileTest f=new NewsValuePartialInputProfileTest();
    SourcePartitionPlanner.Plan build(NewsValuePartialInputProfileTest.Fixture x,boolean collection,String version){var o=x.original();return new NewsValueSourcePlanBuilder(version).build(o.universe(),o.ref(),o.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),x.partial(),1,collection);}
    @Test void OnlyOriginalSuccessesProducePartsAndEveryOriginalFailureRemainsVisible(){
        var x=f.fixture();var plan=assertDoesNotThrow(()->build(x,false,"2"));assertEquals(501,plan.decisions().size());assertTrue(plan.chunks().size()>1);assertEquals(x.original().selection().entries().stream().filter(e->e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE&&!x.failed().contains(e.itemId())).map(NewsValueEligibilityService.Entry::itemId).sorted().toList(),plan.chunks().stream().flatMap(c->c.itemIds().stream()).toList());
        for(var d:plan.decisions())if(x.failed().contains(d.itemId()))assertEquals(SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE,d.reason());var context=CanonicalJson.parse(plan.scope().stageContextJson());assertEquals("source-value-stage-context-v2",context.get("schema_version").asText());assertEquals(1,context.get("missing").size());
        for(var c:plan.chunks()){var input=CanonicalJson.parse(c.snapshotJson());assertDoesNotThrow(()->NewsValueInputProfile.complete(input));assertEquals("partial",input.get("coverage_state").asText());assertEquals(1,input.get("missing").size());assertTrue(input.get("missing").get(0).asText().endsWith(":dedup_failed"));assertEquals("2",input.get(FrozenApplicationContext.FIELD).get("value_result_version").asText());assertEquals(c.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input));assertFalse(c.requestJson().contains("\"resolutions\""));}
        var encoded=FrozenSourceStagePlan.encode(plan,x.original().universe());assertEquals("source-stage-scope-v3",SourcePartialScopePlanTest.body(encoded).get("scope").get("schema_version").asText());assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.original().universe()));
    }
    @Test void CollectionPartialIsIndependentAndTheMissingListIsExact(){
        var x=f.fixture();for(boolean collection:List.of(false,true)){var p=build(x,collection,"1");var raw=p.scope().stageContextJson();assertEquals(collection?2:1,CanonicalJson.parse(raw).get("missing").size());for(var c:p.chunks()){var input=CanonicalJson.parse(c.snapshotJson());assertEquals(SourceValueStageContext.partialSnapshotMissing(raw,f.f.day,f.f.cutoff),input.get("missing").valueStream().map(n->n.asText()).toList());assertFalse(input.get(FrozenApplicationContext.FIELD).has("value_result_version"));assertEquals(raw,SourceValueStageContext.fromPartialSnapshot(input));}}
    }
    @Test void AllFailedStillArchivesEveryDecisionWithZeroParts(){
        var x=f.fixture();var profile=(ObjectNode)x.partial().toJson();var full=(ObjectNode)profile.get("stage_result");var failed=new TreeSet<String>();for(var resolution:full.get("resolutions")){var row=(ObjectNode)resolution;row.put("state","failed").put("call_count",3);row.putNull("application_state");row.putNull("result_hash");row.putNull("applied_at");row.get("item_ids").forEach(id->failed.add(id.asText()));}full.putArray("fragments");profile.putArray("reports");full.put("applied_member_count",0).put("unapplied_member_count",failed.size());((ObjectNode)profile.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));var dep=FrozenSourceDedupDependency.readPartial(profile,f.f.cutoff);var all=new NewsValuePartialInputProfileTest.Fixture(x.original(),dep,failed,x.legacy());var p=build(all,false,"2");assertTrue(p.chunks().isEmpty());assertEquals(501,p.decisions().size());assertTrue(p.decisions().stream().filter(d->failed.contains(d.itemId())).allMatch(d->d.reason()==SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE));assertEquals(full.get("stage_part_count").asInt(),CanonicalJson.parse(p.scope().stageContextJson()).get("missing").size());var archive=FrozenSourceStagePlan.encode(p,x.original().universe());assertEquals(p,FrozenSourceStagePlan.decode(archive.reference(),archive.payloads(),x.original().universe()));
    }
    @Test void FailedReadyFactsAreExcludedBeforePreferencesAndTheirMetadataCannotInventSuccess(){
        var x=f.fixture();var entries=new ArrayList<NewsValueEligibilityService.Entry>();boolean changed=false;for(var e:x.original().selection().entries()){
            if(x.failed().contains(e.itemId())&&e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE){changed=true;var entity=new NewsValueEligibilityService.Entity(e.entity().id(),e.entity().identityKey(),e.entity().firstSeenBusinessDate(),e.entity().legacy(),e.entity().revision(),f.f.cutoff.plusSeconds(1));entries.add(new NewsValueEligibilityService.Entry(e.itemId(),e.reason(),e.fact(),e.businessDate(),e.completedAt(),entity,e.eligibleHighValue()));}else entries.add(e);
        }assertTrue(changed);var o=x.original();var selection=new NewsValueEligibilityService.Selection(o.selection().runId(),o.selection().sourceId(),o.selection().day(),o.selection().cutoff(),entries);var p=new NewsValueSourcePlanBuilder().build(o.universe(),o.ref(),selection,new FrozenGenerationSettings(1,List.of(),List.of("fictional")),x.partial(),1,false);assertTrue(p.decisions().stream().filter(d->x.failed().contains(d.itemId())).allMatch(d->d.reason()==SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE));
    }
    @Test void WrongOriginalUniverseAndUnsettledProfilesCannotBeTurnedIntoPlans(){
        var x=f.fixture();var o=x.original();var other=f.f.fixture(500);assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder().build(other.universe(),other.ref(),o.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),x.partial(),1));var bad=(ObjectNode)x.partial().toJson();var full=(ObjectNode)bad.get("stage_result");((ObjectNode)full.get("resolutions").get(0)).put("state","pending");((ObjectNode)bad.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.readPartial(bad,f.f.cutoff));assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder("9"));
    }
}
