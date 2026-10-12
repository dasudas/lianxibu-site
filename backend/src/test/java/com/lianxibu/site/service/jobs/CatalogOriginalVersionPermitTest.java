package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcEntityAnalysisState;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import java.util.*;
import org.junit.jupiter.api.Test;

class CatalogOriginalVersionPermitTest extends CatalogVersionSourceGraphTest {
    GenerationFlowService.Frozen originalRoot;
    FrozenCatalogVersionProjections.Entry entry;
    void original(String category){source(category);graph();originalRoot=graph.coordinator().prepareDedup(collection.intentId(),RUN,0).root();var n=CanonicalJson.parse(originalRoot.snapshotJson());var input=FrozenApplicationContext.fromJson(n.get(FrozenApplicationContext.FIELD)).input(originalRoot.ownerId(),"fictional-check",originalRoot.inputHash(),0);entry=FrozenCatalogVersionProjections.read(n.get(FrozenCatalogVersionProjections.FIELD),input).entries().get("fictional-current-item");}
    CatalogVersionCommitPermitService guard(){return new CatalogVersionCommitPermitService(f.source,f.tx,f.category);}
    CatalogVersionCommitPermitService.Captured frozen(CatalogVersionCommitPermitService guard){return guard.captureFrozen(originalRoot.ownerId(),originalRoot.inputHash(),entry.itemId());}
    void reserve(){write(()->{var entity=new EntityAnalysisStateService(new JdbcEntityAnalysisState(f.source));var state=entity.reserve("fictional-entity",f.category,f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class),1);var intent=JdbcFrozenInputs.stableId("intent","fictional-analysis-key:"+state.generation());new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent(intent,"fictional-analysis-key",state.generation(),f.category+"_analysis",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});}
    @Test void OriginalInputAndTypedRefsStayExactAfterNativePendingGenerationAdvances(){
        for(String category:List.of("agent","skill","workflow","model")){
            original(category);var guard=guard();var before=frozen(guard);reserve();var fresh=frozen(guard);
            assertEquals(before.projection().json(),fresh.projection().json());assertEquals(entry.hash(),fresh.projection().hash());assertEquals(before.projection().resultConstraint(),fresh.projection().resultConstraint());
            assertTrue(fresh.entityRevision()>before.entityRevision());assertEquals(2,fresh.entityGeneration());assertEquals("queued",fresh.entityState());assertSame(fresh.projection(),write(()->guard.verify(fresh)));
            assertFalse(entry.matches(guard.capture(entry.entityId(),entry.contentId(),entry.itemId(),entry.cutoff()).orElseThrow().projection()));assertEquals(1,f.sends);
        }
    }
    @Test void OriginalRoleHashLeafConfigPreviousProofAndRegressedMetadataStillReject(){
        for(String fault:List.of("body","config","previous","revision","generation","role","hash")){
            original("agent");var guard=guard();reserve();
            switch(fault){case "body"->f.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id='fictional-current-item'");case "config"->f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}' WHERE id=?",RUN);case "previous"->f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],CatalogPreviousAnalysisTest.Fixture.ROOT);case "revision"->f.jdbc.update("UPDATE analyzed_entities SET revision=0");case "generation"->f.jdbc.update("UPDATE analyzed_entities SET analysis_generation=0");case "role"->f.jdbc.update("UPDATE jobs SET task_type='news_dedup' WHERE id=?",originalRoot.ownerId());case "hash"->f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],originalRoot.ownerId());}
            assertThrows(RuntimeException.class,()->frozen(guard));assertEquals(1,f.sends);
        }
    }
    @Test void FreshCaptureStillRequiresItsIssuerWriterAndUnchangedNativeState(){
        original("agent");var guard=guard();reserve();var value=frozen(guard);
        assertThrows(RuntimeException.class,()->guard.verify(value));assertThrows(RuntimeException.class,()->write(()->guard().verify(value)));
        assertThrows(RuntimeException.class,()->write(()->frozen(guard)));
        f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");assertThrows(RuntimeException.class,()->write(()->guard.verify(value)));
        var current=frozen(guard);assertThrows(RuntimeException.class,()->write(()->{guard.verify(current);f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");throw new IllegalStateException("fictional rollback");}));assertSame(current.projection(),write(()->guard.verify(current)));
    }
}
