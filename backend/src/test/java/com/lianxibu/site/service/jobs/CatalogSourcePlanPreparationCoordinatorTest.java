package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

class CatalogSourcePlanPreparationCoordinatorTest {
    CatalogSourcePlanPreparationTest f;boolean allowed=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {f=new CatalogSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return CatalogSourcePlanPreparationCoordinatorTest.this.database();}};f.setup();}
    CatalogSourcePlanPreparationCoordinator provider(SharedWriteExecutor writes){return new CatalogSourcePlanPreparationCoordinator(f.preparation,new SourceStagePreparationService(f.f.source,f.plans,f.universes,f.collections::read,f.f.clock,"fictional-owner"),new JdbcFrozenSourcePlans(f.f.source),writes,"agent",()->allowed);}
    CatalogSourcePlanPreparationCoordinator.Prepared prepare(CatalogSourcePlanPreparationCoordinator p){return p.prepareDedup(f.collection.intentId(),"fictional-run",0);}
    @Test void realProducerCompletesPureBuildBetweenTheCaptureAndPlanWriter(){
        f.preparation=spy(f.preparation);
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return call.callRealMethod();}).when(f.preparation).build(any());
        var roots=new java.util.ArrayList<Integer>();var writes=spy(f.f.writes);
        doAnswer(call->{var value=call.callRealMethod();roots.add(f.f.count("record_kind='logical_task'"));return value;}).when(writes).execute(any(),any(),any());
        var prepared=prepare(provider(writes));assertEquals(java.util.List.of(0,0,1),roots);assertEquals(prepared.plan().chunks().getFirst().requestJson(),prepared.root().requestJson());assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void captureCommitAckLossKeepsAnHonestPrefixAndTheNextCallRecaptures(){
        var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional capture ACK lost");}}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});assertThrows(RuntimeException.class,()->prepare(provider(writes)));
        assertEquals(0,f.f.count("target_type='source_stage_plan'"));assertEquals(0,f.f.count("record_kind='logical_task'"));assertTrue(f.f.count("record_kind='snapshot_part'")>0);
        assertNotNull(prepare(provider(f.f.writes)).root());assertEquals(1,f.f.count("record_kind='logical_task'"));
    }
    @Test void actualPlanCommitAckLossUsesItsDurableCompleteOriginal(){
        var fault=new DataSourceTransactionManager(f.f.source){int commits;@Override protected void doCommit(DefaultTransactionStatus s){super.doCommit(s);if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&++commits==2)throw new org.springframework.transaction.TransactionSystemException("fictional plan ACK lost");}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var prepared=prepare(provider(writes));assertEquals(prepared,prepare(provider(f.f.writes)));assertEquals(1,f.f.count("target_type='source_stage_plan'"));assertEquals(1,f.f.count("record_kind='logical_task'"));
    }
    @Test void factsChangingAfterPureBuildRejectThePlanBeforeAnyRootCanQueue(){
        f.preparation=spy(f.preparation);doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var prepared=call.callRealMethod();f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0,revision=revision+1 WHERE terminal_state='accepted'");return prepared;}).when(f.preparation).build(any());
        assertThrows(RuntimeException.class,()->prepare(provider(f.f.writes)));assertEquals(0,f.f.count("target_type='source_stage_plan'"));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
    @Test void ownsPlanAndSinglePartTransactionsAndReplaysWithoutCurrentFacts(){
        var p=provider(f.f.writes);var value=prepare(p);var chunk=value.plan().chunks().getFirst();assertEquals(chunk.snapshotJson(),value.root().snapshotJson());assertEquals(chunk.requestJson(),value.root().requestJson());assertEquals(1,f.f.count("record_kind='logical_task'"));assertEquals(1,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));int count=f.f.count("1=1"),settings=f.settingsReads;allowed=false;f.allowed=false;f.f.jdbc.execute("DROP TABLE analyzed_entities");f.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");assertEquals(value,prepare(provider(f.f.writes)));assertEquals(count,f.f.count("1=1"));assertEquals(settings,f.settingsReads);
    }
    @Test void failedRootTransactionKeepsOriginalPlanAndResumesExactly(){
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_coordinator_failure CHECK(target_type IS NULL OR target_type<>'generation_prepare')");assertThrows(RuntimeException.class,()->prepare(provider(f.f.writes)));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(1,f.f.count("target_type='source_stage_plan'"));int reads=f.settingsReads;
        f.f.jdbc.execute("ALTER TABLE jobs"+(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?" DROP CONSTRAINT ":" DROP CHECK ")+"fictional_coordinator_failure");assertNotNull(prepare(provider(f.f.writes)).root());assertEquals(reads,f.settingsReads);assertEquals(1,f.f.count("record_kind='logical_task'"));
    }
    @Test void actualPartCommitAckLossReturnsItsPermanentOriginalReceipt(){
        var fault=new DataSourceTransactionManager(f.f.source){int commits;@Override protected void doCommit(DefaultTransactionStatus s){super.doCommit(s);if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&++commits==3)throw new org.springframework.transaction.TransactionSystemException("fictional root ACK lost");}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var value=prepare(provider(writes));assertEquals(value,prepare(provider(f.f.writes)));assertEquals(1,f.f.count("record_kind='logical_task'"));assertEquals(1,f.f.count("target_type='generation_prepare'"));
    }
    @Test void gatesOuterTransactionsBoundsAndCancelledParentCannotCreateNewRoots(){
        var p=provider(f.f.writes);allowed=false;assertThrows(RuntimeException.class,()->prepare(p));assertEquals(0,f.f.count("target_type='source_stage_plan'"));allowed=true;assertThrows(RuntimeException.class,()->f.tx(()->prepare(p)));assertThrows(RuntimeException.class,()->p.prepareDedup(f.collection.intentId(),"fictional-run",-1));f.prepare();f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",f.collection.intentId());assertThrows(RuntimeException.class,()->prepare(p));assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void originalEmptySelectionHasAPlanAndNoSyntheticRoot(){
        f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0");var value=prepare(provider(f.f.writes));assertTrue(value.plan().chunks().isEmpty());assertNull(value.root());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(1,f.f.count("target_type='source_stage_plan'"));assertEquals(value,prepare(provider(f.f.writes)));
    }
    @Test void closedCategoryAndForeignStageRefuseAFragment(){
        assertThrows(RuntimeException.class,()->new CatalogSourcePlanPreparationCoordinator(f.preparation,mock(SourceStagePreparationService.class),new JdbcFrozenSourcePlans(f.f.source),f.f.writes,"news_tech",()->true));
        var p=new CatalogSourcePlanPreparationCoordinator(f.preparation,new SourceStagePreparationService(f.f.source,f.plans,f.universes,f.collections::read,f.f.clock,"fictional-owner"),new JdbcFrozenSourcePlans(f.f.source),f.f.writes,"workflow",()->true);
        assertThrows(RuntimeException.class,()->prepare(p));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
}
