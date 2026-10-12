package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SourceCapturedPartCoordinatorTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @Test void Actual201CoordinatorConsumesOutsideCaptureAndOriginalCapturedLookups()throws Exception{
        var f=new SourcePartialPlanCoordinatorTest(){@Override GenerationJdbcStoreTest database(){return SourceCapturedPartCoordinatorTest.this.database();}};f.setup(201,true);var parts=spy(f.parts);
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return call.callRealMethod();}).when(parts).capturePartPartial(anyString(),anyLong(),anyString(),any());
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return call.callRealMethod();}).when(parts).prepareCaptured(any());
        doThrow(new AssertionError("fictional old writer pure path")).when(parts).preparePartPartial(anyString(),anyLong(),anyString(),any());
        doThrow(new AssertionError("fictional old lookup pure path")).when(parts).committedPartPartial(anyString(),anyLong(),anyString(),any());
        f.service=new SourcePlanPreparationCoordinator(f.q.h.f.p1.preparation,f.value,parts,f.q.service,f.q.h.proofs,new JdbcFrozenSourcePlans(f.db.source),f.db.writes,new DataSourceTransactionManager(f.db.source),f.clock,()->f.allowed,f.q.a.service);
        int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var first=f.prepare(0);assertEquals(201,first.plan().decisions().size());
        for(int n=1;n<first.plan().chunks().size();n++)assertEquals(first.plan(),f.prepare(n).plan());
        verify(parts,times(first.plan().chunks().size())).capturePartPartial(anyString(),anyLong(),anyString(),any());verify(parts,times(first.plan().chunks().size())).prepareCaptured(any());verify(parts,atLeast(first.plan().chunks().size()*2)).committedCaptured(any());
        f.allowed=false;f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");assertEquals(first,f.prepare(0));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void Original201FixtureUsesSameCapturedSpiAndKeepsOneWriterPerPart()throws Exception{
        var f=new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return SourceCapturedPartCoordinatorTest.this.database();}};f.setup(201,true);f.service=spy(f.service);
        doThrow(new AssertionError("fictional old fixture writer pure path")).when(f.service).preparePartPartial(anyString(),anyLong(),anyString(),any());doThrow(new AssertionError("fictional old fixture lookup pure path")).when(f.service).committedPartPartial(anyString(),anyLong(),anyString(),any());
        for(int n=0;n<f.plan.chunks().size();n++){var original=f.prepare(n);assertEquals(f.plan.chunks().get(n).snapshotJson(),original.snapshotJson());assertEquals(original,f.prepare(n));}assertTrue(f.progress().complete());
        verify(f.service,times(f.plan.chunks().size())).prepareCaptured(any());verify(f.service,times(f.plan.chunks().size()*2)).capturePartPartial(anyString(),anyLong(),anyString(),any());
    }
}
