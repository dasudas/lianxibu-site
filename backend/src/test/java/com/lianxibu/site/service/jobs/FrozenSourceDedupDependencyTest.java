package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceDedupDependencyTest {
    final SourceDedupStageResultTest f=new SourceDedupStageResultTest();
    final Instant observed=Instant.parse("2026-01-02T00:01:00.000001Z"),cutoff=Instant.parse("2026-01-02T00:02:00Z");
    SourceDedupStageResult original(int count) {
        var universe=FrozenSourceUniverseTest.universe(count,false);var plan=f.f.plan(universe);
        if(plan.scope()==null)plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),plan.chunks(),FrozenSourceStagePlanTest.scope());
        return SourceDedupStageResult.create(plan,universe,f.ref(plan,universe),f.proofs(plan,universe));
    }
    @Test void allOriginalReportsRootsAndImmutableProofHashesArePreserved() {
        var original=original(203);var dependency=FrozenSourceDedupDependency.create(original,observed,cutoff);var node=dependency.toJson();
        assertEquals(2,node.get("reports").size());assertEquals(original.observationKeyJson(),canonical(node.get("stage_key")));assertEquals(observed,dependency.observedAt());
        assertEquals(original.hash(),node.get("stage_key").get("result_hash").asText());assertEquals("Fictional fragment summary",node.get("reports").get(1).get("summary").asText());
        assertEquals(2,dependency.dependencies().size());assertTrue(dependency.dependencies().stream().allMatch(d->d.stage()==GenerationFlowService.Stage.NEWS_DEDUP&&d.completedAt().equals(observed)&&d.state().equals("succeeded")));
        assertEquals(dependency.json(),FrozenSourceDedupDependency.read(node,original,cutoff).json());assertFalse(dependency.toString().contains("Fictional fragment summary"));
        assertEquals(dependency.json(),FrozenSourceDedupDependency.read(node,cutoff).json());
        assertThrows(UnsupportedOperationException.class,()->dependency.dependencies().clear());((ObjectNode)node).put("fictional_extra",true);assertFalse(dependency.toJson().has("fictional_extra"));
    }
    @Test void zeroPartsRetainTheOriginalCompleteIdentityWithoutInventingRootDependencies() {
        var original=original(0);var dependency=FrozenSourceDedupDependency.create(original,observed,cutoff);
        assertTrue(dependency.dependencies().isEmpty());assertEquals(0,dependency.toJson().get("reports").size());assertEquals(original.hash(),dependency.toJson().get("stage_key").get("result_hash").asText());
        assertEquals(dependency.json(),FrozenSourceDedupDependency.read(dependency.toJson(),original,cutoff).json());
    }
    @Test void replacingReportsTimeOrCompleteOriginalProofFailsEvenWithPlausibleHashes() {
        var original=original(203);var dependency=FrozenSourceDedupDependency.create(original,observed,cutoff);
        for(String field:List.of("summary","result_hash","input_hash","handoff_hash","root_id")) {
            var changed=(ObjectNode)dependency.toJson();((ObjectNode)changed.get("reports").get(0)).put(field,field.endsWith("hash")?"b".repeat(64):"Fictional replaced");assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(changed,original,cutoff));
        }
        var changed=(ObjectNode)dependency.toJson();((tools.jackson.databind.node.ArrayNode)changed.get("reports")).remove(1);assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(changed,original,cutoff));
        var extra=(ObjectNode)dependency.toJson();extra.put("extra",true);assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(extra,original,cutoff));
        assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(dependency.toJson(),original(5),cutoff));
    }
    @Test void strictMicrosecondObservedAvailabilityAndAggregateCapacityAreRequired() {
        var original=original(5);
        var beforeOriginal=Instant.parse(CanonicalJson.parse(original.json()).get("cutoff").asText()).minusNanos(1000);
        for(var time:List.of(observed.plusNanos(1),cutoff.plusNanos(1000),beforeOriginal))assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.create(original,time,cutoff));
        assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.create(original,observed,cutoff.plusNanos(1)));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceDedupDependency.createWithinCapacity(original,observed,cutoff,1));
        assertDoesNotThrow(()->FrozenSourceDedupDependency.create(original,observed,observed));
    }
    @Test void detachedProofRequiresFullContentHashAndExactlyDerivedReports() {
        var original=original(203);var profile=FrozenSourceDedupDependency.create(original,observed,cutoff).toJson();
        var changed=(ObjectNode)profile.deepCopy();((ObjectNode)changed.get("stage_result").get("fragments").get(0).get("payload")).put("summary","Fictional replacement");assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(changed,cutoff));
        var reports=(ObjectNode)profile.deepCopy();((ObjectNode)reports.get("reports").get(0)).put("summary","Fictional replacement");assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(reports,cutoff));
        var bad=(ObjectNode)profile.deepCopy();((ObjectNode)bad.get("stage_key")).put("extra",true);assertThrows(RuntimeException.class,()->FrozenSourceDedupDependency.read(bad,cutoff));
    }
    static String canonical(tools.jackson.databind.JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
}
