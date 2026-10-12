package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceDayDedupDependencyTest {
    static final LocalDate DAY=LocalDate.of(2026,1,1);
    static final Instant REGISTERED=Instant.parse("2026-01-01T16:10:00Z"),AVAILABLE=REGISTERED.plusSeconds(600),EARLY=AVAILABLE.plusSeconds(60);
    static final String OWNER="fictional-directory-owner",BUNDLE="a".repeat(64),CONFIG="b".repeat(64);
    FrozenSourceDedupDependency own(Instant available){
        var u=FrozenSourceUniverseTest.universe(0,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,u).reference();
        var scope=new SourcePartitionPlanner.Scope(DAY,REGISTERED.plusSeconds(1),FrozenSourceStagePlanTest.scope().settings());
        var plan=new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,SourcePartitionPlannerTest.KEY,1,u,ref,List.of(),scope,(ids,part,root)->{throw new AssertionError();});
        var original=SourceDedupStageResult.create(plan,u,FrozenSourceStagePlan.encode(plan,u).reference(),List.of());
        return FrozenSourceDedupDependency.create(original,available,available.plusSeconds(600));
    }
    SourceDedupBarrier.Member member(FrozenSourceDedupDependency own){var k=own.toJson().get("stage_key");return new SourceDedupBarrier.Member(k.get("source_id").asText(),SourceDedupBarrier.Inclusion.ENABLED,3L,CONFIG,k.get("run_id").asText(),1L);}
    SourceDedupBarrier.Cohort cohort(FrozenSourceDedupDependency own){return SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,List.of(member(own)));}
    SourceDedupBarrier.Progress fact(FrozenSourceDedupDependency own){var m=member(own);return new SourceDedupBarrier.Progress("news_tech",DAY,m.sourceId(),3,CONFIG,m.runId(),1,SourceDedupBarrier.State.EMPTY_SUCCESS,own.observedAt(),own.toJson().get("stage_key").get("result_hash").asText());}
    SourceDedupBarrier.Closure early(FrozenSourceDedupDependency own){return SourceDedupBarrier.close(cohort(own),EARLY,List.of(fact(own)));}
    static String canonical(tools.jackson.databind.JsonNode n){return new String(CanonicalJson.bytes(n),StandardCharsets.UTF_8);}

    @Test void includedOriginalHasSmallDetachedReferenceAndExactOwnP1Binding(){
        var own=own(AVAILABLE);var closed=early(own);var value=FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,closed,own,EARLY);
        assertTrue(value.originalBatchEligible());assertEquals(closed.hash(),value.toJson().get("closure_hash").asText());assertEquals(BUNDLE,value.toJson().get("bundle_hash").asText());
        assertEquals(own.toJson().get("stage_key").get("result_hash"),value.toJson().get("p1_result_hash"));assertEquals(AVAILABLE,value.availableAt());assertEquals(EARLY,value.closedAt());
        assertTrue(value.json().getBytes(StandardCharsets.UTF_8).length<=4096);assertFalse(value.toJson().has("closure"));assertFalse(value.toJson().has("reports"));
        assertEquals(value.json(),FrozenSourceDayDedupDependency.read(value.json(),own,EARLY).json());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,value.toJson()),value.hash());
        ((ObjectNode)value.toJson()).put("extra",true);assertFalse(value.toJson().has("extra"));assertEquals("FrozenSourceDayDedupDependency[redacted]",value.toString());
    }
    @Test void lateOrdinaryWorkKeepsOriginalBatchExcludedWithoutChangingOriginalClosure(){
        var deadline=SourceDedupBarrier.deadline(DAY);var own=own(deadline.plusNanos(1000));
        for(boolean alreadyLate:List.of(false,true)){
            var closed=SourceDedupBarrier.close(cohort(own),deadline.plusSeconds(10),alreadyLate?List.of(fact(own)):List.of());String original=closed.json();
            var value=FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,closed,own,deadline.plusSeconds(20));assertFalse(value.originalBatchEligible());
            assertEquals(deadline,value.cutoff());assertEquals(original,closed.json());assertEquals(value.json(),FrozenSourceDayDedupDependency.read(value.json(),own,deadline.plusSeconds(20)).json());
        }
    }
    @Test void missingOnTimeProofCannotBeUpgradedOrPretendToBeLate(){
        var own=own(AVAILABLE);var closed=SourceDedupBarrier.close(cohort(own),SourceDedupBarrier.deadline(DAY),List.of());
        assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,closed,own,SourceDedupBarrier.deadline(DAY)));
        var early=early(own);var later=own(AVAILABLE.plusNanos(1000));assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,early,later,EARLY));
        var altered=new SourceDedupBarrier.Progress("news_tech",DAY,member(own).sourceId(),3,CONFIG,member(own).runId(),1,SourceDedupBarrier.State.EMPTY_SUCCESS,AVAILABLE,"c".repeat(64));
        var changed=SourceDedupBarrier.close(cohort(own),EARLY,List.of(altered));assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,changed,own,EARLY));
    }
    @Test void excludedAbsentWrongCategoryAndGenerationDoNotBecomeP2Dependencies(){
        var own=own(AVAILABLE);var m=member(own);var disabled=new SourceDedupBarrier.Member(m.sourceId(),SourceDedupBarrier.Inclusion.DISABLED,3L,CONFIG,null,null);
        for(var c:List.of(SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,List.of(disabled)),SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,List.of()),SourceDedupBarrier.freeze("agent",DAY,REGISTERED,List.of(m)))){
            var closed=SourceDedupBarrier.close(c,SourceDedupBarrier.deadline(DAY),List.of());assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,closed,own,SourceDedupBarrier.deadline(DAY)));
        }
        var wrong=new SourceDedupBarrier.Member(m.sourceId(),SourceDedupBarrier.Inclusion.ENABLED,3L,CONFIG,m.runId(),2L);
        var c=SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,List.of(wrong));var closed=SourceDedupBarrier.close(c,SourceDedupBarrier.deadline(DAY),List.of());
        assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,closed,own,SourceDedupBarrier.deadline(DAY)));
    }
    @Test void strictClosedCanonicalShapeOwnHashesAndMicrosecondTimesRejectMutation(){
        var own=own(AVAILABLE);var value=FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,early(own),own,EARLY);
        for(String field:List.of("schema_version","category","business_date","run_id","source_id","run_generation","config_revision","config_hash","closure_hash","p1_result_hash","available_at","closed_at","cutoff","original_batch_eligible")){
            var node=(ObjectNode)value.toJson();switch(field){case "run_generation"->node.put(field,2);case "config_revision"->node.put(field,-1);case "original_batch_eligible"->node.put(field,false);case "p1_result_hash"->node.put(field,"c".repeat(64));case "available_at"->node.put(field,"2026-01-01T16:20:00.000000001Z");case "closed_at"->node.put(field,"2026-01-01T16:09:00.000000Z");case "cutoff"->node.put(field,"2026-01-01T20:29:00.000000Z");default->node.put(field,"fictional-invalid");}
            assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.read(canonical(node),own,EARLY),field);
        }
        var extra=(ObjectNode)value.toJson();extra.put("extra",true);assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.read(canonical(extra),own,EARLY));
        assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.read(" "+value.json(),own,EARLY));assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.read(value.json(),own,EARLY.minusNanos(1000)));
        assertThrows(RuntimeException.class,()->FrozenSourceDayDedupDependency.read(value.json(),own,EARLY.plusNanos(1)));assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceDayDedupDependency.read(" ".repeat(4097),own,EARLY));
    }
    @Test void detachedShapeValidationIsExplicitlyInsufficientToAuthorizeSQLOrModelWork(){
        var own=own(AVAILABLE);var value=FrozenSourceDayDedupDependency.create(OWNER,BUNDLE,early(own),own,EARLY);
        var fabricated=(ObjectNode)value.toJson();fabricated.put("closure_hash","d".repeat(64));
        // A valid hash cannot prove an archive exists. The actual consumer must reject this against SQL.
        assertNotEquals(value.hash(),FrozenSourceDayDedupDependency.read(canonical(fabricated),own,EARLY).hash());
    }
}
