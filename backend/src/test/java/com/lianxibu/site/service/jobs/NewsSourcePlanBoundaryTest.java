package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NewsSourcePlanBoundaryTest {
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    NewsSourcePlanPreparationTest source() throws Exception {var f=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsSourcePlanBoundaryTest.this.fixture();}};f.setup();return f;}
    NewsSourcePlanPreparationService.Captured capture(NewsSourcePlanPreparationTest f){return f.tx(()->f.preparation.capture(f.collection.intentId(),"fictional-run"));}
    void entity(NewsSourcePlanPreparationTest f,String id){String key="{\"key\":\""+id+"\",\"namespace\":\"fictional-news\"}";f.f.jdbc.update("INSERT INTO analyzed_entities(id,category,identity_key,identity_hash,title,summary,revision,first_recorded_at,analysis_state,legacy_flag) VALUES(?,'news_tech',?,?,?,NULL,0,?,'pending',0)",id,key,JdbcEntityDedup.identityHash("news_tech",key),"Fictional candidate title",Timestamp.from(f.f.clock.instant().minusSeconds(1)));}
    @Test void actualCapturePureBuildAndShortCommitPreserveTheWholeOriginal() throws Exception {
        var f=source();var captured=capture(f);assertEquals(0,f.f.count("target_type='source_stage_plan'"));int count=f.f.count("1=1"),reads=f.settingsReads;
        var prepared=f.preparation.build(captured);assertEquals(count,f.f.count("1=1"));assertEquals(reads,f.settingsReads);assertEquals("CapturedNewsSourcePlan[redacted]",captured.toString());assertEquals("PreparedNewsSourcePlan[redacted]",prepared.toString());
        var plan=f.tx(()->f.preparation.commit(prepared));assertEquals(prepared.plan(),plan);assertEquals(2,plan.decisions().size());assertEquals(1,plan.chunks().size());
        assertEquals(plan,readonly(f,()->f.preparation.committed(f.collection.intentId(),"fictional-run")));assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void pureBuildWorksWithoutTheCurrentHistoryTableButCommitRechecksIt() throws Exception {
        var f=source();var captured=capture(f);int count=f.f.count("1=1");f.f.jdbc.execute("DROP TABLE analyzed_entities");var prepared=f.preparation.build(captured);
        assertNotNull(prepared.plan());assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.count("target_type='source_stage_plan'"));
    }
    @Test void changedCurrentQualificationCannotPublishTheCapturedPlan() throws Exception {
        var f=source();var prepared=f.preparation.build(capture(f));int count=f.f.count("1=1");f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0,revision=revision+1 WHERE terminal_state='accepted'");
        assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.count("target_type='source_stage_plan'"));
    }
    @Test void changedOrAddedCurrentHistoryCannotMasqueradeAsTheOriginalCapture() throws Exception {
        var f=source();entity(f,"fictional-candidate-one");var prepared=f.preparation.build(capture(f));int count=f.f.count("1=1");
        f.f.jdbc.update("UPDATE analyzed_entities SET title='Fictional replacement title',revision=revision+1");assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(count,f.f.count("1=1"));
        f.f.jdbc.update("UPDATE analyzed_entities SET title='Fictional candidate title',revision=0");entity(f,"fictional-candidate-two");assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(0,f.f.count("target_type='source_stage_plan'"));
    }
    @Test void changedSettingsAndOriginalCollectionAreRejectedAtCommit() throws Exception {
        var f=source();var settings=new AtomicReference<>(new FrozenGenerationSettings(1,List.of(),List.of()));
        f.preparation=new NewsSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.f.source),f.universes,f.plans,f.collections,f.eligibility,f.history,settings::get,()->f.allowed);
        var prepared=f.preparation.build(capture(f));int count=f.f.count("1=1");settings.set(new FrozenGenerationSettings(2,List.of(),List.of("fictional changed preference")));
        assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(count,f.f.count("1=1"));settings.set(new FrozenGenerationSettings(1,List.of(),List.of()));f.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");
        assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(0,f.f.count("target_type='source_stage_plan'"));
    }
    @Test void capturePrefixIsRecoverableAndCommitRollbackLeavesNoPlanOrRoot() throws Exception {
        var f=source();var prepared=f.preparation.build(capture(f));int count=f.f.count("1=1");assertTrue(count>2);
        assertThrows(RuntimeException.class,()->f.tx(()->{f.preparation.commit(prepared);throw new IllegalStateException("fictional commit rollback");}));assertEquals(count,f.f.count("1=1"));
        assertNull(readonly(f,()->f.preparation.captureCommitted(f.collection.intentId(),"fictional-run")));
        var retried=f.preparation.build(capture(f));assertEquals(prepared.plan(),f.tx(()->f.preparation.commit(retried)));
    }
    @Test void actualCommittedReplayNeedsNoNewCurrentFactsOrCapacity() throws Exception {
        var f=source();var prepared=f.preparation.build(capture(f));var original=f.tx(()->f.preparation.commit(prepared));int count=f.f.count("1=1"),reads=f.settingsReads;
        f.allowed=false;f.f.jdbc.execute("DROP TABLE analyzed_entities");f.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");
        var replay=f.preparation.build(readonly(f,()->f.preparation.captureCommitted(f.collection.intentId(),"fictional-run")));
        assertEquals(original,f.tx(()->f.preparation.commit(replay)));assertEquals(count,f.f.count("1=1"));assertEquals(reads,f.settingsReads);
    }
    @Test void eachPhaseEnforcesItsActualTransactionBoundaryAndNativeParent() throws Exception {
        var f=source();assertThrows(RuntimeException.class,()->f.preparation.capture(f.collection.intentId(),"fictional-run"));assertThrows(RuntimeException.class,()->readonly(f,()->f.preparation.capture(f.collection.intentId(),"fictional-run")));
        var captured=capture(f);assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.build(captured)));var prepared=f.preparation.build(captured);int count=f.f.count("1=1");
        assertThrows(RuntimeException.class,()->f.preparation.commit(prepared));assertThrows(RuntimeException.class,()->readonly(f,()->f.preparation.commit(prepared)));f.allowed=false;assertThrows(GenerationFlowService.CapacityGuarded.class,()->f.tx(()->f.preparation.commit(prepared)));f.allowed=true;
        f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",f.collection.intentId());assertThrows(RuntimeException.class,()->f.tx(()->f.preparation.commit(prepared)));assertEquals(count,f.f.count("1=1"));
    }
    <T>T readonly(NewsSourcePlanPreparationTest f,java.util.function.Supplier<T> work){var t=new TransactionTemplate(new DataSourceTransactionManager(f.f.source));t.setReadOnly(true);return t.execute(s->work.get());}
}
