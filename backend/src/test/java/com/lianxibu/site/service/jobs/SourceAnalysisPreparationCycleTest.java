package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.JdbcSourceIntentScan;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SourceAnalysisPreparationCycleTest {
    SourceIntentScanTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(){f=new SourceIntentScanTest(){@Override GenerationJdbcStoreTest database(){return SourceAnalysisPreparationCycleTest.this.database();}};f.setup();}
    SourceAnalysisPreparationCycle cycle(JdbcSourceIntentScan scan,SourceAnalysisParentStepService parent){return new SourceAnalysisPreparationCycle(scan,parent,new DataSourceTransactionManager(f.f.source));}
    @Test void ActualNativeScanIsReadOnlyAndOneOutsideStepAdvancesPastFailureAndWrapsAfterTheTail(){
        setup();var scan=spy(f.store);doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(1,call.getArgument(1,Integer.class));return call.callRealMethod();}).when(scan).page(any(),eq(1));
        var parent=mock(SourceAnalysisParentStepService.class);var visited=new ArrayList<String>();doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var c=call.getArgument(0,JdbcSourceIntentScan.Candidate.class);visited.add(c.id());if(visited.size()==1)throw new IllegalStateException("fictional first parent failure");return new SourceAnalysisParentStepService.Outcome(SourceAnalysisParentStepService.Status.PENDING,c.id());}).when(parent).step(any());
        var cycle=cycle(scan,parent);int rows=f.f.count("1=1");assertEquals(SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));for(int n=1;n<f.active.size();n++)assertEquals(SourceAnalysisPreparationCycle.Outcome.PENDING,cycle.run(()->true));assertEquals(f.active,visited);assertEquals(SourceAnalysisPreparationCycle.Outcome.PENDING,cycle.run(()->true));assertEquals(f.active.getFirst(),visited.getLast());assertEquals(rows,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        var restart=cycle(scan,parent);assertEquals(SourceAnalysisPreparationCycle.Outcome.PENDING,restart.run(()->true));assertEquals(f.active.getFirst(),visited.getLast());
    }
    @Test void ActualPartialZeroParentFinalizesAndRestartOnlyReplaysItsPermanentObservation()throws Exception{
        var original=new SourceParentRuntimeProofTest(){@Override GenerationJdbcStoreTest database(){return SourceAnalysisPreparationCycleTest.this.database();}};original.setup(1,false,false);var source=original.f.db.source;var scan=new JdbcSourceIntentScan(source);var cycle=new SourceAnalysisPreparationCycle(scan,original.parent,new DataSourceTransactionManager(source));
        assertEquals(SourceAnalysisPreparationCycle.Outcome.FINALIZED,cycle.run(()->true));assertEquals(1,original.f.db.count("target_type='source_value_final_availability'"));int rows=original.f.db.count("1=1"),calls=original.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);original.f.db.jdbc.execute("DROP TABLE source_run_items");original.f.db.jdbc.execute("DROP TABLE analyzed_entities");original.wire(original.f.clock);cycle=new SourceAnalysisPreparationCycle(scan,original.parent,new DataSourceTransactionManager(source));assertEquals(SourceAnalysisPreparationCycle.Outcome.FINALIZED,cycle.run(()->true));assertEquals(rows,original.f.db.count("1=1"));assertEquals(calls,original.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void CancellationScanFailureNestedTransactionAndMalformedPageCannotExecuteAParent(){
        setup();var scan=spy(f.store);var parent=mock(SourceAnalysisParentStepService.class);var running=new AtomicBoolean(false);var cycle=cycle(scan,parent);assertEquals(SourceAnalysisPreparationCycle.Outcome.IDLE,cycle.run(running::get));verifyNoInteractions(scan,parent);running.set(true);
        doAnswer(call->{var page=call.callRealMethod();running.set(false);return page;}).when(scan).page(any(),eq(1));assertEquals(SourceAnalysisPreparationCycle.Outcome.IDLE,cycle.run(running::get));verifyNoInteractions(parent);
        doThrow(new IllegalStateException("fictional scan failure")).when(scan).page(any(),eq(1));assertEquals(SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));verifyNoInteractions(parent);
        assertEquals(SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED,f.readonly(()->cycle.run(()->true)));verifyNoInteractions(parent);
        doReturn(new JdbcSourceIntentScan.Page(f.page(null,1).candidates(),true,"fictional-wrong-cursor")).when(scan).page(any(),eq(1));assertEquals(SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));verifyNoInteractions(parent);
    }
    @Test void ConcurrentRunIsBusyAndStoppingWaitsForOnlyTheAlreadyStartedParent()throws Exception{
        setup();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var parent=mock(SourceAnalysisParentStepService.class);doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));var c=call.getArgument(0,JdbcSourceIntentScan.Candidate.class);return new SourceAnalysisParentStepService.Outcome(SourceAnalysisParentStepService.Status.PENDING,c.id());}).when(parent).step(any());var cycle=cycle(f.store,parent);var running=new AtomicBoolean(true);
        try(var executor=Executors.newSingleThreadExecutor()){var first=executor.submit(()->cycle.run(running::get));try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(SourceAnalysisPreparationCycle.Outcome.BUSY,cycle.run(()->true));running.set(false);}finally{release.countDown();}assertEquals(SourceAnalysisPreparationCycle.Outcome.PENDING,first.get(5,TimeUnit.SECONDS));assertEquals(SourceAnalysisPreparationCycle.Outcome.IDLE,cycle.run(running::get));}verify(parent,times(1)).step(any());
    }
    @Test void ConstructorIsLazyAndRequiresAllActualPorts(){var scan=mock(JdbcSourceIntentScan.class);var parent=mock(SourceAnalysisParentStepService.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);assertNotNull(new SourceAnalysisPreparationCycle(scan,parent,tx));assertThrows(RuntimeException.class,()->new SourceAnalysisPreparationCycle(scan,parent,null));verifyNoInteractions(scan,parent,tx);}
}
