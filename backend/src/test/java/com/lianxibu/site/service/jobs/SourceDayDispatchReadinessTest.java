package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual P1/P2 preparation and dispatch SQL; the original day provider is an explicit double. */
class SourceDayDispatchReadinessTest {
    SourceDayPlanCoordinatorTest q;GenerationJdbcStoreTest db;DispatchReadinessService service;String root;boolean references=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{
        q=new SourceDayPlanCoordinatorTest(){@Override GenerationJdbcStoreTest database(){return SourceDayDispatchReadinessTest.this.database();}};q.setup();root=q.prepare().root().ownerId();db=q.f.f.f;
        when(q.days.captureExisting(any(),any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return q.day;});service=provider(q.days);
    }
    DispatchReadinessService provider(SourceDayNewsValuePermitService days){var inputs=new JdbcFrozenInputs(db.source);return new DispatchReadinessService(new JdbcDispatchInputs(db.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(db.source)),new GenerationLineageService(new JdbcGenerationLineage(db.source),q.f.f.clock,"fictional-owner"),mock(ContentReadService.class),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(db.source)),target->references,q.f.f.p1.collections::read,new SourcePartitionProofService(q.f.f.plans),true,q.f.f.dependencies,null,new DataSourceTransactionManager(db.source),days);}
    boolean ready(String id,DispatchReadinessService.Captured captured){return db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-day-dispatch-check",id,null),key->null,c->service.readyCaptured(id,captured));}
    @Test void ActualFullDayCaptureBindsOriginalRootAndRequiresWriterRecheck()throws Exception{
        setup();int rows=db.count("1=1"),calls=db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var captured=service.captureSource(root);assertNotNull(captured);assertTrue(ready(root,captured));verify(q.days).captureExisting(any(),any(),any());verify(q.days,atLeastOnce()).verify(eq(q.day),any());assertThrows(RuntimeException.class,()->db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-no-day-capture",root,null),key->null,c->service.ready(root)));db.jdbc.update("UPDATE source_run_items SET revision=revision+1");assertFalse(ready(root,captured));assertEquals(rows,db.count("1=1"));assertEquals(calls,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void DayLostAfterCaptureCannotGrantClaimOrSendQualification()throws Exception{
        setup();int running=db.count("record_kind='ai_attempt' AND state='running'");var captured=service.captureSource(root);when(q.days.verify(eq(q.day),any())).thenThrow(new IllegalStateException("fictional original day damaged"));assertThrows(RuntimeException.class,()->ready(root,captured));assertEquals(0,db.count("target_type='worker_send'"));assertEquals(running,db.count("record_kind='ai_attempt' AND state='running'"));
    }
    @Test void WrongTransactionAliasMissingProviderAndNoCaptureStayClosed()throws Exception{
        setup();var captured=service.captureSource(root);assertThrows(RuntimeException.class,()->service.readyCaptured(root,captured));assertThrows(RuntimeException.class,()->q.f.f.readonly(()->service.readyCaptured(root,captured)));assertThrows(RuntimeException.class,()->q.f.f.p1.tx(()->service.captureSource(root)));assertThrows(RuntimeException.class,()->q.f.f.readonly(()->service.captureSource(root)));assertThrows(RuntimeException.class,()->ready(root.toUpperCase(Locale.ROOT),captured));assertThrows(RuntimeException.class,()->ready(root,null));service=provider(null);assertThrows(RuntimeException.class,()->service.captureSource(root));assertThrows(RuntimeException.class,()->ready(root,captured));
    }
    @Test void InstalledDayProviderRejectsLegacyP2InputWithoutAnOriginalDay()throws Exception{
        q=new SourceDayPlanCoordinatorTest(){@Override GenerationJdbcStoreTest database(){return SourceDayDispatchReadinessTest.this.database();}};q.setup();db=q.f.f.f;q.f.f.p1.tx(()->q.f.f.service.prepare(q.intent,"fictional-run",q.f.f.profile));root=q.f.f.p1.tx(()->q.parts.preparePart(q.intent,0,SourcePlanPreparationCoordinator.partAction(q.intent,0))).ownerId();service=provider(q.days);assertThrows(RuntimeException.class,()->service.captureSource(root));assertThrows(RuntimeException.class,()->db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-legacy-day-check",root,null),key->null,c->service.ready(root)));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void PartialDayRequiresBothActualNativeP1PermitAndOriginalDay()throws Exception{
        var partial=new SourceValueDayResolutionTest(){@Override GenerationJdbcStoreTest database(){return SourceDayDispatchReadinessTest.this.database();}};partial.setup(true);partial.prepare(0);db=partial.db;root=partial.f.plan.chunks().getFirst().rootId();var dayFixture=(SourceValueDayResolutionTest.DayPartialPlanFixture)partial.f.f;
        when(dayFixture.days.captureExisting(any(),any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return dayFixture.day;});var inputs=new JdbcFrozenInputs(db.source);var deps=new SourceDedupDependencyService(partial.authority.observations,partial.authority.proven,partial.authority.service);
        service=new DispatchReadinessService(new JdbcDispatchInputs(db.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(db.source)),new GenerationLineageService(new JdbcGenerationLineage(db.source),partial.f.f.clock,"fictional-owner"),mock(ContentReadService.class),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(db.source)),target->true,partial.original.p1.collections::read,new SourcePartitionProofService(partial.f.f.plans),true,deps,partial.authority.service,new DataSourceTransactionManager(db.source),dayFixture.days);
        var captured=service.captureSource(root);assertTrue(ready(root,captured));String p1=partial.authority.f.plan.chunks().getLast().rootId();db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);assertThrows(RuntimeException.class,()->ready(root,captured));assertEquals(0,db.count("target_type='worker_send'"));
    }
}
