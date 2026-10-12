package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure composition; the day reference itself does not grant an execution permission. */
class SourceValueDayStageResultTest {
    final NewsValueDayPlanBuilderTest days=new NewsValueDayPlanBuilderTest();
    final SourceValueStageResultTest values=new SourceValueStageResultTest();
    @Test void includedWholeResultRetainsOriginalDayAcrossEveryFragment(){
        var x=days.earlyFixture(501);var day=days.day(x.dependency(),x.selection().cutoff(),true);var plan=days.build(x,x.dependency(),day);
        var proofs=values.proofs(plan,x.universe());var result=SourceValueStageResult.create(plan,x.universe(),values.ref(plan,x.universe()),proofs);var body=CanonicalJson.parse(result.json());
        assertEquals("source-value-stage-result-v1",body.get("schema_version").asText());assertEquals(CanonicalJson.parse(plan.scope().stageContextJson()),body.get("stage_context"));assertEquals(day.toJson(),body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD));assertTrue(body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD).get("original_batch_eligible").asBoolean());assertEquals(proofs.size(),result.fragments().size());assertTrue(result.fragments().size()>1);assertEquals(501,result.decisions().size());
        var reversed=new ArrayList<>(proofs);Collections.reverse(reversed);assertEquals(result.json(),SourceValueStageResult.create(plan,x.universe(),values.ref(plan,x.universe()),reversed).json());
    }
    @Test void lateWholeOrdinaryValueDoesNotBecomePartialOrEnterOriginalHighBatch(){
        var x=days.f.fixture(501);var day=days.day(x.dependency(),days.f.cutoff,false);var plan=days.build(x,x.dependency(),day);var result=SourceValueStageResult.create(plan,x.universe(),values.ref(plan,x.universe()),values.proofs(plan,x.universe()));var body=CanonicalJson.parse(result.json());
        assertEquals("source-value-stage-result-v1",body.get("schema_version").asText());assertEquals("complete",body.get("stage_context").get("coverage_state").asText());assertFalse(body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD).get("original_batch_eligible").asBoolean());assertFalse(result.members().isEmpty());
        for(var fragment:body.get("fragments"))for(var item:fragment.get("payload").get("items")){assertTrue(item.get("valuable").asBoolean());assertFalse(item.get("high_value").asBoolean());}
        assertEquals(result.hash(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));
    }
    @Test void actualEmptyUniverseKeepsIncludedDayAndZeroOriginalParts(){
        var x=days.earlyFixture(0);var day=days.day(x.dependency(),x.selection().cutoff(),true);var plan=days.build(x,x.dependency(),day);var result=SourceValueStageResult.create(plan,x.universe(),values.ref(plan,x.universe()),List.of());var body=CanonicalJson.parse(result.json());
        assertEquals(0,body.get("accepted").asInt());assertEquals(0,body.get("stage_part_count").asInt());assertTrue(result.fragments().isEmpty());assertEquals(day.toJson(),body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD));
    }
    @Test void directWholeComposerCannotHidePartialP1BehindDayContext(){
        var x=new NewsValuePartialInputProfileTest().fixture();var day=days.day(x.partial(),days.f.cutoff,false);var plan=days.build(x.original(),x.partial(),day);var context=CanonicalJson.parse(plan.scope().stageContextJson());assertEquals("source-value-stage-context-v3",context.get("schema_version").asText());assertEquals("source-dedup-dependency-v2",context.get(FrozenSourceDedupDependency.FIELD).get("schema_version").asText());assertFalse(context.get("missing").isEmpty());
        assertThrows(RuntimeException.class,()->SourceValueStageResult.create(plan,x.original().universe(),values.ref(plan,x.original().universe()),values.proofs(plan,x.original().universe())));
    }
}
