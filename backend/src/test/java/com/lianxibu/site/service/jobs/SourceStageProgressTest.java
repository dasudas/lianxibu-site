package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class SourceStageProgressTest {
    SourceStagePreparationTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceStagePreparationTest(){@Override FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceStageProgressTest.this.database();}};}};f.setup();}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.fixture.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    SourceStagePreparationService.Progress progress(){return readonly(()->f.service.progress(f.f.universeRef.ownerId()));}
    GenerationFlowService.Frozen prepare(int part){return f.prepare(part,SourcePlanPreparationCoordinator.partAction(f.f.universeRef.ownerId(),part));}
    @Test void exactOriginalReceiptsReconstructAContinuousCursorAfterRestart(){
        int reads=f.f.sourceReads;var initial=progress();assertEquals(f.f.plan,initial.plan());assertEquals(0,initial.nextPart());assertFalse(initial.complete());var first=prepare(0);assertEquals(1,progress().nextPart());assertFalse(progress().complete());var second=prepare(1);var done=progress();assertEquals(2,done.nextPart());assertTrue(done.complete());int count=f.f.count();f.service=f.service(f.f.fixture.clock);assertEquals(done,progress());assertEquals(first,prepare(0));assertEquals(second,prepare(1));assertEquals(count,f.f.count());assertEquals(reads,f.f.sourceReads);assertEquals(0,f.f.fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void aPreparedFragmentAfterAHoleCannotBeUsedToSkipIt(){prepare(1);int count=f.f.count();assertThrows(RuntimeException.class,this::progress);assertEquals(count,f.f.count());assertEquals(1,f.f.fixture.count("record_kind='logical_task'"));}
    @Test void rehashedWrongNativeTargetCannotBecomeProgressOrTriggerAFallback(){
        prepare(0);String id=JdbcFrozenInputs.stableId("prepare-action",SourcePlanPreparationCoordinator.partAction(f.f.universeRef.ownerId(),0));var n=(ObjectNode)CanonicalJson.parse(f.f.fixture.jdbc.queryForObject("SELECT target_key_json FROM jobs WHERE id=?",String.class,id));n.put("kind","generation_intent");String raw=JdbcFrozenInputs.canonical(n);f.f.fixture.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);int count=f.f.count();assertThrows(RuntimeException.class,this::progress);assertEquals(count,f.f.count());
    }
    @Test void actualReadonlyAndExactOriginalIdentityAreRequired(){
        assertThrows(RuntimeException.class,()->f.service.progress(f.f.universeRef.ownerId()));assertThrows(RuntimeException.class,()->f.f.tx(()->f.service.progress(f.f.universeRef.ownerId())));assertThrows(RuntimeException.class,()->readonly(()->f.service.progress(f.f.universeRef.ownerId().toUpperCase(Locale.ROOT))));f.f.fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);assertThrows(RuntimeException.class,this::progress);
    }
    @Test void originalZeroChunksHaveCompletePreparationWithoutAnySyntheticReceipt(){
        f.f.clearPlan();var prior=f.f.plan;f.f.plan=new SourcePartitionPlanner().plan(prior.stage(),prior.logicalKey(),prior.generation(),f.f.original,f.f.universeRef,f.f.original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),"excluded".equals(m.terminalState())?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList(),prior.scope(),(ids,n,root)->{throw new AssertionError("empty selection has no request");});f.f.freeze();int count=f.f.count();var result=progress();assertEquals(0,result.nextPart());assertTrue(result.complete());assertEquals(f.f.plan,result.plan());assertEquals(result,progress());assertEquals(count,f.f.count());assertEquals(0,f.f.fixture.count("record_kind IN ('logical_task','ai_attempt') OR target_type='generation_prepare'"));
    }
}
