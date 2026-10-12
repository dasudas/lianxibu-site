package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.CatalogSourceProofGraph;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual graph providers; successful fragment archive application remains a fictional fixture. */
class CatalogSourceProofGraphTest {
    CatalogSourceFinalProofTest f;CatalogSourceProofGraph graph;boolean allowed=true;String category;int settingsReads;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void install(String category,boolean empty)throws Exception{
        this.category=category;var fixture=new CatalogSourceFinalizationTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceProofGraphTest.this.database();}};fixture.install(category,empty);f=fixture.f;
        runtimeProducer();graph=provider(category);
    }
    void runtimeProducer(){f.f.jdbc.update("UPDATE jobs SET operator_id='analysis-worker' WHERE target_type='generation_prepare'");}
    CatalogSourceProofGraph provider(String category){return new CatalogSourceProofGraph(f.f.source,new DataSourceTransactionManager(f.f.source),f.f.clock,()->{settingsReads++;return new FrozenGenerationSettings(7,List.of("Fictional watch"),List.of("Fictional block"));},()->allowed,category);}
    JdbcSourceIntentScan.Candidate candidate(){return f.results.readonly(()->new JdbcCatalogSourceIntentScan(f.f.source,category).find(f.ref.ownerId()));}
    @Test void ClosedGraphConstructionRequiresEveryPortAndPerformsZeroIo(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var clock=mock(Clock.class);var settings=mock(GenerationFlowService.SettingsSnapshotSource.class);var gate=mock(java.util.function.BooleanSupplier.class);
        for(String category:List.of("agent","skill","workflow","model")){var g=new CatalogSourceProofGraph(source,tx,clock,settings,gate,category);assertEquals(category,g.category());assertNotNull(g.collections());assertNotNull(g.universes());assertNotNull(g.plans());assertNotNull(g.births());assertNotNull(g.parts());assertNotNull(g.resolutions());assertNotNull(g.partials());assertNotNull(g.whole());assertNotNull(g.finals());assertNotNull(g.archives());assertNotNull(g.receipts());assertNotNull(g.permits());assertNotNull(g.finalizer());assertNotNull(g.observer());assertNotNull(g.preparation());assertNotNull(g.coordinator());assertNotNull(g.origins());assertNotNull(g.parent());assertSame(g.cycle(),g.cycle());}
        for(String category:List.of("news_tech","AGENT","report",""))assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(source,tx,clock,settings,gate,category));
        assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(null,tx,clock,settings,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(source,null,clock,settings,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(source,tx,null,settings,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(source,tx,clock,null,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogSourceProofGraph(source,tx,clock,settings,null,"agent"));
        verifyNoInteractions(source,tx,clock,settings,gate);
    }
    @Test void FourActualGraphsWaitThenFinalizeAndReplayAfterCurrentInputsDisappear()throws Exception{
        for(String category:List.of("agent","skill","workflow","model")){install(category,false);assertEquals(CatalogSourcePreparationCycle.Outcome.PENDING,graph.cycle().run(()->true));assertEquals(0,f.f.count("target_type='catalog_source_final_result'"));f.complete();var finished=graph.finalizer().finish(f.ref);assertNotNull(finished);assertNotNull(graph.observer().capture(f.ref));assertEquals("succeeded",f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));int rows=f.f.count("1=1"),calls=f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class),reads=settingsReads;f.f.jdbc.execute("DROP TABLE source_run_items");f.f.jdbc.execute("DROP TABLE analyzed_entities");allowed=false;graph=provider(category);assertEquals(CatalogSourcePreparationCycle.Outcome.FINALIZED,graph.cycle().run(()->true));assertEquals(finished.json(),graph.finalizer().finish(f.ref).json());assertEquals(rows,f.f.count("1=1"));assertEquals(calls,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(reads,settingsReads);allowed=true;}
    }
    @Test void ActualZeroCollectionFinishesWithoutRootAndFailedBudgetProducesAnActualPartialSeal()throws Exception{
        for(String category:List.of("agent","skill","workflow","model")){install(category,true);assertEquals(CatalogSourcePreparationCycle.Outcome.FINALIZED,graph.cycle().run(()->true));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals("no_input",f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(1,f.f.count("target_type='catalog_final_availability'"));}
        install("agent",false);f.f.jdbc.update("UPDATE jobs SET state='failed',call_count=3 WHERE id=?",f.results.chunk.rootId());assertEquals(CatalogSourcePreparationCycle.Outcome.FINALIZED,graph.cycle().run(()->true));assertNotNull(f.results.readonly(()->graph.partials().readCommitted(f.ref)));assertEquals("partial",f.f.jdbc.queryForObject("SELECT analysis_state FROM source_runs",String.class));assertEquals(3,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void UnpreparedActualOriginCapturesMappedSettingsOnceAndGateResumesWithoutBudgetUse()throws Exception{
        install("skill",false);String intent=f.ref.ownerId(),origin=f.prepared.collection.dispositionId();f.f.jdbc.update("DELETE FROM jobs WHERE id<>? AND id<>?",intent,origin);f.f.jdbc.update("UPDATE jobs SET state='waiting_input',input_state='waiting',input_hash=NULL WHERE id=?",intent);f.f.jdbc.update("UPDATE source_runs SET job_refs_json=?",JdbcFrozenInputs.canonical(tools.jackson.databind.json.JsonMapper.shared().createArrayNode().add(intent)));
        allowed=false;assertEquals(CatalogSourcePreparationCycle.Outcome.GATED,graph.cycle().run(()->true));assertEquals(0,f.f.count("target_type='source_stage_plan'"));assertEquals(0,settingsReads);allowed=true;assertEquals(CatalogSourcePreparationCycle.Outcome.PREPARED,graph.cycle().run(()->true));assertEquals(2,settingsReads);var plan=f.results.readonly(()->graph.plans().readImmutable(intent));assertEquals(new FrozenGenerationSettings(7,List.of("Fictional watch"),List.of("Fictional block")),plan.scope().settings());assertEquals(1,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));allowed=false;graph=provider(category);assertEquals(CatalogSourcePreparationCycle.Outcome.PENDING,graph.cycle().run(()->true));assertEquals(2,settingsReads);
    }
    @Test void ActualObserverFailureRestartsOnlyObservationAndForeignGraphCannotCommit()throws Exception{
        install("workflow",false);f.complete();assertThrows(RuntimeException.class,()->provider("model").parent().step(candidate()));assertEquals(0,f.f.count("target_type='catalog_source_final_result'"));
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_graph_observer_failure CHECK(target_type IS NULL OR target_type<>'catalog_final_availability')");assertEquals(CatalogSourcePreparationCycle.Outcome.RECOVERY_REQUIRED,graph.cycle().run(()->true));assertEquals(1,f.f.count("target_type='catalog_source_final_result'"));assertEquals(0,f.f.count("target_type='catalog_final_availability'"));long revision=f.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_graph_observer_failure":"ALTER TABLE jobs DROP CHECK fictional_graph_observer_failure");graph=provider(category);assertEquals(CatalogSourcePreparationCycle.Outcome.FINALIZED,graph.cycle().run(()->true));assertEquals(revision,f.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void BadActualOriginalRoleAndOuterTransactionCannotProduceFinalProjection()throws Exception{
        install("model",false);f.complete();int rows=f.f.count("1=1");assertEquals(CatalogSourcePreparationCycle.Outcome.RECOVERY_REQUIRED,f.prepared.tx(()->graph.cycle().run(()->true)));f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",f.prepared.collection.dispositionId());assertEquals(CatalogSourcePreparationCycle.Outcome.RECOVERY_REQUIRED,graph.cycle().run(()->true));assertEquals(rows,f.f.count("1=1"));assertNull(f.f.jdbc.queryForObject("SELECT dedup_summary FROM source_runs",String.class));assertEquals(0,f.f.count("target_type='catalog_source_final_result'"));
    }
}
