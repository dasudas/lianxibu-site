package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual original P2 SQL and explicitly fictional full P1 observation/day-reference genesis. */
class SourceValueBatchProofTest {
    SourceValueStageProofTest f;SourceValueResultProofService service;FrozenSourceStagePlanService plans;FrozenSourceUniverseService universes;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(){f=new SourceValueDayStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueBatchProofTest.this.database();}};f.install(501);plans=spy(f.plans);universes=spy(f.universes);service=new SourceValueResultProofService(new JdbcSourceDedupResults(f.f.source),plans,universes,new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.f.source)));}
    List<String> roots(){return f.plan.chunks().stream().map(SourcePartitionPlanner.Chunk::rootId).toList();}
    Map<String,SourceValueResultProofService.Applied> read(List<String> roots){return f.readonly(()->service.readAppliedBatch(f.ref,roots));}
    @Test void multiPartOriginalDayInputLoadsOnePlanAndUniverseAndLeavesPendingOutOfAppliedMap(){
        setup();assertEquals(2,roots().size());f.complete(0);int count=f.f.count("1=1");var applied=read(roots());assertEquals(Set.of(roots().getFirst()),applied.keySet());verify(plans,times(1)).readImmutable(f.ref);verify(universes,times(1)).readImmutable(f.plan.reference());assertEquals(count,f.f.count("1=1"));assertThrows(UnsupportedOperationException.class,()->applied.clear());
        f.complete(1);assertEquals(new HashSet<>(roots()),read(roots()).keySet());assertTrue(read(List.of()).isEmpty());
    }
    @Test void subsequentReadRechecksNativeOriginalP1ObservationAndRetainedEvidence(){
        setup();f.complete(0);assertEquals(1,read(roots()).size());String root=roots().getFirst();f.f.jdbc.update("UPDATE jobs SET call_count=0 WHERE id=?",root);assertThrows(RuntimeException.class,()->read(roots()));f.f.jdbc.update("UPDATE jobs SET call_count=1 WHERE id=?",root);assertEquals(1,read(roots()).size());
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,()->read(roots()));
        setup();f.complete(0);f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result'",new byte[32],roots().getFirst());assertThrows(RuntimeException.class,()->read(roots()));
    }
    @Test void actualPartialP1StillNeedsItsOriginalNativeAuthorityForEachAppliedProof()throws Exception{
        var q=new SourceValuePartialResultProofTest(){@Override GenerationJdbcStoreTest database(){return SourceValueBatchProofTest.this.database();}};q.setup();q.outcome.complete(0);var ref=FrozenSourceStagePlan.encode(q.plan,q.outcome.original).reference();
        assertEquals(Set.of(q.root),q.f.f.f.f.read(()->q.service.readAppliedBatch(ref,List.of(q.root))).keySet());q.service=q.provider(new SourceDedupDependencyService(q.f.f.observations));assertThrows(RuntimeException.class,()->q.f.f.f.f.read(()->q.service.readAppliedBatch(ref,List.of(q.root))));
        q.service=q.provider(new SourceDedupDependencyService(q.f.f.observations,q.f.f.proven,q.f.f.service));assertEquals(1,q.f.f.f.f.read(()->q.service.readAppliedBatch(ref,List.of(q.root))).size());q.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",q.f.f.f.plan.chunks().getLast().rootId());assertThrows(RuntimeException.class,()->q.f.f.f.f.read(()->q.service.readAppliedBatch(ref,List.of(q.root))));
    }
    @Test void transactionIdentityDuplicatesCapacityAndArchiveDamageStayClosed(){
        setup();assertThrows(RuntimeException.class,()->service.readAppliedBatch(f.ref,roots()));assertThrows(RuntimeException.class,()->f.tx(()->service.readAppliedBatch(f.ref,roots())));assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst(),roots().getFirst())));assertThrows(RuntimeException.class,()->read(List.of(roots().getFirst().toUpperCase(Locale.ROOT))));assertThrows(RuntimeException.class,()->read(List.of("fictional-other-value-original")));assertThrows(RuntimeException.class,()->read(null));assertThrows(GenerationFlowService.CapacityGuarded.class,()->read(Collections.nCopies(10001,roots().getFirst())));
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(f.ref.ownerId()));assertThrows(RuntimeException.class,()->read(List.of()));
    }
    @Test void wholeValueConsumerUsesOneBatchAndRequiresEveryOriginalPart(){
        setup();var fragments=spy(service);doThrow(new IllegalStateException("fictional original P2 per-part archive reread")).when(fragments).readApplied(anyString());var whole=new SourceValueStageProofService(f.plans,f.universes,fragments,new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.f.source)));
        f.complete(0);assertNull(f.readonly(()->whole.readCommitted(f.ref)));f.complete(1);clearInvocations(fragments);var result=f.readonly(()->whole.readCommitted(f.ref));assertNotNull(result);assertEquals(501,result.result().decisions().size());verify(fragments,times(1)).readAppliedBatch(f.ref,roots());verify(fragments,never()).readApplied(anyString());
    }
}
