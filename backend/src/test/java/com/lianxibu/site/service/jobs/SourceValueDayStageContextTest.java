package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceValueDayStageContextTest {
    final FrozenSourceDayDedupDependencyTest f=new FrozenSourceDayDedupDependencyTest();
    @Test void completeEmptyP2RetainsOriginalDayProofWithoutInventingAnyRoot(){
        var own=f.own(f.AVAILABLE);var day=FrozenSourceDayDedupDependency.create(f.OWNER,f.BUNDLE,f.early(own),own,f.EARLY);
        String context=SourceValueStageContext.createDay(own,day,f.DAY,f.EARLY,false);
        assertEquals("source-value-stage-context-v3",CanonicalJson.parse(context).get("schema_version").asText());assertEquals(day.toJson(),CanonicalJson.parse(context).get(FrozenSourceDayDedupDependency.FIELD));
        assertEquals(context,SourceValueStageContext.readAny(context,f.DAY,f.EARLY));
        var universe=FrozenSourceUniverseTest.universe(0,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        var scope=new SourcePartitionPlanner.Scope(f.DAY,f.EARLY,FrozenSourceStagePlanTest.scope().settings(),context);
        var plan=new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_VALUE,NewsValueSourcePlanBuilder.logicalKey(own),1,universe,ref,List.of(),scope,(ids,part,root)->{throw new AssertionError();});
        var encoded=FrozenSourceStagePlan.encode(plan,universe);assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),universe));assertTrue(plan.chunks().isEmpty());
        String body=new String(Base64.getDecoder().decode(CanonicalJson.parse(encoded.payloads().get(1)).get("bytes_base64").asText()),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("source-stage-scope-v4",CanonicalJson.parse(body).get("scope").get("schema_version").asText());
    }
    @Test void completeOrCollectionPartialRebuildsExactDayContextFromNativeSnapshot(){
        var own=f.own(f.AVAILABLE);var day=FrozenSourceDayDedupDependency.create(f.OWNER,f.BUNDLE,f.early(own),own,f.EARLY);
        for(boolean partial:List.of(false,true)){
            String raw=SourceValueStageContext.createDay(own,day,f.DAY,f.EARLY,partial);var context=CanonicalJson.parse(raw);
            var snapshot=(ObjectNode)context.deepCopy();snapshot.remove("schema_version");snapshot.put("stage","news_value").put("business_date",f.DAY.toString()).put("cutoff",f.EARLY.toString());
            assertEquals(raw,SourceValueStageContext.fromDaySnapshot(snapshot));assertEquals(partial?1:0,context.get("missing").size());
            assertThrows(RuntimeException.class,()->SourceValueStageContext.read(raw,f.DAY,f.EARLY));assertThrows(RuntimeException.class,()->SourceValueStageContext.readPartial(raw,f.DAY,f.EARLY));
        }
    }
    @Test void originalPartialFailuresAndLateBatchExclusionSurviveNewContext()throws Exception {
        var partial=new SourceValuePartialStageContextTest();partial.setup(1);partial.f.terminal(0,"failed");var own=partial.dependency();var key=own.toJson().get("stage_key");
        var member=new SourceDedupBarrier.Member(key.get("source_id").asText(),SourceDedupBarrier.Inclusion.ENABLED,3L,f.CONFIG,key.get("run_id").asText(),1L);
        var cohort=SourceDedupBarrier.freeze("news_tech",f.DAY,f.REGISTERED,List.of(member));var closure=SourceDedupBarrier.close(cohort,SourceDedupBarrier.deadline(f.DAY),List.of());
        var day=FrozenSourceDayDedupDependency.create(f.OWNER,f.BUNDLE,closure,own,partial.cutoff());assertFalse(day.originalBatchEligible());
        String raw=SourceValueStageContext.createDay(own,day,f.DAY,partial.cutoff(),true);assertEquals(raw,SourceValueStageContext.readAny(raw,f.DAY,partial.cutoff()));
        var context=CanonicalJson.parse(raw);assertEquals("partial",context.get("coverage_state").asText());assertEquals(2,context.get("missing").size());assertEquals("dedup_failed",context.get("missing").get(0).get("reason").asText());
        var snapshot=partial.snapshot(SourceValueStageContext.create(own,f.DAY,partial.cutoff(),true));snapshot.set(FrozenSourceDayDedupDependency.FIELD,day.toJson());assertEquals(raw,SourceValueStageContext.fromDaySnapshot(snapshot));
        assertEquals(SourceValueStageContext.partialSnapshotMissing(SourceValueStageContext.create(own,f.DAY,partial.cutoff(),true),f.DAY,partial.cutoff()),SourceValueStageContext.partialSnapshotMissing(raw,f.DAY,partial.cutoff()));
    }
    @Test void dayContextRejectsWrongBindingUnknownFieldsMissingDayAndChangedCoverage(){
        var own=f.own(f.AVAILABLE);var day=FrozenSourceDayDedupDependency.create(f.OWNER,f.BUNDLE,f.early(own),own,f.EARLY);String raw=SourceValueStageContext.createDay(own,day,f.DAY,f.EARLY,false);
        for(String change:List.of("extra","schema_version","day","p1","coverage")){
            var node=(ObjectNode)CanonicalJson.parse(raw);switch(change){case "day"->node.remove(FrozenSourceDayDedupDependency.FIELD);case "p1"->((ObjectNode)node.get(FrozenSourceDayDedupDependency.FIELD)).put("p1_result_hash","d".repeat(64));case "coverage"->node.put("coverage_state","partial");default->node.put(change,"fictional-invalid");}
            assertThrows(RuntimeException.class,()->SourceValueStageContext.readAny(f.canonical(node),f.DAY,f.EARLY),change);
        }
        assertThrows(RuntimeException.class,()->SourceValueStageContext.readAny(raw,f.DAY.plusDays(1),f.EARLY));assertThrows(RuntimeException.class,()->SourceValueStageContext.readAny(" "+raw,f.DAY,f.EARLY));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceValueStageContext.readAny(" ".repeat(SourceValueStageContext.DAY_MAX_BYTES+1),f.DAY,f.EARLY));
    }
}
