package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ReportDispatchNativeReferenceFixture;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import org.junit.jupiter.api.Test;
/** Real formal preparation/claim/send/native result flow; source/settings and model response are fictional. */
class ReportProgramFailureRetentionTest {
 boolean mysql(){return false;}
 ReportNativeGenerationIntegrationTest h;
 void setup()throws Exception{h=new ReportNativeGenerationIntegrationTest(){@Override boolean mysql(){return ReportProgramFailureRetentionTest.this.mysql();}};h.setup();}
 ResultJdbcStore store(boolean manual){var base=h.h.h.h.f.data.nativeData().fixture().f();return new ResultJdbcStore(base.source,h.identity,new ContentReadService(new JdbcContentReads(base.source),base.tx,ContentReadService.denyUnverifiedReferences()),ReportDispatchNativeReferenceFixture.create(base.source,base.tx),h.clock,"analysis-worker",manual);}
 @Test void LostProgramAuthorityKeepsActualValidatedRawResultForAuditButNeverWritesBody()throws Exception{setup();var base=h.h.h.h.f.data.nativeData().fixture().f();assertTrue(store(false).retainAfterAutomaticFailure(h.app.input()));base.jdbc.update("DELETE FROM jobs WHERE target_type='generation_prepare' AND operator_id=?",JdbcReportPreparedInputs.ACTOR);assertThrows(RuntimeException.class,h::apply);var root=new JdbcResultState(base.source).root(h.identity.rootId());assertNotNull(root.resultHash());assertNull(root.appliedAt());assertEquals(CanonicalJson.parse(h.raw),CanonicalJson.parse(new JdbcResultState(base.source).retained(root)));assertEquals(0L,base.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE kind='report'",Long.class));assertNull(h.ready());assertEquals(1,h.reportSends);assertEquals(1,new JdbcJobRecords(base.source).byId(h.identity.rootId()).callCount());}
 @Test void RealReadyWriteFailureRollsBackBodyAndIntentsWhileSeparateAuditRetentionCommits()throws Exception{setup();var base=h.h.h.h.f.data.nativeData().fixture().f();base.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_report_audit_ready_failure CHECK(target_type IS NULL OR target_type<>'report_result_ready')");assertThrows(RuntimeException.class,h::apply);var root=new JdbcResultState(base.source).root(h.identity.rootId());assertNotNull(root.resultHash());assertNull(root.appliedAt());assertEquals(0L,base.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE kind='report'",Long.class));assertEquals(0L,base.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type IN ('weekly_report','monthly_report')",Long.class));assertEquals(1,h.reportSends);}
 @Test void ManualOrLostNativeSendClaimOrChangedInputCannotRequestAutomaticAuditRetention()throws Exception{setup();assertFalse(store(true).retainAfterAutomaticFailure(h.app.input()));var base=h.h.h.h.f.data.nativeData().fixture().f();base.jdbc.update("DELETE FROM jobs WHERE target_type='worker_claim'");assertFalse(store(false).retainAfterAutomaticFailure(h.app.input()));setup();base=h.h.h.h.f.data.nativeData().fixture().f();base.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",new byte[32],h.identity.rootId());assertFalse(store(false).retainAfterAutomaticFailure(h.app.input()));}
 @Test void LegacyReportWithoutFormalPreparationProfileDoesNotGainNewRetentionPermission(){var legacy=mysql()?new ResultJdbcMySqlTest():new ResultJdbcStoreTest();legacy.fixture();assertFalse(legacy.store.retainAfterAutomaticFailure(legacy.store.input()));}
}
