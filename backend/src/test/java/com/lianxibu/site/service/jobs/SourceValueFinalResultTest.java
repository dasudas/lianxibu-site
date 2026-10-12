package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFinalResultTest {
    static java.util.stream.Stream<tools.jackson.databind.JsonNode> elements(tools.jackson.databind.JsonNode node){return java.util.stream.StreamSupport.stream(node.spliterator(),false);}
    record Fixture(SourcePartitionPlanner.Plan plan,MemberUniverse.Universe universe,SourceValueStageResult whole) { }
    Fixture fixture(int count,boolean duplicate,boolean high,boolean partial,String summary,String reason,List<String> blocked){
        var original=new NewsValueSourcePlanBuilderTest();var u=FrozenSourceUniverseTest.universe(count,false);
        var dedup=new SourceDedupFragmentProofTest(){
            @Override ObjectNode payload(SourcePartitionPlanner.Chunk c){var p=super.payload(c);for(var item:p.get("items")){var n=(ObjectNode)item;n.put("decision","new").putNull("candidate_entity_id");}return p;}
            @Override ObjectNode handoff(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u,SourcePartitionPlanner.Chunk c){var n=super.handoff(p,u,c);var bindings=FrozenMemberBindings.read(CanonicalJson.parse(c.snapshotJson()),input(c));for(var m:n.get("members")){var fact=(ObjectNode)m;String id=fact.get("itemId").asText();fact.put("state","ready").put("entityId",duplicate?"fictional-shared-entity":bindings.get(id).newEntityId());}return n;}
        };
        var p1=dedup.plan(u);if(p1.scope()==null)p1=new SourcePartitionPlanner.Plan(p1.stage(),p1.logicalKey(),p1.generation(),p1.reference(),p1.decisions(),p1.chunks(),FrozenSourceStagePlanTest.scope());var frozenP1=p1;
        var parts=p1.chunks().stream().map(c->dedup.read(frozenP1,u,c,dedup.handoff(frozenP1,u,c))).toList();var complete=SourceDedupStageResult.create(p1,u,FrozenSourceStagePlan.encode(p1,u).reference(),parts);var dependency=FrozenSourceDedupDependency.create(complete,original.observed,original.cutoff);
        var facts=new HashMap<String,com.lianxibu.site.service.sources.SourceMemberStageService.Fact>();complete.members().forEach(m->facts.put(m.itemId(),m));var entries=new ArrayList<NewsValueEligibilityService.Entry>();
        for(var m:u.members()){
            if(m.terminalState().equals("excluded")){entries.add(new NewsValueEligibilityService.Entry(m.itemId(),NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED,null,null,null,null,false));continue;}
            var fact=facts.get(m.itemId());var body=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);body.put("source_url","https://example.invalid/"+m.itemId()).put("time_basis","published").put("published_at","2026-01-01T08:00:00.000000Z");var identity=JsonMapper.shared().createObjectNode().put("namespace","article_url");identity.set("key",body.get("source_url"));
            var evidence=new SourceMemberEvidenceService.Fact(m.itemId(),fact.revision(),"news_tech",JdbcFrozenInputs.canonical(identity),body.get("title").asText(),body.get("source_summary").asText(),JdbcFrozenInputs.canonical(body));var entity=new NewsValueEligibilityService.Entity(fact.entityId(),JdbcFrozenInputs.canonical(identity),original.day,false,0,original.cutoff);entries.add(new NewsValueEligibilityService.Entry(m.itemId(),NewsValueEligibilityService.Reason.ELIGIBLE,evidence,original.day,original.cutoff.minusSeconds(2),entity,true));
        }
        String key=NewsValueSourcePlanBuilder.logicalKey(dependency);var reference=FrozenSourceUniverse.encode(JdbcFrozenInputs.stableId("intent",key+":1"),u).reference();var selection=new NewsValueEligibilityService.Selection(u.runId(),u.sourceId(),original.day,original.cutoff,entries);var plan=new NewsValueSourcePlanBuilder("2").build(u,reference,selection,new FrozenGenerationSettings(1,List.of(),blocked),dependency,1,partial);
        var value=new SourceValueStageResultTest(){@Override ObjectNode payload(SourcePartitionPlanner.Chunk c){var p=super.payload(c);p.put("summary",summary+(!summary.isBlank()?" tail-"+c.stagePart():""));var selected=p.putArray("selected_item_ids");int n=0;for(var row:p.get("items")){((ObjectNode)row).put("high_value",high).put("reason",reason);if(high&&n++<5)selected.add(row.get("item_id").asText());}return p;}};
        var results=value.proofs(plan,u);return new Fixture(plan,u,SourceValueStageResult.create(plan,u,FrozenSourceStagePlan.encode(plan,u).reference(),results));
    }
    SourceValueFinalResult result(Fixture x){return SourceValueFinalResult.create(x.plan(),x.universe(),x.whole());}
    @Test void globalSelectionHasAtMostFiveDistinctEntitiesAndPreservesEverySummaryTail(){
        var x=fixture(251,false,true,false,"Fictional source summary","Fictional high-value evidence",List.of());assertEquals(2,x.plan().chunks().size());var r=result(x);assertEquals(5,r.selected().size());assertEquals(5,r.selected().stream().map(SourceValueFinalResult.Selected::entityId).distinct().count());var expected=x.whole().fragments().stream().flatMap(p->elements(CanonicalJson.parse(p.result().payloadJson()).get("selected_item_ids"))).map(n->n.asText()).sorted().limit(5).toList();assertEquals(expected,r.selected().stream().map(SourceValueFinalResult.Selected::itemId).toList());
        assertTrue(r.summary().contains("tail-0"));assertTrue(r.summary().contains("tail-1"));var n=CanonicalJson.parse(r.json());assertEquals(10,n.get("selection_decisions").size());assertEquals("complete",r.coverageState());assertEquals(x.whole().hash(),n.get("whole_result_hash").asText());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,n),r.hash());assertEquals(r.json(),result(x).json());assertThrows(UnsupportedOperationException.class,()->r.selected().clear());assertFalse(r.toString().contains("Fictional source summary"));
    }
    @Test void sameEntityCannotOccupyTwoSlotsAndOrdinaryValueIsNotUpgraded(){
        var duplicate=fixture(13,true,true,false,"Fictional summary","Fictional value reason",List.of());assertEquals(1,result(duplicate).selected().size());assertTrue(elements(CanonicalJson.parse(result(duplicate).json()).get("selection_decisions")).anyMatch(n->n.get("disposition").asText().equals("entity_duplicate")));
        var ordinary=fixture(5,false,false,false,"Fictional ordinary summary","Fictional ordinary reason",List.of());var n=CanonicalJson.parse(result(ordinary).json());assertEquals(3,n.get("valuable_count").asInt());assertEquals(0,n.get("high_value_count").asInt());assertTrue(result(ordinary).selected().isEmpty());
    }
    @Test void zeroOriginalPlanCreatesAnHonestProgramResultWithNoModelSummary(){
        var x=fixture(0,false,false,false,"","",List.of());var r=result(x);assertTrue(r.selected().isEmpty());assertTrue(r.missing().isEmpty());assertEquals("complete",r.coverageState());assertTrue(r.summary().contains("没有可分析输入"));assertEquals(0,CanonicalJson.parse(r.json()).get("eligible_count").asInt());
    }
    @Test void missingOrBlockedSummariesAndBlockedReasonsStayExplicitAndDoNotReenterOutput(){
        var missing=fixture(5,false,true,false,"","Fictional valid reason",List.of());assertEquals("partial",result(missing).coverageState());assertTrue(result(missing).missing().stream().anyMatch(m->m.reason().equals("summary_missing")));
        var blocked=fixture(5,false,true,false,"ＦＯＲＢＩＤＤＥＮ fictional summary","FoRbIdDeN fictional reason",List.of("forbidden"));var r=result(blocked);assertEquals("partial",r.coverageState());assertTrue(r.selected().isEmpty());assertFalse(r.summary().contains("ＦＯＲＢＩＤＤＥＮ"));assertTrue(r.missing().stream().anyMatch(m->m.reason().equals("preference_blocked_summary")));
    }
    @Test void collectionGapsAndP1NeedsReviewAreNeverHiddenByCompleteValueFragments(){
        var partial=fixture(5,false,true,true,"Fictional partial summary","Fictional reason",List.of());assertEquals("partial",result(partial).coverageState());assertTrue(result(partial).missing().stream().anyMatch(m->m.reason().equals("collection_partial")));
        var f=new SourceValueStageResultTest();var x=f.f.fixture(11);var p=f.plan(x);var whole=SourceValueStageResult.create(p,x.universe(),f.ref(p,x.universe()),f.proofs(p,x.universe()));var r=SourceValueFinalResult.create(p,x.universe(),whole);assertEquals("partial",r.coverageState());assertTrue(r.missing().stream().anyMatch(m->m.reason().equals("dedup_needs_review")));
    }
    @Test void differentFrozenSettingsAndCapacityCannotProducePartialFinalSuccess(){
        var x=fixture(5,false,true,false,"Fictional final summary","Fictional reason",List.of());var scope=x.plan().scope();var p=new SourcePartitionPlanner.Plan(x.plan().stage(),x.plan().logicalKey(),x.plan().generation(),x.plan().reference(),x.plan().decisions(),x.plan().chunks(),new SourcePartitionPlanner.Scope(scope.businessDate(),scope.cutoff(),new FrozenGenerationSettings(2,List.of("fictional-changed"),List.of()),scope.stageContextJson()));assertThrows(RuntimeException.class,()->SourceValueFinalResult.create(p,x.universe(),x.whole()));assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceValueFinalResult.createWithinCapacity(x.plan(),x.universe(),x.whole(),1024));assertNotNull(result(x));
    }
}
