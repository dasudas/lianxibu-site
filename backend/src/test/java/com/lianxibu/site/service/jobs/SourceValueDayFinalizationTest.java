package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual original partial proof and projection SQL; fictional source rows and doubled day provider. */
class SourceValueDayFinalizationTest {
    SourceValueDayPartialSealTest q;SourceValueDayResolutionTest f;SourceValueStageProofService whole;SourceValueFinalizationTest x;SourceValueFinalizationService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{
        q=new SourceValueDayPartialSealTest(){@Override GenerationJdbcStoreTest database(){return SourceValueDayFinalizationTest.this.database();}};q.setup(false);f=q.f;var dependencies=new SourceDedupDependencyService(f.authority.observations,f.authority.proven,f.authority.service);var plans=f.f.f.plans;var universes=f.original.p1.universes;
        whole=new SourceValueStageProofService(plans,universes,new SourceValueResultProofService(new JdbcSourceDedupResults(f.db.source),plans,universes,dependencies),dependencies,q.seals);
    }
    void install(){
        x=new SourceValueFinalizationTest();x.f=new SourceValueStageProofTest();x.f.f=f.db;x.f.plan=f.f.plan;x.f.original=f.outcomes.original;x.f.ref=f.ref;x.f.clock=f.f.f.clock;x.f.plans=f.f.f.plans;x.f.universes=f.original.p1.universes;x.f.service=whole;x.install();service=provider(q.permits);
    }
    SourceValueFinalizationService provider(SourceValuePartialCommitPermitService permit){return new SourceValueFinalizationService(x.proofs,x.archives,x.f.plans,x.f.universes,x.receipts,x.summaries,f.db.writes,new DataSourceTransactionManager(f.db.source),x.f.clock,permit);}
    @Test void UnsealedZeroStillWaitsAndOriginalPartialSealProducesPartialSourceProjection()throws Exception{
        setup();assertNull(f.original.read(()->whole.readCommitted(f.ref)));assertNotNull(q.seals.seal(f.ref));var original=f.original.read(()->whole.readCommitted(f.ref));assertEquals("source-value-stage-result-v2",CanonicalJson.parse(original.result().json()).get("schema_version").asText());assertEquals(CanonicalJson.parse(f.f.plan.scope().stageContextJson()),CanonicalJson.parse(original.result().json()).get("stage_context"));install();assertThrows(RuntimeException.class,()->x.service.finish(f.ref));assertEquals(0,x.seals());var receipt=service.finish(f.ref);assertEquals("partial",receipt.coverageState());assertEquals("succeeded",x.intentState());assertEquals("partial",x.sourceText("analysis_state"));assertTrue(x.sourceText("dedup_summary").contains("去重覆盖不完整"));assertTrue(x.sourceText("dedup_summary").contains("原计划包含 1 条"));assertTrue(x.sourceText("analysis_summary").contains("原始去重证明"));var committed=f.original.read(()->service.readCommitted(f.ref));assertEquals(receipt.json(),committed.receipt().json());assertEquals(0,CanonicalJson.parse(committed.result().json()).get("selected_count").asInt());assertEquals(0,f.db.count("record_kind='logical_task' AND task_type='news_value'"));int rows=f.db.count("1=1");assertEquals(receipt.json(),service.finish(f.ref).json());assertEquals(rows,f.db.count("1=1"));
    }
    @Test void FinalWriterRejectsChangedActualPartialPermissionAndRollsBackProjection()throws Exception{
        setup();q.seals.seal(f.ref);install();var changed=spy(q.permits);doThrow(new IllegalStateException("fictional native partial changed")).when(changed).verify(any());service=provider(changed);assertThrows(RuntimeException.class,()->service.finish(f.ref));assertEquals(0,x.seals());assertNull(x.sourceText("dedup_summary"));assertNull(x.sourceText("analysis_summary"));assertEquals("waiting_input",x.intentState());assertEquals(2,f.db.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));service=provider(q.permits);assertNotNull(service.finish(f.ref));assertEquals(1,x.seals());
    }
}
