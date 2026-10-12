package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Production assembly consuming real partial archives; retained outcomes are fictional. */
class SourceParentRuntimeProofTest {
    SourcePartialParentStepTest f;
    SourceAnalysisProofGraph graph;
    SourceAnalysisParentStepService parent;
    SourcePlanPreparationCoordinator preparation;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(int count,boolean mixed,boolean applied)throws Exception{
        f=new SourcePartialParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceParentRuntimeProofTest.this.database();}};
        f.setup(count,mixed);var plan=f.q.prepare(0).plan();for(int n=1;n<plan.chunks().size();n++)f.q.prepare(n);if(applied)f.complete(true);
        f.db.jdbc.update("UPDATE jobs SET operator_id='analysis-worker' WHERE target_type='generation_prepare'");
        preparation=mock(SourcePlanPreparationCoordinator.class);wire(f.clock);
    }
    void wire(Clock clock){var tx=new DataSourceTransactionManager(f.db.source);graph=new SourceAnalysisProofGraph(f.db.source,tx,clock,()->false);parent=NewsSourcePreparationConfiguration.parentStep(f.db.source,tx,clock,preparation,graph);}
    void step(SourceAnalysisParentStepService.Status expected){assertEquals(expected,parent.step(f.candidate(f.q.intent)).status());}
    @Test void ActualPartial101GraphSealsFinalizesAndReplaysWithoutCurrentRowsOrNewInput()throws Exception{
        setup(101,true,true);String collector=f.collector("result_json"),coverage=f.collector("coverage_json");int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);step(SourceAnalysisParentStepService.Status.FINALIZED);
        assertNotNull(f.read(()->graph.valueSeals().readCommitted(f.ref())));assertNotNull(f.read(()->graph.finalization().readCommitted(f.ref())));assertEquals(1,f.db.count("target_type='source_value_final_availability'"));assertEquals("partial",f.db.jdbc.queryForObject("SELECT analysis_state FROM source_runs",String.class));assertEquals("succeeded",f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.q.intent));assertEquals(collector,f.collector("result_json"));assertEquals(coverage,f.collector("coverage_json"));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        int rows=f.db.count("1=1");long revision=f.db.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");wire(f.clock);step(SourceAnalysisParentStepService.Status.FINALIZED);assertEquals(rows,f.db.count("1=1"));assertEquals(revision,f.db.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));verifyNoInteractions(preparation);
    }
    @Test void OriginalZeroValuePlanRecoversOnlyItsMissingFinalObservation()throws Exception{
        setup(1,false,false);assertTrue(f.read(()->graph.plans().readImmutable(f.ref())).chunks().isEmpty());assertNotNull(graph.valueSeals().seal(f.ref()));assertNotNull(graph.finalization().finish(f.ref()));assertEquals(0,f.db.count("target_type='source_value_final_availability'"));
        var later=Clock.fixed(f.clock.instant().plusSeconds(60),ZoneOffset.UTC);wire(later);step(SourceAnalysisParentStepService.Status.FINALIZED);var proof=f.read(()->graph.finalization().readCommitted(f.ref()));assertEquals(later.instant(),f.read(()->graph.finalObserver().availableAt(proof,later.instant())));assertEquals(0,f.db.count("record_kind='logical_task' AND task_type='news_value'"));assertEquals(3,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));verifyNoInteractions(preparation);
    }
    @Test void OriginalNativeChangesAndNestedTransactionCannotClaimFinalization()throws Exception{
        setup(1,false,false);var candidate=f.candidate(f.q.intent);assertThrows(RuntimeException.class,()->f.read(()->parent.step(candidate)));String p1=f.q.q.a.f.plan.chunks().getFirst().rootId();f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);assertThrows(RuntimeException.class,()->parent.step(candidate));assertEquals(0,f.db.count("target_type IN ('source_value_final_result','source_value_final_availability')"));verifyNoInteractions(preparation);
    }
}
