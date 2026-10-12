package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class SourceValueFinalProofTest {
    SourceValueStageProofTest f;SourceValueFinalProofService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalProofTest.this.fixture();}};f.install(5);service=provider();}
    SourceValueFinalProofService provider(){return new SourceValueFinalProofService(f.service,f.plans,f.universes);}
    SourceValueFinalProofService.Committed read(){return f.readonly(()->service.readCommitted(f.ref));}
    @Test void onlyAllCommittedOriginalFragmentsProduceTheExactFinalResult(){
        f.install(501);service=provider();assertNull(read());f.complete(0);assertNull(read());f.complete(1);int count=f.f.count("1=1");var proven=read();var expected=SourceValueFinalResult.create(f.plan,f.original,f.read());assertEquals(expected.json(),proven.result().json());assertEquals(expected.hash(),proven.result().hash());assertEquals(f.clock.instant(),proven.minimumObservationTime());assertTrue(proven.result().summary().endsWith("Fictional value fragment summary"));assertTrue(proven.result().selected().isEmpty());assertEquals(count,f.f.count("1=1"));assertEquals(2,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void emptyOriginalStillNeedsItsNativeP1AndRetainsTheConservativeLowerBound(){
        f.install(0);service=provider();var proven=read();assertEquals("本来源没有可分析输入。",proven.result().summary());assertEquals(f.plan.scope().cutoff(),proven.minimumObservationTime());assertEquals(0,f.f.count("record_kind='logical_task'"));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void restartAndLaterGenerationKeepOriginalBytesAndNoNewCalls(){
        f.complete(0);var first=read();f.tx(()->new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",f.plan.logicalKey()+":2"),f.plan.logicalKey(),2,"news_value",f.plan.scope().businessDate()),f.clock.instant()));service=provider();int count=f.f.count("1=1");assertEquals(first.result().json(),read().result().json());assertEquals(first.minimumObservationTime(),read().minimumObservationTime());assertEquals(count,f.f.count("1=1"));assertEquals(first.result().hash(),f.readonly(()->service.read(f.ref)).hash());
    }
    @Test void wrongReferenceBadNativeProofAndNonReadonlyTransactionsAreRejected(){
        assertThrows(RuntimeException.class,()->service.read(f.ref));assertThrows(RuntimeException.class,()->f.tx(()->service.read(f.ref)));f.complete(0);assertNotNull(read());assertThrows(RuntimeException.class,()->f.readonly(()->service.read(new FrozenSourceStagePlan.Reference(f.ref.ownerId(),"b".repeat(64),f.ref.indexHash()))));f.f.jdbc.update("UPDATE jobs SET result_hash=? WHERE id=?",new byte[32],f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,this::read);
    }
}
