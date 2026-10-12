package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;

/** Inherits original SQL pending, terminal, prepare, result and concurrent-header recovery checks. */
class SourceStageResolutionBatchTest extends SourceStageResolutionTest {
    SourceDedupResultProofService batch;SourceValueResultProofService values;
    @Override SourceStageResolutionService provider(JdbcSourceStageRoots rootStore){
        var results=new JdbcSourceDedupResults(f.db.source);var dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.db.source));
        batch=spy(new SourceDedupResultProofService(results,f.plans,f.p1.universes));doThrow(new IllegalStateException("fictional duplicate original archive read")).when(batch).readApplied(anyString());
        values=spy(new SourceValueResultProofService(results,f.plans,f.p1.universes,dependencies));doThrow(new IllegalStateException("fictional duplicate P2 original archive read")).when(values).readApplied(anyString());
        return new SourceStageResolutionService(rootStore,f.plans,f.p1.universes,f.parts,new JdbcFrozenInputs(f.db.source),results,batch,values,dependencies);
    }
    @Test void actualAppliedOriginalP1ResolutionCallsOneBatchAndKeepsNativeRecheck()throws Exception{
        f=fixture();f.completedP1();provider();var resolved=read(f.handoffs.ref);assertTrue(resolved.settled());assertFalse(resolved.hasFailures());assertNotNull(resolved.fragments().getFirst().dedup());
        verify(batch,times(1)).readAppliedBatch(eq(f.handoffs.ref),eq(resolved.plan().chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList()));verify(batch,never()).readApplied(anyString());
    }
    @Test void actualAppliedOriginalP2ResolutionUsesOneValueBatchWithItsOwnNativeProof()throws Exception{
        f=fixture();f.completedP1();f.step.step(f.candidate(f.handoffs.ref.ownerId()));String child=f.handoffs.read().p2Owner();f.step.step(f.candidate(child));var ref=f.reference(child);f.completeP2(ref);provider();var resolved=read(ref);assertTrue(resolved.settled());assertFalse(resolved.hasFailures());assertNotNull(resolved.fragments().getFirst().value());assertNull(resolved.fragments().getFirst().dedup());verify(values,times(1)).readAppliedBatch(eq(ref),eq(resolved.plan().chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList()));verify(values,never()).readApplied(anyString());verify(batch,never()).readAppliedBatch(any(),any());
    }
}
