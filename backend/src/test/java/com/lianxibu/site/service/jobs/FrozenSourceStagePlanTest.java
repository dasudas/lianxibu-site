package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceStagePlanTest {
    SourcePartitionPlanner.Plan plan(MemberUniverse.Universe universe,String body) {
        var plan=new SourcePartitionPlannerTest().plan(universe,body);
        return new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),plan.chunks(),scope());
    }
    static SourcePartitionPlanner.Scope scope(){return new SourcePartitionPlanner.Scope(java.time.LocalDate.of(2026,1,1),java.time.Instant.parse("2026-01-01T21:00:00Z"),new FrozenGenerationSettings(3,List.of("fictional-focus"),List.of("fictional-block")));}
    @Test void completeMultiPartPlanAndExactRequestsRoundTripWithStableExternalReference() {
        var universe=FrozenSourceUniverseTest.universe(251,true);var plan=plan(universe,"Fictional unicode 原始证据".repeat(80));
        var encoded=FrozenSourceStagePlan.encode(plan,universe);
        assertTrue(encoded.payloads().size()>2);assertEquals(251,plan.decisions().size());assertEquals(2,plan.chunks().size());
        assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),universe));
        assertEquals(encoded,FrozenSourceStagePlan.encode(plan,universe));
        assertEquals(encoded.reference(),FrozenSourceStagePlan.Reference.fromJson(encoded.reference().json()));
        assertTrue(encoded.reference().json().length()<512);assertFalse(encoded.toString().contains("原始证据"));
    }
    @Test void originalDecisionSetAndEligibleUnionMustBeCompleteAndNonoverlapping() {
        var universe=FrozenSourceUniverseTest.universe(251,false);var original=plan(universe,"Fictional evidence");
        var missing=new ArrayList<>(original.decisions());missing.removeLast();
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),missing,original.chunks()));
        var reversed=new ArrayList<>(original.decisions());Collections.reverse(reversed);
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),reversed,original.chunks()));
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),original.decisions(),List.of(original.chunks().getFirst())));
        var duplicate=new ArrayList<>(original.chunks());duplicate.add(original.chunks().getLast());
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),original.decisions(),duplicate));
        var bad=new ArrayList<>(original.decisions());bad.set(1,new SourcePartitionPlanner.Decision(bad.get(1).itemId(),SourcePartitionPlanner.Reason.ELIGIBLE));
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),bad,original.chunks()));
    }
    @Test void changedRootRequestHashPartOrSnapshotCannotBecomeAnOriginalChunk() {
        var universe=FrozenSourceUniverseTest.universe(3,false);var original=plan(universe,"Fictional evidence");var chunk=original.chunks().getFirst();
        var changed=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());changed.put("stage_part",1);
        for(var bad:List.of(
                new SourcePartitionPlanner.Chunk(1,chunk.rootId(),chunk.itemIds(),chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson()),
                new SourcePartitionPlanner.Chunk(0,"fictional-replacement-root",chunk.itemIds(),chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson()),
                new SourcePartitionPlanner.Chunk(0,chunk.rootId(),chunk.itemIds(),chunk.snapshotJson(),"b".repeat(64),chunk.requestJson()),
                new SourcePartitionPlanner.Chunk(0,chunk.rootId(),chunk.itemIds(),chunk.snapshotJson(),chunk.inputHash(),chunk.requestJson().replace("deepseek-flash","fictional-other-model")),
                new SourcePartitionPlanner.Chunk(0,chunk.rootId(),chunk.itemIds(),changed.toString(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,changed),new GenerationRequestBuilder().build(original.stage(),changed.toString(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,changed)))))
            assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),original.decisions(),List.of(bad)));
    }
    @Test void missingSurplusReorderedAndLocallyRehashedBodiesCannotReplaceTheExternalReference() {
        var universe=FrozenSourceUniverseTest.universe(101,true);var encoded=FrozenSourceStagePlan.encode(plan(universe,"Fictional evidence".repeat(300)),universe);
        var parts=new ArrayList<>(encoded.payloads());parts.removeLast();assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(encoded.reference(),parts,universe));
        parts.clear();parts.addAll(encoded.payloads());parts.add(parts.getLast());assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(encoded.reference(),parts,universe));
        parts.clear();parts.addAll(encoded.payloads());Collections.swap(parts,1,2);assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(encoded.reference(),parts,universe));
        parts.clear();parts.addAll(encoded.payloads());
        var chunk=(ObjectNode)CanonicalJson.parse(parts.get(1));String body=new String(Base64.getDecoder().decode(chunk.path("bytes_base64").asText()),java.nio.charset.StandardCharsets.UTF_8);
        chunk.put("bytes_base64",Base64.getEncoder().encodeToString(body.replace("Fictional evidence","Fictional replaced").getBytes(java.nio.charset.StandardCharsets.UTF_8)));parts.set(1,chunk.toString());
        var index=(ObjectNode)CanonicalJson.parse(parts.getFirst());((ObjectNode)index.path("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));parts.set(0,index.toString());
        assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(encoded.reference(),parts,universe));
        var external=new FrozenSourceStagePlan.Reference("fictional-other-owner",encoded.reference().planHash(),encoded.reference().indexHash());
        assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(external,encoded.payloads(),universe));
    }
    @Test void individuallyValidChunksCannotMixDifferentBusinessDatesOrCutoffs() {
        var universe=FrozenSourceUniverseTest.universe(251,false);var plan=plan(universe,"Fictional evidence");var chunk=plan.chunks().get(1);
        for(String field:List.of("business_date","cutoff")) {
            var snapshot=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());snapshot.put(field,field.equals("business_date")?"2026-01-02":"2026-01-02T21:00:00Z");
            String raw=new String(CanonicalJson.bytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
            var changed=new SourcePartitionPlanner.Chunk(chunk.stagePart(),chunk.rootId(),chunk.itemIds(),raw,hash,new GenerationRequestBuilder().build(plan.stage(),raw,hash));
            assertRejected(universe,new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),plan.generation(),plan.reference(),plan.decisions(),List.of(plan.chunks().getFirst(),changed)));
        }
    }
    @Test void emptyPlanStillFreezesAllOriginalDecisionsAndClosedSchemasRejectExtraFields() {
        var universe=FrozenSourceUniverseTest.universe(3,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        var decisions=universe.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).sorted(Comparator.comparing(SourcePartitionPlanner.Decision::itemId)).toList();
        var plan=new SourcePartitionPlanner.Plan(SourcePartitionPlannerTest.STAGE,SourcePartitionPlannerTest.KEY,1,ref,decisions,List.of(),scope());
        var encoded=FrozenSourceStagePlan.encode(plan,universe);assertEquals(plan,FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),universe));assertEquals(2,encoded.payloads().size());
        var zero=FrozenSourceUniverseTest.universe(0,false);assertTrue(FrozenSourceStagePlan.decode(FrozenSourceStagePlan.encode(plan(zero,"Fictional"),zero).reference(),FrozenSourceStagePlan.encode(plan(zero,"Fictional"),zero).payloads(),zero).decisions().isEmpty());
        assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.Reference.fromJson(encoded.reference().json().replace("{","{\"extra\":true,")));
        var index=(ObjectNode)CanonicalJson.parse(encoded.payloads().getFirst());index.put("byte_count",FrozenSourceStagePlan.MAX_BYTES+1L);
        var parts=new ArrayList<>(encoded.payloads());parts.set(0,index.toString());
        var forged=new FrozenSourceStagePlan.Reference(encoded.reference().ownerId(),encoded.reference().planHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index));
        assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.decode(forged,parts,universe));
    }
    @Test void noEligibleMembersKeepOriginalBusinessDateCutoffAndCompleteSettings() {
        var universe=FrozenSourceUniverseTest.universe(0,false);var original=plan(universe,"Fictional");var encoded=FrozenSourceStagePlan.encode(original,universe);
        assertEquals(scope(),FrozenSourceStagePlan.decode(encoded.reference(),encoded.payloads(),universe).scope());
        var next=new SourcePartitionPlanner.Scope(scope().businessDate(),scope().cutoff().plusSeconds(1),scope().settings());
        var changed=new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),original.decisions(),List.of(),next);
        assertNotEquals(encoded.reference(),FrozenSourceStagePlan.encode(changed,universe).reference());
        assertRejected(universe,new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,original.reference(),original.decisions(),List.of()));
    }
    void assertRejected(MemberUniverse.Universe universe,SourcePartitionPlanner.Plan plan){assertThrows(RuntimeException.class,()->FrozenSourceStagePlan.encode(plan,universe));}
}
