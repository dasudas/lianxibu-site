package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.SourceAnalysisProofGraph;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual assembled proof providers and retained fictional outcomes; no external model call. */
class SourceAnalysisProofGraphTest {
    SourceValuePartialIntentHandoffTest h;
    SourcePartialParentStepTest p;
    SourceAnalysisProofGraph graph;
    boolean allowed=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourceAnalysisProofGraph provider(GenerationJdbcStoreTest db,Clock clock){return new SourceAnalysisProofGraph(db.source,new DataSourceTransactionManager(db.source),clock,()->allowed);}
    void handoff(int count,boolean mixed)throws Exception{
        h=new SourceValuePartialIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return SourceAnalysisProofGraphTest.this.database();}};
        h.setup(count,mixed);runtimeProducer(h.h.f.f);graph=provider(h.h.f.f,h.h.f.clock);
    }
    void value(int count,boolean mixed)throws Exception{
        p=new SourcePartialParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceAnalysisProofGraphTest.this.database();}};
        p.setup(count,mixed);var prepared=p.q.prepare(0);for(int n=1;n<prepared.plan().chunks().size();n++)p.q.prepare(n);runtimeProducer(p.db);graph=provider(p.db,p.clock);
    }
    // Older fixtures produce receipts as fictional-owner. Runtime uses its fixed producer role.
    void runtimeProducer(GenerationJdbcStoreTest db){db.jdbc.update("UPDATE jobs SET operator_id='analysis-worker' WHERE target_type='generation_prepare'");}
    @Test void ConstructionIsLazyAndNullPortsCannotBuildAWorkingGraph(){
        var ds=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var clock=mock(Clock.class);var gate=mock(java.util.function.BooleanSupplier.class);
        var assembled=new SourceAnalysisProofGraph(ds,tx,clock,gate);
        assertNotNull(assembled.collections());assertNotNull(assembled.universes());assertNotNull(assembled.plans());assertNotNull(assembled.births());assertNotNull(assembled.parts());assertNotNull(assembled.resolutions());assertNotNull(assembled.dedupSeals());assertNotNull(assembled.dedupProofs());assertNotNull(assembled.dedupObserver());assertNotNull(assembled.dedupPartials());assertNotNull(assembled.dedupPermits());assertNotNull(assembled.dependencies());assertNotNull(assembled.valueSeals());assertNotNull(assembled.valueProofs());assertNotNull(assembled.valueObserver());assertNotNull(assembled.handoffReceipts());assertNotNull(assembled.handoffs());assertNotNull(assembled.finalProofs());assertNotNull(assembled.finalArchives());assertNotNull(assembled.finalReceipts());assertNotNull(assembled.finalPermits());assertNotNull(assembled.finalization());assertNotNull(assembled.finalObserver());assertSame(assembled.dependencies(),assembled.dependencies());
        verifyNoInteractions(ds,tx,clock,gate);
        assertThrows(RuntimeException.class,()->new SourceAnalysisProofGraph(null,tx,clock,gate));assertThrows(RuntimeException.class,()->new SourceAnalysisProofGraph(ds,null,clock,gate));assertThrows(RuntimeException.class,()->new SourceAnalysisProofGraph(ds,tx,null,gate));assertThrows(RuntimeException.class,()->new SourceAnalysisProofGraph(ds,tx,clock,null));verifyNoInteractions(ds,tx,clock,gate);
    }
    @Test void Actual201OriginalP1SealObservationAndOnlyHandoffAreReconstructedByTheGraph()throws Exception{
        handoff(201,true);var db=h.h.f.f;var ref=h.h.ref;
        db.jdbc.update("DELETE FROM jobs WHERE id=? OR target_type='source_stage_availability'",JdbcSourceDedupPartialReceipts.receiptId(ref.ownerId()));
        assertNull(h.h.f.readonly(()->graph.dedupSeals().readCommitted(ref)));int calls=db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);
        var seal=graph.dedupSeals().seal(ref);assertEquals(201,CanonicalJson.parse(seal.proof().json()).get("accepted").asInt());assertEquals("partial",CanonicalJson.parse(seal.proof().json()).get("coverage_state").asText());assertEquals(seal.proof().json(),h.h.f.readonly(()->graph.dedupProofs().read(ref)).json());
        var handoff=graph.handoffs().handoff(ref);assertEquals(seal.proof().hash(),handoff.p1ResultHash());assertEquals(1,h.h.children());assertEquals(1,h.h.relations());assertEquals(calls,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        allowed=false;graph=provider(db,h.h.f.clock);assertEquals(handoff.json(),graph.handoffs().handoff(ref).json());assertEquals(1,h.h.relations());
    }
    @Test void ActualPartialP2WithAppliedOrFailedPartsFinalizesTheSameOriginalAndRestarts()throws Exception{
        for(boolean applied:new boolean[]{true,false}){
            value(201,true);String before=p.collector("result_json"),coverage=p.collector("coverage_json");p.complete(applied);var ref=p.ref();int calls=p.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);
            var seal=graph.valueSeals().seal(ref);assertEquals("partial",CanonicalJson.parse(seal.proof().json()).get("coverage_state").asText());assertEquals(seal.proof().json(),p.read(()->graph.valueProofs().read(ref)).json());
            var finalValue=graph.finalization().finish(ref);assertNotNull(finalValue);assertNotNull(graph.finalObserver().capture(ref));assertEquals("partial",p.db.jdbc.queryForObject("SELECT analysis_state FROM source_runs",String.class));assertEquals(before,p.collector("result_json"));assertEquals(coverage,p.collector("coverage_json"));assertEquals(calls,p.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
            int rows=p.db.count("1=1");allowed=false;p.db.jdbc.execute("DROP TABLE source_run_items");p.db.jdbc.execute("DROP TABLE analyzed_entities");graph=provider(p.db,p.clock);assertEquals(finalValue.json(),graph.finalization().finish(ref).json());assertNotNull(graph.finalObserver().capture(ref));assertEquals(rows,p.db.count("1=1"));allowed=true;
        }
    }
    @Test void FailedP1ZeroValueStillHasActualSealsAndNoSyntheticValueRoot()throws Exception{
        value(1,false);var ref=p.ref();assertTrue(p.read(()->graph.plans().readImmutable(ref)).chunks().isEmpty());assertNotNull(graph.valueSeals().seal(ref));assertNotNull(graph.finalization().finish(ref));assertNotNull(graph.finalObserver().capture(ref));assertEquals(0,p.db.count("record_kind='logical_task' AND task_type='news_value'"));assertEquals("partial",p.db.jdbc.queryForObject("SELECT analysis_state FROM source_runs",String.class));assertEquals(3,p.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void DynamicGateAndChangedOriginalNativeCannotInventAChildOrProjection()throws Exception{
        handoff(1,false);allowed=false;assertThrows(IllegalStateException.class,()->graph.handoffs().handoff(h.h.ref));assertEquals(0,h.h.children());allowed=true;assertNotNull(graph.handoffs().handoff(h.h.ref));
        value(1,false);var ref=p.ref();graph.valueSeals().seal(ref);String root=p.q.q.a.f.plan.chunks().getFirst().rootId();p.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->graph.finalization().finish(ref));assertEquals(0,p.db.count("target_type='source_value_final_result'"));assertNull(p.db.jdbc.queryForObject("SELECT analysis_summary FROM source_runs",String.class));
    }
}
