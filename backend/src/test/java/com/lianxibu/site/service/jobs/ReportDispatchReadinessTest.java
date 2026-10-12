package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.ReportDispatchNativeReferenceFixture;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** Actual formal native report preparation/lineage/dependencies and full reference proof. No real model call. */
class ReportDispatchReadinessTest {
 boolean mysql(){return false;}
 ReportDispatchPermitTest h;DispatchReadinessService readiness;
 void setup()throws Exception{h=new ReportDispatchPermitTest(){@Override boolean mysql(){return ReportDispatchReadinessTest.this.mysql();}};h.setup();readiness=create(h.permits);}
 DispatchReadinessService create(ReportDispatchPermitService permits){var base=h.h.f.data.nativeData().fixture().f();var inputs=new JdbcFrozenInputs(base.source);var contents=new ContentReadService(new JdbcContentReads(base.source),base.tx,ContentReadService.denyUnverifiedReferences());var references=ReportDispatchNativeReferenceFixture.create(base.source,base.tx);return new DispatchReadinessService(new JdbcDispatchInputs(base.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(base.source)),new GenerationLineageService(new JdbcGenerationLineage(base.source),Clock.systemUTC(),"analysis-worker"),contents,mock(SourceMemberEvidenceService.class),references,null,null,false,null,null,base.tx,null,null,permits);}
 DispatchReadinessService.Captured capture(){return ReportOriginalCompletionTest.utc(()->readiness.captureSource(h.original.input().ownerId()));}
 boolean preflight(){return ReportOriginalCompletionTest.utc(()->{var base=h.h.f.data.nativeData().fixture().f();var tx=new TransactionTemplate(base.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx.execute(s->readiness.preflight(h.original.input().ownerId()));});}
 boolean ready(DispatchReadinessService.Captured value){return ReportOriginalCompletionTest.utc(()->{var base=h.h.f.data.nativeData().fixture().f();return base.writes.execute(base.action("fictional-real-report-ready"),k->null,a->readiness.readyCaptured(h.original.input().ownerId(),value));});}
 @Test void ActualFormalReportPassesNativePreflightAndFinalWholeProgramReferenceRequestValidation()throws Exception{setup();assertTrue(preflight());var value=capture();assertNotNull(value);assertTrue(ready(value));var base=h.h.f.data.nativeData().fixture().f();assertEquals(2,base.sends);assertEquals(0,new JdbcJobRecords(base.source).byId(h.original.input().ownerId()).callCount());}
 @Test void PlainWriterReadyCannotBypassOpaqueNativeReportCapture()throws Exception{setup();var base=h.h.f.data.nativeData().fixture().f();assertThrows(RuntimeException.class,()->ReportOriginalCompletionTest.utc(()->base.writes.execute(base.action("fictional-uncaptured-report-dispatch"),k->null,a->readiness.ready(h.original.input().ownerId()))));assertTrue(preflight());var disabled=create(null);assertThrows(RuntimeException.class,()->ReportOriginalCompletionTest.utc(()->disabled.captureSource(h.original.input().ownerId())));}
 @Test void ChangedNativeProgramReceiptAfterCaptureRejectsBeforeAnyReservationOrSend()throws Exception{setup();var value=capture();var base=h.h.f.data.nativeData().fixture().f();base.jdbc.update("UPDATE jobs SET operator_id='fictional-wrong-coordinator' WHERE target_type='generation_prepare' AND operator_id=?",JdbcReportPreparedInputs.ACTOR);assertThrows(RuntimeException.class,()->ready(value));assertEquals(0,new JdbcJobRecords(base.source).byId(h.original.input().ownerId()).callCount());assertEquals(0L,base.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_send' AND target_key_json LIKE ?",Long.class,"%"+h.original.input().ownerId()+"%"));}
 @Test void ForeignNativePermitOrDamagedFirstRequestCannotCrossWriterBoundary()throws Exception{setup();var value=capture();var base=h.h.f.data.nativeData().fixture().f();var other=new ReportDispatchPermitService(base.source,h.h.originals,h.noUpdates,base.tx);readiness=create(other);assertThrows(RuntimeException.class,()->ready(value));readiness=create(h.permits);base.jdbc.update("UPDATE jobs SET request_json='{}' WHERE root_job_id=? AND attempt_no=1",h.original.input().ownerId());assertThrows(RuntimeException.class,this::preflight);assertEquals(2,base.sends);}
}
