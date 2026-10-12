package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NewsValueSourcePlanBuilderTest {
    final SourceDedupStageResultTest f=new SourceDedupStageResultTest();final LocalDate day=LocalDate.of(2026,1,1);final Instant cutoff=Instant.parse("2026-01-02T00:02:00Z"),observed=cutoff.minusSeconds(1);
    record Fixture(MemberUniverse.Universe universe,FrozenSourceUniverse.Reference ref,FrozenSourceDedupDependency dependency,NewsValueEligibilityService.Selection selection) { }
    Fixture fixture(int count) {
        var universe=FrozenSourceUniverseTest.universe(count,false);var p=f.f.plan(universe);if(p.scope()==null)p=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),FrozenSourceStagePlanTest.scope());
        var complete=SourceDedupStageResult.create(p,universe,f.ref(p,universe),f.proofs(p,universe));var dependency=FrozenSourceDedupDependency.create(complete,observed,cutoff);
        var nativeFacts=new HashMap<String,com.lianxibu.site.service.sources.SourceMemberStageService.Fact>();complete.members().forEach(m->nativeFacts.put(m.itemId(),m));var entries=new ArrayList<NewsValueEligibilityService.Entry>();
        for(var m:universe.members()) {
            var original=nativeFacts.get(m.itemId());if(m.terminalState().equals("excluded"))entries.add(new NewsValueEligibilityService.Entry(m.itemId(),NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED,null,null,null,null,false));
            else if(original==null||!original.state().equals("ready"))entries.add(new NewsValueEligibilityService.Entry(m.itemId(),NewsValueEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null,null,false));
            else {
                var body=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);body.put("source_url","https://example.invalid/"+m.itemId());body.put("time_basis","published");body.put("published_at","2026-01-01T08:00:00.000000Z");
                var identity=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("namespace","article_url");identity.set("key",body.get("source_url"));
                var fact=new SourceMemberEvidenceService.Fact(m.itemId(),original.revision(),"news_tech",canonical(identity),body.get("title").asText(),body.get("source_summary").asText(),canonical(body));
                var entity=new NewsValueEligibilityService.Entity(original.entityId(),canonical(identity),day,false,0,cutoff);
                entries.add(new NewsValueEligibilityService.Entry(m.itemId(),NewsValueEligibilityService.Reason.ELIGIBLE,fact,day,cutoff.minusSeconds(2),entity,true));
            }
        }
        String key=NewsValueSourcePlanBuilder.logicalKey(dependency);var ref=FrozenSourceUniverse.encode(JdbcFrozenInputs.stableId("intent",key+":1"),universe).reference();
        return new Fixture(universe,ref,dependency,new NewsValueEligibilityService.Selection(universe.runId(),universe.sourceId(),day,cutoff,entries));
    }
    SourcePartitionPlanner.Plan plan(Fixture x,FrozenGenerationSettings settings){return new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),x.selection(),settings,x.dependency(),1);}
    @Test void everyOriginalDecisionAndAllQualifiedMembersAreCoveredAcrossValueParts() {
        var x=fixture(501);var result=plan(x,new FrozenGenerationSettings(1,List.of("fictional-follow"),List.of()));assertEquals(501,result.decisions().size());assertTrue(result.chunks().size()>1);
        var eligible=x.selection().entries().stream().filter(e->e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE).map(NewsValueEligibilityService.Entry::itemId).sorted().toList();
        assertEquals(eligible,result.chunks().stream().flatMap(c->c.itemIds().stream()).toList());assertEquals(GenerationFlowService.Stage.NEWS_VALUE,result.stage());
        for(var chunk:result.chunks()) {
            var input=CanonicalJson.parse(chunk.snapshotJson());assertDoesNotThrow(()->NewsValueInputProfile.complete(input));var model=NewsValueInputProfile.modelView(input);
            assertTrue(input.get(FrozenSourceDedupDependency.FIELD).has("stage_result"));assertFalse(model.get(FrozenSourceDedupDependency.FIELD).has("stage_result"));assertFalse(model.get(FrozenSourceDedupDependency.FIELD).has("reports"));
            assertTrue(model.has("p1_qualified_report_v1"));assertFalse(canonical(model).contains("Fictional fragment summary"));assertEquals(chunk.itemIds().size(),model.get("p1_qualified_report_v1").get("items").size());
            assertEquals(chunk.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input));assertTrue(chunk.requestJson().contains("p1_qualified_report_v1"));
            assertFalse(model.get("members").get(0).has("body"));assertTrue(input.get("members").get(0).has("body"));
        }
    }
    @Test void blockedFullBodyTailIsExcludedAndOldEntityJudgmentRemainsWithoutHighEligibility() {
        var x=fixture(11);var entries=new ArrayList<>(x.selection().entries());int first=-1,second=-1;for(int n=0;n<entries.size();n++)if(entries.get(n).reason()==NewsValueEligibilityService.Reason.ELIGIBLE){if(first<0)first=n;else{second=n;break;}}
        var item=entries.get(first);var body=(ObjectNode)CanonicalJson.parse(item.fact().payloadJson());body.put("body_text","x".repeat(801)+"FORBIDDEN fictional tail");var fact=new SourceMemberEvidenceService.Fact(item.itemId(),item.fact().revision(),item.fact().category(),item.fact().identityKey(),item.fact().title(),item.fact().summary(),canonical(body));
        entries.set(first,new NewsValueEligibilityService.Entry(item.itemId(),item.reason(),fact,day,item.completedAt(),item.entity(),true));var old=entries.get(second);var entity=new NewsValueEligibilityService.Entity(old.entity().id(),old.entity().identityKey(),day.minusDays(1),false,0,cutoff);entries.set(second,new NewsValueEligibilityService.Entry(old.itemId(),old.reason(),old.fact(),day,old.completedAt(),entity,false));
        var selection=new NewsValueEligibilityService.Selection(x.universe().runId(),x.universe().sourceId(),day,cutoff,entries);var altered=new Fixture(x.universe(),x.ref(),x.dependency(),selection);var p=plan(altered,new FrozenGenerationSettings(2,List.of(),List.of("forbidden")));
        assertEquals(SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED,p.decisions().stream().filter(d->d.itemId().equals(item.itemId())).findFirst().orElseThrow().reason());
        var chunk=p.chunks().stream().filter(c->c.itemIds().contains(old.itemId())).findFirst().orElseThrow();var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));assertFalse(context.input(chunk.rootId(),"fictional-check",chunk.inputHash(),0).eligibleHighValueItems().contains(old.itemId()));assertFalse(canonical(NewsValueInputProfile.modelView(snapshot)).contains("FORBIDDEN"));
    }
    @Test void zeroInputAndOriginalExcludedDecisionsNeverCreateFakeRequests() {
        var p=plan(fixture(0),new FrozenGenerationSettings(0,List.of(),List.of()));assertTrue(p.chunks().isEmpty());assertTrue(p.decisions().isEmpty());assertNotNull(p.scope());
        var x=fixture(5);var entries=x.selection().entries().stream().map(e->e.reason()==NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED?e:new NewsValueEligibilityService.Entry(e.itemId(),NewsValueEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null,null,false)).toList();
        p=plan(new Fixture(x.universe(),x.ref(),x.dependency(),new NewsValueEligibilityService.Selection(x.universe().runId(),x.universe().sourceId(),day,cutoff,entries)),new FrozenGenerationSettings(0,List.of(),List.of()));assertTrue(p.chunks().isEmpty());assertEquals(5,p.decisions().size());
    }
    @Test void alteredScopeUniverseOrIncompleteQualificationCannotBuildAPartialPlan() {
        var x=fixture(5);var settings=new FrozenGenerationSettings(0,List.of(),List.of());
        assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),new NewsValueEligibilityService.Selection(x.universe().runId(),"fictional-other",day,cutoff,x.selection().entries()),settings,x.dependency(),1));
        assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),new NewsValueEligibilityService.Selection(x.universe().runId(),x.universe().sourceId(),day,cutoff,x.selection().entries().subList(0,1)),settings,x.dependency(),1));
        var wrong=FrozenSourceUniverse.encode("fictional-other-owner",x.universe()).reference();assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder().build(x.universe(),wrong,x.selection(),settings,x.dependency(),1));
        assertThrows(RuntimeException.class,()->new NewsValueSourcePlanBuilder().build(fixture(7).universe(),x.ref(),x.selection(),settings,x.dependency(),1));
    }
    @Test void completeProfileRejectsRehashedNewnessAndP1EntityTampering() {
        var x=fixture(5);var p=plan(x,new FrozenGenerationSettings(0,List.of(),List.of()));var input=(ObjectNode)CanonicalJson.parse(p.chunks().getFirst().snapshotJson());
        var changed=input.deepCopy();((ObjectNode)changed.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0)).put("entity_id","fictional-other-entity");assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(changed));
        var forged=input.deepCopy();((ObjectNode)forged.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0)).put("eligible_high_value",false);assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(forged));
        var missing=input.deepCopy();missing.remove(FrozenSourceDedupDependency.FIELD);assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(missing));
    }
    @Test void originalPartialCoverageIsExplicitAndNotConvertedIntoComplete() {
        var x=fixture(5);var p=new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(0,List.of(),List.of()),x.dependency(),1,true);
        var snapshot=CanonicalJson.parse(p.chunks().getFirst().snapshotJson());assertEquals("partial",snapshot.get("coverage_state").asText());assertEquals("collection:"+x.universe().runId(),snapshot.get("missing").get(0).asText());assertDoesNotThrow(()->NewsValueInputProfile.complete(snapshot));
        for(var item:NewsValueInputProfile.modelView(snapshot).get("p1_qualified_report_v1").get("items"))assertTrue(Set.of("new","existing").contains(item.get("dedup_decision").asText()));
    }
    @Test void completeOriginalProofOverTheRootBudgetIsRejectedWithoutDroppingTailReports() {
        var x=fixture(1001);var large=new SourceDedupFragmentProofTest(){@Override ObjectNode payload(SourcePartitionPlanner.Chunk chunk){var result=super.payload(chunk);result.put("summary","x".repeat(16000));for(var item:result.get("items"))((ObjectNode)item).put("reason","x".repeat(1800));return result;}};
        var p=large.plan(x.universe());var parts=p.chunks().stream().map(c->large.read(p,x.universe(),c,large.handoff(p,x.universe(),c))).toList();var whole=SourceDedupStageResult.create(p,x.universe(),f.ref(p,x.universe()),parts);var dependency=FrozenSourceDedupDependency.create(whole,observed,cutoff);assertTrue(dependency.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576);
        var first=x.selection().entries().stream().filter(e->e.reason()==NewsValueEligibilityService.Reason.ELIGIBLE).findFirst().orElseThrow();var entries=x.selection().entries().stream().map(e->e.itemId().equals(first.itemId())||e.reason()==NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED?e:new NewsValueEligibilityService.Entry(e.itemId(),NewsValueEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null,null,false)).toList();
        var selection=new NewsValueEligibilityService.Selection(x.universe().runId(),x.universe().sourceId(),day,cutoff,entries);
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),selection,new FrozenGenerationSettings(0,List.of(),List.of()),dependency,1));
    }
    static String canonical(tools.jackson.databind.JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
}
