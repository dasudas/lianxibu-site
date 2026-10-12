package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceValueStageContextTest {
    final NewsValueSourcePlanBuilderTest f=new NewsValueSourcePlanBuilderTest();
    @Test void zeroOriginalPlanArchivesItsOwnCompleteP1DependencyAndCoverage() {
        var x=f.fixture(0);var plan=f.plan(x,new FrozenGenerationSettings(0,List.of(),List.of()));assertTrue(plan.chunks().isEmpty());assertNotNull(plan.scope().stageContextJson());
        var original=FrozenSourceStagePlan.encode(plan,x.universe());var reread=FrozenSourceStagePlan.decode(original.reference(),original.payloads(),x.universe());assertEquals(plan,reread);
        var context=CanonicalJson.parse(reread.scope().stageContextJson());assertEquals(x.dependency().json(),canonical(context.get(FrozenSourceDedupDependency.FIELD)));assertEquals("complete",context.get("coverage_state").asText());assertEquals(0,context.get("missing").size());
    }
    @Test void partialOriginalCoverageAndEveryPartUseTheSameCanonicalStageContext() {
        var x=f.fixture(501);var plan=new NewsValueSourcePlanBuilder().build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),x.dependency(),1,true);
        var original=FrozenSourceStagePlan.encode(plan,x.universe());assertEquals(plan,FrozenSourceStagePlan.decode(original.reference(),original.payloads(),x.universe()));
        var context=CanonicalJson.parse(plan.scope().stageContextJson());assertEquals("partial",context.get("coverage_state").asText());assertEquals("collection:"+x.universe().runId(),context.get("missing").get(0).asText());
        for(var chunk:plan.chunks())assertEquals(plan.scope().stageContextJson(),SourceValueStageContext.fromSnapshot(CanonicalJson.parse(chunk.snapshotJson())));
    }
    @Test void closedCanonicalTimeAndCoverageAreRequiredForAnyScope() {
        var x=f.fixture(0);String saved=f.plan(x,new FrozenGenerationSettings(0,List.of(),List.of())).scope().stageContextJson();
        for(String change:List.of("extra","schema_version","coverage_state","missing")) {
            var altered=(ObjectNode)CanonicalJson.parse(saved);if(change.equals("missing"))altered.putArray("missing").add("fictional-wrong");else altered.put(change,"fictional-wrong");
            assertThrows(RuntimeException.class,()->new SourcePartitionPlanner.Scope(f.day,f.cutoff,new FrozenGenerationSettings(0,List.of(),List.of()),canonical(altered)));
        }
        assertThrows(RuntimeException.class,()->new SourcePartitionPlanner.Scope(f.day,f.cutoff,new FrozenGenerationSettings(0,List.of(),List.of()),saved+" "));
        assertThrows(RuntimeException.class,()->new SourcePartitionPlanner.Scope(f.day.minusDays(1),f.cutoff,new FrozenGenerationSettings(0,List.of(),List.of()),saved));
    }
    @Test void replacedPartContextCannotUseTheOriginalWholeScope() {
        var x=f.fixture(5);var p=f.plan(x,new FrozenGenerationSettings(0,List.of(),List.of()));var first=p.chunks().getFirst();var snapshot=(ObjectNode)CanonicalJson.parse(first.snapshotJson());snapshot.put("coverage_state","partial");snapshot.putArray("missing").add("collection:"+x.universe().runId());String raw=canonical(snapshot),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
        var changed=new SourcePartitionPlanner.Chunk(first.stagePart(),first.rootId(),first.itemIds(),raw,hash,new GenerationRequestBuilder().build(p.stage(),raw,hash));var wrong=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),List.of(changed),p.scope());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(wrong,x.universe()));
    }
    @Test void originalV1ScopeKeepsItsOriginalShapeAndSupportsItsOriginalPlan() {
        var x=f.fixture(5);var p=f.f.f.plan(x.universe());assertNull(p.scope().stageContextJson());var original=FrozenSourceStagePlan.encode(p,x.universe());assertEquals(p,FrozenSourceStagePlan.decode(original.reference(),original.payloads(),x.universe()));
        var scope=FrozenSourceStagePlanTest.scope();assertNull(scope.stageContextJson());assertEquals(scope,new SourcePartitionPlanner.Scope(scope.businessDate(),scope.cutoff(),scope.settings()));
    }
    static String canonical(tools.jackson.databind.JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
}
