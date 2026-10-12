package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** Real permanent original partial input and request, with zero additional external calls. */
class SourcePartialStagePreparationTest {
    NewsValuePartialPlanPreparationTest f;SourcePartitionPlanner.Plan plan;SourceStagePreparationService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    NewsValuePartialPlanPreparationTest planFixture(){return new NewsValuePartialPlanPreparationTest(){@Override GenerationJdbcStoreTest database(){return SourcePartialStagePreparationTest.this.database();}};}
    void setup(boolean mixed)throws Exception{setup(mixed?101:1,mixed);}
    void setup(int count,boolean mixed)throws Exception {f=planFixture();f.setup(count,mixed);plan=f.commit(f.build());service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));}
    SourceStagePreparationService provider(SourceDedupDependencyService dependency){return new SourceStagePreparationService(f.db.source,f.plans,f.f.f.f.p1.universes,f.f.f.f.p1.collections::read,f.clock,"fictional-owner",null,dependency);}
    String action(long part){return SourcePlanPreparationCoordinator.partAction(f.intent,part);}
    GenerationFlowService.Frozen prepare(long part){var captured=service.capturePartPartial(f.intent,part,action(part),f.permit);return f.db.writes.execute(new SharedWriteExecutor.ActionContext(action(part),plan.chunks().get((int)part).rootId(),plan.chunks().get((int)part).inputHash()),key->service.committedCaptured(captured),context->service.prepareCaptured(captured));}
    SourceStagePreparationService.Progress progress(){return f.f.f.f.read(()->service.progress(f.intent));}
    @Test void ActualPartialPartsFreezeOneAtATimeAndProgressRetainsEveryOriginalReceipt()throws Exception {
        setup(true);int before=f.db.count("record_kind='logical_task'"),calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);assertEquals(0,progress().nextPart());for(int n=0;n<plan.chunks().size();n++){var frozen=prepare(n);assertEquals(plan.chunks().get(n).snapshotJson(),frozen.snapshotJson());assertEquals(plan.chunks().get(n).requestJson(),frozen.requestJson());assertEquals("partial",frozen.coverageState());assertEquals(1,frozen.missing().size());assertEquals(n+1,progress().nextPart());assertEquals(before+n+1,f.db.count("record_kind='logical_task'"));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}assertTrue(progress().complete());int count=f.db.count("1=1");assertEquals(prepare(0),f.f.f.f.read(()->service.committedPart(f.intent,0,action(0))));assertEquals(count,f.db.count("1=1"));
    }
    @Test void RestartReadsOnlyOriginalPlanAndPartialAuthorityWithoutCurrentTables()throws Exception {
        setup(true);var original=prepare(0);int count=f.db.count("1=1");f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,f.f.service));assertEquals(original,prepare(0));assertEquals(original,f.f.f.f.read(()->service.committedPart(f.intent,0,action(0))));assertEquals(1,progress().nextPart());assertEquals(count,f.db.count("1=1"));
    }
    @Test void AllFailedZeroPlanHasCompleteProgressAndCreatesNoSyntheticRoot()throws Exception {
        setup(false);int count=f.db.count("1=1");assertTrue(plan.chunks().isEmpty());assertTrue(progress().complete());assertEquals(0,progress().nextPart());assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->service.preparePartPartial(f.intent,0,action(0),f.permit)));assertEquals(count,f.db.count("1=1"));var actual=service;service=provider(null);assertThrows(RuntimeException.class,this::progress);service=actual;f.db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(f.f.f.ref.ownerId()));assertThrows(RuntimeException.class,this::progress);
    }
    @Test void DefaultsMissingPermitNativeChangesAndCapacityCannotQueuePartialInput()throws Exception {
        setup(true);int count=f.db.count("1=1");assertThrows(RuntimeException.class,()->service.preparePartPartial(f.intent,0,action(0),f.permit));assertThrows(RuntimeException.class,()->f.f.f.f.read(()->service.preparePartPartial(f.intent,0,action(0),f.permit)));assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->service.preparePart(f.intent,0,action(0))));assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->service.preparePartPartial(f.intent,0,action(0),null)));var actual=service;service=provider(null);assertThrows(RuntimeException.class,()->prepare(0));service=actual;String root=f.f.f.plan.chunks().getLast().rootId();long revision=f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,root);f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->prepare(0));f.db.jdbc.update("UPDATE jobs SET revision=? WHERE id=?",revision,root);var capped=spy(f.f.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).verify(any(),any());service=provider(new SourceDedupDependencyService(f.f.observations,f.f.proven,capped));assertThrows(GenerationFlowService.CapacityGuarded.class,()->prepare(0));assertEquals(count,f.db.count("1=1"));
    }
    @Test void ActualRollbackAndLostAckRecoverTheExactOriginalPart()throws Exception {
        setup(true);int count=f.db.count("1=1");assertThrows(RuntimeException.class,()->f.f.f.f.p1.tx(()->{service.preparePartPartial(f.intent,0,action(0),f.permit);throw new IllegalStateException("fictional part outer failure");}));assertEquals(count,f.db.count("1=1"));var fault=new DataSourceTransactionManager(f.db.source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional partial part ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.db.source),fault,()->25,n->{});var original=writes.execute(new SharedWriteExecutor.ActionContext(action(0),plan.chunks().getFirst().rootId(),plan.chunks().getFirst().inputHash()),key->service.committedPartPartial(f.intent,0,key,f.permit),context->service.preparePartPartial(f.intent,0,action(0),f.permit));assertEquals(original,prepare(0));assertEquals(1,progress().nextPart());assertEquals(1,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=?",Integer.class,JdbcFrozenInputs.stableId("prepare-action",action(0))));
    }
}
