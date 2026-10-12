package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourcePartitionProofServiceTest {
    FrozenSourceStagePlanServiceTest f;SourcePartitionProofService proof;
    FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest();}
    @BeforeEach void setup(){f=fixture();f.setup();f.freeze();proof=new SourcePartitionProofService(f.service);}
    @Test void eachActualOriginalPartIsAuthorizedFromTheCompletePermanentPlanWithoutNewSourceReads() {
        int before=f.count(),reads=f.sourceReads;assertEquals(2,f.plan.chunks().size());
        for(var chunk:f.plan.chunks())assertEquals(chunk,f.tx(()->proof.require(chunk.rootId(),chunk.snapshotJson(),chunk.inputHash())));
        assertEquals(before,f.count());assertEquals(reads,f.sourceReads);
    }
    @Test void changedRehashedInputOtherRootAndMalformedPartitionCannotReplaceTheOriginal() {
        var chunk=f.plan.chunks().getFirst();
        for(String mode:List.of("member","part","owner","closed","missing","generation")) {
            var input=(ObjectNode)CanonicalJson.parse(chunk.snapshotJson());
            switch(mode) {
                case "member"->((ObjectNode)input.path("members").get(0)).put("id","fictional-other-item");
                case "part"->((ObjectNode)input.get(SourcePartitionPlanner.FIELD)).put("stage_part",1);
                case "owner"->((ObjectNode)input.get(SourcePartitionPlanner.FIELD).get("universe_ref")).put("owner_job_id","fictional-other-owner");
                case "closed"->((ObjectNode)input.get(SourcePartitionPlanner.FIELD)).put("extra",true);
                case "missing"->input.remove(SourcePartitionPlanner.FIELD);
                case "generation"->input.put("generation",2);
            }
            String raw=new String(CanonicalJson.bytes(input),java.nio.charset.StandardCharsets.UTF_8),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);
            assertThrows(RuntimeException.class,()->f.tx(()->proof.require(chunk.rootId(),raw,hash)));
        }
        assertThrows(RuntimeException.class,()->f.tx(()->proof.require("fictional-other-root",chunk.snapshotJson(),chunk.inputHash())));
        assertThrows(RuntimeException.class,()->f.tx(()->proof.require(chunk.rootId(),chunk.snapshotJson(),"b".repeat(64))));
    }
    @Test void missingPermanentPlanAndNonWriterCannotGrantAValidLookingInput() {
        var chunk=f.plan.chunks().getFirst();assertThrows(RuntimeException.class,()->proof.require(chunk.rootId(),chunk.snapshotJson(),chunk.inputHash()));
        f.fixture.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");
        assertThrows(RuntimeException.class,()->f.tx(()->proof.require(chunk.rootId(),chunk.snapshotJson(),chunk.inputHash())));
        assertEquals(0,f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));
    }
}
