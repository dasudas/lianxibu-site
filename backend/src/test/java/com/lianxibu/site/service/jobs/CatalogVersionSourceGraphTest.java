package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.CatalogSourceProofGraph;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual production graph over owned fictional native original inputs; no paid model or P7 authority. */
class CatalogVersionSourceGraphTest extends CatalogVersionPlanPreparationTest {
    CatalogSourceProofGraph graph;
    void graph(){graph=new CatalogSourceProofGraph(f.source,f.tx,f.clock,()->new FrozenGenerationSettings(1,List.of(),List.of()),()->true,f.category);collections=graph.collections();universes=graph.universes();plans=graph.plans();preparation=graph.preparation();}
    @Test void FourActualGraphCoordinatorsFreezeKnownVersionsAndOnlyOneOriginalPart(){
        for(String category:List.of("agent","skill","workflow","model")){
            source(category);graph();var prepared=graph.coordinator().prepareDedup(collection.intentId(),RUN,0);var chunk=prepared.plan().chunks().getFirst();var snapshot=CanonicalJson.parse(chunk.snapshotJson());assertEquals("2",snapshot.get(FrozenApplicationContext.FIELD).get("dedup_result_version").asText());assertEquals(1,snapshot.get(FrozenCatalogVersionProjections.FIELD).get("items").size());assertEquals(chunk.snapshotJson(),prepared.root().snapshotJson());assertEquals(chunk.requestJson(),prepared.root().requestJson());
            int before=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);graph();assertEquals(prepared,graph.coordinator().prepareDedup(collection.intentId(),RUN,0));assertEquals(before,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(1,f.sends);assertEquals(1,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        }
    }
    @Test void ActualGraphMissingOldEvidenceKeepsUnknownAndCorruptionRejectsBeforeNewPart(){
        f.jdbc.update("UPDATE contents SET input_job_id=NULL");graph();var prepared=graph.coordinator().prepareDedup(collection.intentId(),RUN,0);assertTrue(CanonicalJson.parse(prepared.plan().chunks().getFirst().snapshotJson()).get(FrozenCatalogVersionProjections.FIELD).get("items").isEmpty());
        source("skill");graph();f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],CatalogPreviousAnalysisTest.Fixture.ROOT);assertThrows(RuntimeException.class,()->graph.coordinator().prepareDedup(collection.intentId(),RUN,0));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",Integer.class));assertEquals(1,f.sends);
    }
    @Test void RebuiltActualGraphUsesFrozenPartBeforeLaterMutableProofs(){
        graph();var original=graph.coordinator().prepareDedup(collection.intentId(),RUN,0);int before=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.jdbc.execute("DROP TABLE analyzed_entities");f.jdbc.execute("DROP TABLE contents");graph();assertEquals(original,graph.coordinator().prepareDedup(collection.intentId(),RUN,0));assertEquals(before,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(1,f.sends);
    }
}
