package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete original native captures close freshly once in each original one-second writer. */
class SourcePartWriterBudgetTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @Test void Actual201PartialPartsReuseOnlyThisWritersAlreadyVerifiedCompletePlan()throws Exception{
        var f=new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return SourcePartWriterBudgetTest.this.database();}};f.setup(201,true);FrozenSourceStagePlanService plans;var reads=new AtomicInteger();var universes=spy(f.f.f.f.f.p1.universes);var universeReads=new AtomicInteger();doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());universeReads.incrementAndGet();return call.callRealMethod();}).when(universes).read(any(FrozenSourceUniverse.Reference.class));plans=new FrozenSourceStagePlanService(new com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans(f.f.db.source),universes,f.f.clock,"fictional-owner");plans=spy(plans);doAnswer(call->{reads.incrementAndGet();return call.callRealMethod();}).when(plans).verifyCapturedOriginal(any());f.service=new SourceStagePreparationService(f.f.db.source,plans,universes,f.f.f.f.f.p1.collections::read,f.f.clock,"fictional-owner",null,new SourceDedupDependencyService(f.f.f.observations,f.f.f.proven,f.f.f.service));int calls=f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);for(int n=0;n<f.plan.chunks().size();n++){int before=reads.get(),universesBefore=universeReads.get();var original=f.prepare(n);assertEquals(1,reads.get()-before);assertEquals(1,universeReads.get()-universesBefore);assertEquals(f.plan.chunks().get(n).snapshotJson(),original.snapshotJson());assertEquals(f.plan.chunks().get(n).requestJson(),original.requestJson());assertEquals(original,f.prepare(n));assertEquals(before+1,reads.get());assertEquals(universesBefore+1,universeReads.get());}assertTrue(f.progress().complete());assertEquals(calls,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
}
