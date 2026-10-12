package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NewsValueDayPlanBuilderTest {
    final NewsValueSourcePlanBuilderTest f=new NewsValueSourcePlanBuilderTest();
    final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),early=registered.plusSeconds(600);
    FrozenSourceDayDedupDependency day(FrozenSourceDedupDependency own,Instant cutoff,boolean included){
        var k=own.toJson().get("stage_key");var member=new SourceDedupBarrier.Member(k.get("source_id").asText(),SourceDedupBarrier.Inclusion.ENABLED,3L,"a".repeat(64),k.get("run_id").asText(),1L);
        var cohort=SourceDedupBarrier.freeze("news_tech",f.day,registered,List.of(member));var full=own.toJson().get("stage_result");
        var state="source-dedup-stage-result-v2".equals(full.get("schema_version").asText())?SourceDedupBarrier.State.PARTIAL:full.get("eligible_count").asLong()==0?SourceDedupBarrier.State.EMPTY_SUCCESS:SourceDedupBarrier.State.COMPLETE;
        var fact=new SourceDedupBarrier.Progress("news_tech",f.day,member.sourceId(),3,member.configHash(),member.runId(),1,state,own.observedAt(),k.get("result_hash").asText());
        var closed=SourceDedupBarrier.close(cohort,included?cutoff:SourceDedupBarrier.deadline(f.day),included?List.of(fact):List.of());
        return FrozenSourceDayDedupDependency.create("fictional-directory","b".repeat(64),closed,own,cutoff);
    }
    NewsValueSourcePlanBuilderTest.Fixture earlyFixture(int count){
        var x=f.fixture(count);var p=f.f.f.plan(x.universe());var originalPlan=p;var scope=new SourcePartitionPlanner.Scope(f.day,early.minusSeconds(60),FrozenSourceStagePlanTest.scope().settings());
        var chunks=p.chunks().stream().map(c->{var n=(ObjectNode)CanonicalJson.parse(c.snapshotJson());n.put("cutoff",scope.cutoff().toString());for(var m:n.get("members"))((ObjectNode)m).put("completed_at",scope.cutoff().minusSeconds(60).toString());String raw=f.canonical(n),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,n);return new SourcePartitionPlanner.Chunk(c.stagePart(),c.rootId(),c.itemIds(),raw,hash,new GenerationRequestBuilder().build(originalPlan.stage(),raw,hash));}).toList();
        p=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),chunks,scope);var actual=p;var own=FrozenSourceDedupDependency.create(SourceDedupStageResult.create(p,x.universe(),f.f.ref(p,x.universe()),f.f.proofs(actual,x.universe())),early,early.plusSeconds(60));
        var entries=x.selection().entries().stream().map(e->{if(e.entity()==null)return e;var entity=new NewsValueEligibilityService.Entity(e.entity().id(),e.entity().identityKey(),e.entity().firstSeenBusinessDate(),e.entity().legacy(),e.entity().revision(),early.plusSeconds(60));return new NewsValueEligibilityService.Entry(e.itemId(),e.reason(),e.fact(),e.businessDate(),early,entity,e.eligibleHighValue());}).toList();
        return new NewsValueSourcePlanBuilderTest.Fixture(x.universe(),x.ref(),own,new NewsValueEligibilityService.Selection(x.selection().runId(),x.selection().sourceId(),f.day,early.plusSeconds(60),entries));
    }
    SourcePartitionPlanner.Plan build(NewsValueSourcePlanBuilderTest.Fixture x,FrozenSourceDedupDependency own,FrozenSourceDayDedupDependency day){return new NewsValueSourcePlanBuilder("2").buildDay(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),own,day,1,false);}
    @Test void everyIncludedFragmentAndZeroPlanPreserveExactOriginalDayScope(){
        for(int count:List.of(0,501)){
            var x=earlyFixture(count);var day=day(x.dependency(),x.selection().cutoff(),true);var plan=build(x,x.dependency(),day);assertEquals(count,plan.decisions().size());
            assertEquals(day.toJson(),CanonicalJson.parse(plan.scope().stageContextJson()).get(FrozenSourceDayDedupDependency.FIELD));
            if(count==0)assertTrue(plan.chunks().isEmpty());else assertTrue(plan.chunks().size()>1);
            for(var c:plan.chunks()){var snapshot=CanonicalJson.parse(c.snapshotJson());assertEquals(day.toJson(),snapshot.get(FrozenSourceDayDedupDependency.FIELD));assertEquals(plan.scope().stageContextJson(),SourceValueStageContext.fromDaySnapshot(snapshot));assertFalse(snapshot.get(FrozenApplicationContext.FIELD).get("eligible_high_value_item_ids").isEmpty());assertDoesNotThrow(()->NewsValueInputProfile.complete(snapshot));assertFalse(NewsValueInputProfile.modelView(snapshot).has(FrozenSourceDayDedupDependency.FIELD));assertFalse(c.requestJson().contains(FrozenSourceDayDedupDependency.FIELD));}
            var encoded=FrozenSourceStagePlan.encode(plan,x.universe());assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.universe()));
        }
    }
    @Test void lateSourceStillCreatesOrdinaryPartsButNoOriginalBatchHighCandidates(){
        var x=f.fixture(501);var day=day(x.dependency(),f.cutoff,false);var plan=build(x,x.dependency(),day);assertFalse(plan.chunks().isEmpty());
        assertEquals(f.plan(x,new FrozenGenerationSettings(1,List.of(),List.of())).decisions(),plan.decisions());
        for(var c:plan.chunks()){var snapshot=CanonicalJson.parse(c.snapshotJson());assertTrue(snapshot.get(FrozenApplicationContext.FIELD).get("eligible_high_value_item_ids").isEmpty());for(var e:snapshot.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members")){assertFalse(e.get("eligible_high_value").asBoolean());assertEquals(f.day.toString(),e.get("first_seen_business_date").asText());assertFalse(e.get("legacy").asBoolean());}assertDoesNotThrow(()->NewsValueInputProfile.complete(snapshot));}
        var encoded=FrozenSourceStagePlan.encode(plan,x.universe());assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.universe()));
    }
    @Test void originalPartialFailureAndLateBatchBothRemainEffectiveAcrossAllParts(){
        var partial=new NewsValuePartialInputProfileTest();var x=partial.fixture();var day=day(x.partial(),f.cutoff,false);var plan=build(x.original(),x.partial(),day);
        for(var decision:plan.decisions())if(x.failed().contains(decision.itemId()))assertEquals(SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE,decision.reason());
        for(var c:plan.chunks()){var snapshot=CanonicalJson.parse(c.snapshotJson());assertEquals("partial",snapshot.get("coverage_state").asText());assertFalse(snapshot.get("missing").isEmpty());assertTrue(snapshot.get(FrozenApplicationContext.FIELD).get("eligible_high_value_item_ids").isEmpty());assertDoesNotThrow(()->NewsValueInputProfile.complete(snapshot));assertEquals(plan.scope().stageContextJson(),SourceValueStageContext.fromDaySnapshot(snapshot));}
        var encoded=FrozenSourceStagePlan.encode(plan,x.original().universe());assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),x.original().universe()));
    }
    @Test void lateInputCannotRehashItsWayToHighEligibilityOrReplaceOwnDayP1Binding(){
        var x=f.fixture(5);var plan=build(x,x.dependency(),day(x.dependency(),f.cutoff,false));var input=(ObjectNode)CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());
        var high=input.deepCopy();var first=high.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0);((ObjectNode)first).put("eligible_high_value",true);((ObjectNode)high.get(FrozenApplicationContext.FIELD)).putArray("eligible_high_value_item_ids").add(first.get("item_id").asText());assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(high));
        var changed=input.deepCopy();((ObjectNode)changed.get(FrozenSourceDayDedupDependency.FIELD)).put("p1_result_hash","c".repeat(64));assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(changed));
        var scope=input.deepCopy();((ObjectNode)scope.get(FrozenSourceDayDedupDependency.FIELD)).put("closure_hash","d".repeat(64));String raw=f.canonical(scope),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,scope);var old=plan.chunks().getFirst();var chunk=new SourcePartitionPlanner.Chunk(old.stagePart(),old.rootId(),old.itemIds(),raw,hash,new GenerationRequestBuilder().build(plan.stage(),raw,hash));
        var changedPlan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),List.of(chunk),plan.scope());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(changedPlan,x.universe()));
    }
}
