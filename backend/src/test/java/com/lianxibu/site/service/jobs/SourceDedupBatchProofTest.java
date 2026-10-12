package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Original archives and retained P1 SQL, with explicitly fictional applied-fragment genesis. */
class SourceDedupBatchProofTest {
    SourceDedupResultProofServiceTest f;SourceDedupResultProofService service;FrozenSourceStagePlanService plans;FrozenSourceUniverseService universes;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(){f=new SourceDedupResultProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceDedupBatchProofTest.this.database();}@Override MemberUniverse.Universe universe(){return FrozenSourceUniverseTest.universe(301,false);}};f.setup();plans=spy(f.archives);universes=spy(f.universes);service=new SourceDedupResultProofService(new JdbcSourceDedupResults(f.f.source),plans,universes);}
    FrozenSourceStagePlan.Reference reference(){return FrozenSourceStagePlan.encode(f.plan,f.original).reference();}
    List<String> roots(){return f.plan.chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList();}
    void complete(int n){f.chunk=f.plan.chunks().get(n);f.complete();}
    Map<String,SourceDedupResultProofService.Applied> read(List<String> roots){return f.readonly(()->service.readAppliedBatch(reference(),roots));}
    @Test void multipleOriginalFragmentsReuseOnePlanAndUniverseButPendingIsNotApplied(){
        setup();assertTrue(roots().size()>1);complete(0);complete(f.plan.chunks().size()-1);int rows=f.f.count("1=1");var applied=read(roots());assertEquals(Set.of(roots().getFirst(),roots().getLast()),applied.keySet());
        verify(plans,times(1)).readImmutable(reference());verify(universes,times(1)).readImmutable(f.plan.reference());assertEquals(rows,f.f.count("1=1"));assertThrows(UnsupportedOperationException.class,()->applied.clear());
        assertEquals(Set.of(roots().getFirst()),read(List.of(roots().getFirst())).keySet());assertTrue(read(List.of()).isEmpty());
    }
    @Test void anotherReadRevalidatesActualNativeAndOriginalArchiveInsteadOfCachingSuccess(){
        setup();complete(0);assertEquals(1,read(List.of(roots().getFirst())).size());f.f.jdbc.update("UPDATE jobs SET call_count=0 WHERE id=?",roots().getFirst());assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst())));f.f.jdbc.update("UPDATE jobs SET call_count=1 WHERE id=?",roots().getFirst());assertEquals(1,read(List.of(roots().getFirst())).size());
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(reference().ownerId()));assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst())));
    }
    @Test void retainedAndHandoffDamageStillRejectTheWholeRequestedProof(){
        setup();complete(0);f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],f.part());assertThrows(RuntimeException.class,()->read(roots()));
        setup();complete(0);f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-altered' WHERE id=?",f.receipt());assertThrows(RuntimeException.class,()->read(roots()));
    }
    @Test void transactionRolesAliasesDuplicateAndForeignOriginalIdentityStayClosed(){
        setup();assertThrows(RuntimeException.class,()->service.readAppliedBatch(reference(),roots()));assertThrows(RuntimeException.class,()->f.tx(()->service.readAppliedBatch(reference(),roots())));
        assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst(),roots().getFirst())));assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst().toUpperCase(Locale.ROOT))));assertThrows(RuntimeException.class,()->read(List.of("fictional-other-original-root")));assertThrows(RuntimeException.class,()->read(null));
        assertThrows(RuntimeException.class,()->f.readonly(()->service.readAppliedBatch(null,roots())));
    }
    @Test void wholeSourceConsumerUsesOneBatchAndStillWaitsForEveryOriginalFragment(){
        setup();var fragments=spy(service);doThrow(new IllegalStateException("fictional single-fragment archive reread forbidden")).when(fragments).readApplied(anyString());var whole=new SourceDedupStageProofService(f.archives,f.universes,fragments);
        complete(0);assertNull(f.readonly(()->whole.readCommitted(reference())));for(int n=1;n<f.plan.chunks().size();n++)complete(n);clearInvocations(fragments);var result=f.readonly(()->whole.readCommitted(reference()));assertNotNull(result);assertEquals(301,result.proof().decisions().size());verify(fragments,times(1)).readAppliedBatch(reference(),roots());verify(fragments,never()).readApplied(anyString());
    }
}
