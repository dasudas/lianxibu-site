package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual original 201-member partial handoff/preparation; no model network call. */
class SourcePartialDispatchReadinessTest {
    SourcePartialPlanCoordinatorTest q;GenerationJdbcStoreTest db;DispatchReadinessService service;List<String> roots;SourcePartitionPlanner.Plan plan;boolean references=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{q=new SourcePartialPlanCoordinatorTest(){@Override GenerationJdbcStoreTest database(){return SourcePartialDispatchReadinessTest.this.database();}};q.setup(201,true);db=q.db;plan=q.prepare(0).plan();for(int n=1;n<plan.chunks().size();n++)q.prepare(n);roots=plan.chunks().stream().map(c->c.rootId()).toList();service=provider(q.q.a.service);}
    DispatchReadinessService provider(SourceDedupPartialDependencyPermitService permit){var inputs=new JdbcFrozenInputs(db.source);var deps=new SourceDedupDependencyService(q.q.a.observations,q.q.a.proven,permit);return new DispatchReadinessService(new JdbcDispatchInputs(db.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(db.source)),new GenerationLineageService(new JdbcGenerationLineage(db.source),q.clock,"fictional-owner"),mock(ContentReadService.class),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(db.source)),target->references,q.q.h.f.p1.collections::read,new SourcePartitionProofService(q.plans),true,deps,permit,new DataSourceTransactionManager(db.source));}
    boolean ready(String id,DispatchReadinessService.Captured capture){return db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-partial-dispatch-check",id,null),key->null,c->service.readyPartial(id,capture));}
    @Test void EveryActualOriginalPartRequiresOutsideCaptureAndTheOriginalWriterPermit()throws Exception{
        setup();int rows=db.count("1=1"),calls=db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);for(String root:roots){var capture=service.capturePartial(root);assertNotNull(capture);assertEquals("CapturedPartialDispatchInput[redacted]",capture.toString());assertTrue(ready(root,capture));service=provider(q.q.a.service);assertTrue(ready(root,capture));assertThrows(RuntimeException.class,()->db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-default-partial-dispatch",root,null),key->null,c->service.ready(root)));}assertEquals(rows,db.count("1=1"));assertEquals(calls,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void NativeP1ChangesAfterCaptureAndInsideWriterRefuseTheOriginalInput()throws Exception{
        setup();String root=roots.getFirst();var capture=service.capturePartial(root);String p1=q.q.a.f.plan.chunks().getLast().rootId();var changed=spy(q.q.a.service);doAnswer(call->{db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);return call.callRealMethod();}).when(changed).verify(any(),any());service=provider(changed);assertThrows(RuntimeException.class,()->ready(root,capture));service=provider(q.q.a.service);assertTrue(ready(root,capture));db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);assertThrows(RuntimeException.class,()->ready(root,capture));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void CurrentMemberChangeStillBlocksTheOtherwiseValidPartialProof()throws Exception{
        setup();String root=roots.getFirst();var capture=service.capturePartial(root);assertTrue(ready(root,capture));String member=plan.chunks().getFirst().itemIds().getFirst();db.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id=?",member);assertFalse(ready(root,capture));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void DefaultPortsTransactionsAliasesAndCapacityCannotGrantPartialSendPermission()throws Exception{
        setup();String root=roots.getFirst();var capture=service.capturePartial(root);assertThrows(RuntimeException.class,()->service.readyPartial(root,capture));assertThrows(RuntimeException.class,()->q.q.h.f.p1.tx(()->service.capturePartial(root)));assertThrows(RuntimeException.class,()->q.q.h.f.readonly(()->service.capturePartial(root)));assertThrows(RuntimeException.class,()->ready(roots.getLast(),capture));assertThrows(RuntimeException.class,()->ready(root,null));service=provider(null);assertThrows(RuntimeException.class,()->service.capturePartial(root));assertThrows(RuntimeException.class,()->ready(root,capture));var capped=spy(q.q.a.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).capture(any(),any());service=provider(capped);assertThrows(GenerationFlowService.CapacityGuarded.class,()->service.capturePartial(root));capped=spy(q.q.a.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).verify(any(),any());service=provider(capped);assertThrows(GenerationFlowService.CapacityGuarded.class,()->ready(root,capture));service=provider(q.q.a.service);assertTrue(ready(root,capture));
    }
}
