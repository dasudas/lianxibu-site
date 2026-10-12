package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/** Real fictional archives and native INPUT; retained APPLIED outcomes are injected without network. */
class SourceValuePartialResultProofTest {
    NewsValuePartialPlanPreparationTest f;SourceValueStageProofTest outcome;SourcePartitionPlanner.Plan plan;SourceValueResultProofService service;String root;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{
        f=new NewsValuePartialPlanPreparationTest(){@Override GenerationJdbcStoreTest database(){return SourceValuePartialResultProofTest.this.database();}};f.setup(true);plan=f.commit(f.build());root=plan.chunks().getFirst().rootId();f.f.f.f.p1.tx(()->{var chunk=plan.chunks().getFirst();new JdbcJobRecords(f.db.source).createRoot(new JdbcJobRecords.Root(root,plan.reference().ownerId(),plan.logicalKey(),1,0,"news_value",2),f.clock.instant());new JdbcFrozenInputs(f.db.source).save(root,chunk.snapshotJson(),chunk.inputHash(),f.clock.instant());return true;});
        service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));outcome=new SourceValueStageProofTest();outcome.f=f.db;outcome.plan=plan;outcome.original=f.f.f.f.read(()->f.f.f.f.p1.universes.readImmutable(plan.reference()));outcome.clock=f.clock;outcome.pure=new SourceValueStageResultTest();
    }
    SourceValueResultProofService provider(SourceDedupDependencyService dependencies){return new SourceValueResultProofService(new JdbcSourceDedupResults(f.db.source),f.plans,f.f.f.f.p1.universes,dependencies);}
    SourceValueResultProofService.Applied read(){return f.f.f.f.read(()->service.readApplied(root));}
    @Test void ActualAppliedPartialInputReadsOriginalProofAndRestartIgnoresCurrentRows()throws Exception{
        setup();assertNull(read());outcome.complete(0);var original=assertDoesNotThrow(this::read);assertNotNull(original);assertEquals(plan.chunks().getFirst().itemIds(),original.proof().members().stream().map(m->m.itemId()).toList());assertEquals(f.clock.instant(),original.appliedAt());assertEquals(plan.chunks().getFirst().inputHash(),original.proof().result().inputHash());int count=f.db.count("1=1"),calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));assertEquals(original.proof().handoffHash(),read().proof().handoffHash());assertEquals(count,f.db.count("1=1"));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void ReadonlyAndActualPartialPortsAreMandatoryEvenWhenTheLedgerExists()throws Exception{
        setup();outcome.complete(0);assertThrows(RuntimeException.class,()->service.readApplied(root));assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->service.readApplied(root)));service=provider(new SourceDedupDependencyService(f.f.observations));assertThrows(RuntimeException.class,this::read);service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));assertNotNull(read());f.db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(f.f.f.ref.ownerId()));assertThrows(RuntimeException.class,this::read);assertEquals(1,f.f.a.observations());
    }
    @Test void OriginalAppliedTimeAndNativeP1ObservationCannotBeReplacedByCurrentFacts()throws Exception{
        setup();outcome.complete(0);f.db.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(plan.scope().cutoff().minusSeconds(1)),root);assertThrows(RuntimeException.class,this::read);f.db.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(f.clock.instant()),root);assertNotNull(read());f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.f.plan.chunks().getLast().rootId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void CorruptOriginalHandoffAndCapacityExceptionsRemainExplicit()throws Exception{
        setup();outcome.complete(0);var capped=spy(f.f.proven);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).read(any(),any());service=provider(new SourceDedupDependencyService(f.f.observations,capped,f.f.service));assertThrows(GenerationFlowService.CapacityGuarded.class,this::read);service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));assertNotNull(read());f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenInputs.stableId("member-stage","member-stage:"+root));assertThrows(RuntimeException.class,this::read);
    }
}
