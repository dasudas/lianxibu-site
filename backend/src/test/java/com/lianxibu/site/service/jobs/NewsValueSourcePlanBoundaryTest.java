package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NewsValueSourcePlanBoundaryTest {
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    NewsValueSourcePlanPreparationTest source() throws Exception {var f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueSourcePlanBoundaryTest.this.fixture();}};f.setup();return f;}
    NewsValueSourcePlanPreparationService.Captured capture(NewsValueSourcePlanPreparationTest f){return f.p1.tx(()->f.service.capture(f.intent,"fictional-run",f.profile));}
    @Test void actualP1DependencyIsImmutableAcrossPureBuildAndShortCommit() throws Exception {
        var f=source();String original=JdbcFrozenInputs.canonical(f.profile);var captured=capture(f);int count=f.f.count("1=1"),reads=f.settingsReads;
        assertEquals(0,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=?",Integer.class,JdbcFrozenSourcePlans.receiptId(f.intent)));((ObjectNode)f.profile).remove("reports");
        var prepared=f.service.build(captured);assertEquals(count,f.f.count("1=1"));assertEquals(reads,f.settingsReads);assertEquals("CapturedNewsValuePlan[redacted]",captured.toString());assertEquals("PreparedNewsValuePlan[redacted]",prepared.toString());
        var plan=f.p1.tx(()->f.service.commit(prepared));assertEquals(prepared.plan(),plan);assertEquals(original,JdbcFrozenInputs.canonical(CanonicalJson.parse(plan.scope().stageContextJson()).get(FrozenSourceDedupDependency.FIELD)));
        assertEquals(plan,f.readonly(()->f.service.committed(f.intent,"fictional-run")));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void changedCurrentQualificationAndEntityRevisionCannotCommitTheCapturedP2() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));int count=f.f.count("1=1");f.f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));f.f.jdbc.update("UPDATE analyzed_entities SET revision=0");f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0 WHERE terminal_state='accepted'");
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));
    }
    @Test void currentSettingsAndOriginalCollectionAreRecheckedBeforePlanPersistence() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));int count=f.f.count("1=1");f.keywords=java.util.List.of("fictional changed preference");
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));f.keywords=java.util.List.of();f.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));
    }
    @Test void changedNativeP1ObservationCannotAuthorizeTheCapturedDependency() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));int count=f.f.count("1=1");f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.count("target_type='source_stage_plan'"));
    }
    @Test void actualOriginalP1ArchiveIsStillRequiredAfterCapture() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));int count=f.f.count("1=1");
        f.f.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",java.util.HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),f.p1.collection.intentId(),JdbcFrozenInputs.stableId("source-universe","fictional-run"));
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));
    }
    @Test void commitRollbackLeavesOnlyTheRecoverableOriginalUniversePrefix() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));int count=f.f.count("1=1");assertNull(f.readonly(()->f.service.captureCommitted(f.intent,"fictional-run")));
        assertThrows(RuntimeException.class,()->f.p1.tx(()->{f.service.commit(prepared);throw new IllegalStateException("fictional P2 commit rollback");}));assertEquals(count,f.f.count("1=1"));
        var retried=f.service.build(capture(f));assertEquals(prepared.plan(),f.p1.tx(()->f.service.commit(retried)));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void committedReplayWorksAfterCapacityClosesAndCurrentTablesDisappear() throws Exception {
        var f=source();var prepared=f.service.build(capture(f));var original=f.p1.tx(()->f.service.commit(prepared));int count=f.f.count("1=1"),reads=f.settingsReads;
        f.allowed=false;f.f.jdbc.execute("DROP TABLE source_run_items");f.f.jdbc.execute("DROP TABLE analyzed_entities");f.f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");
        var replay=f.service.build(f.readonly(()->f.service.captureCommitted(f.intent,"fictional-run")));assertEquals(original,f.p1.tx(()->f.service.commit(replay)));assertEquals(count,f.f.count("1=1"));assertEquals(reads,f.settingsReads);
    }
    @Test void eachPhaseRequiresTheActualBoundaryAndNewWorkRequiresActiveNativeParent() throws Exception {
        var f=source();assertThrows(RuntimeException.class,()->f.service.capture(f.intent,"fictional-run",f.profile));assertThrows(RuntimeException.class,()->f.readonly(()->f.service.capture(f.intent,"fictional-run",f.profile)));var captured=capture(f);
        assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.build(captured)));var prepared=f.service.build(captured);int count=f.f.count("1=1");
        assertThrows(RuntimeException.class,()->f.service.commit(prepared));assertThrows(RuntimeException.class,()->f.readonly(()->f.service.commit(prepared)));f.allowed=false;assertThrows(GenerationFlowService.CapacityGuarded.class,()->f.p1.tx(()->f.service.commit(prepared)));f.allowed=true;
        f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",f.intent);assertThrows(RuntimeException.class,()->f.p1.tx(()->f.service.commit(prepared)));assertEquals(count,f.f.count("1=1"));
    }
}
