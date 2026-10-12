package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

/** Original partial P1 and actual child/source links, entirely fictional retained outcomes. */
class SourceValuePartialIntentHandoffTest {
    SourceDedupPartialDependencyPermitTest a;SourceValueIntentHandoffTest h;SourceValueIntentService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(boolean mixed)throws Exception{
        setup(mixed?101:1,mixed);
    }
    void setup(int count,boolean mixed)throws Exception{
        a=new SourceDedupPartialDependencyPermitTest(){@Override GenerationJdbcStoreTest database(){return SourceValuePartialIntentHandoffTest.this.database();}};a.setup(count,0);for(int n=0;n<a.f.plan.chunks().size();n++){if(mixed&&n<a.f.plan.chunks().size()-1)a.f.applied(n);else a.f.terminal(n,"failed");}a.original();h=new SourceValueIntentHandoffTest();h.f=new NewsValueSourcePlanPreparationTest();h.f.p1=a.f.f.p1;h.f.f=a.f.f.db;h.f.clock=a.f.f.clock;h.ref=a.f.ref;h.install();h.proofs=new SourceDedupStageProofService(h.f.p1.plans,h.f.p1.universes,new SourceDedupResultProofService(new JdbcSourceDedupResults(h.f.f.source),h.f.p1.plans,h.f.p1.universes),a.a.f.service);h.observer=new SourceDedupStageAvailabilityService(h.proofs,a.observations,h.f.f.writes,new DataSourceTransactionManager(h.f.f.source),h.f.clock);h.service=h.provider(h.f.f.writes,h.f.clock);service=provider(h.f.f.writes,a.service);
    }
    SourceValueIntentService provider(SharedWriteExecutor writes,SourceDedupPartialDependencyPermitService permit){return new SourceValueIntentService(h.proofs,h.observer,h.f.p1.plans,h.f.p1.universes,h.metadata,h.f.p1.collections,h.nativeStore,h.sourceLinks(),writes,new DataSourceTransactionManager(h.f.f.source),h.f.clock,()->h.allowed,permit);}
    SourceValueIntentReceiptService.Handoff read(){return h.f.readonly(()->service.readCommitted(h.ref));}
    @Test void PartialOriginalHandoffUsesTheWholeHashAndAllocatesOnlyOneRealChild()throws Exception{
        setup(true);assertThrows(RuntimeException.class,()->h.service.handoff(h.ref));assertEquals(0,h.children());long revision=h.sourceRevision();int calls=h.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var whole=h.f.readonly(()->h.proofs.read(h.ref));var handoff=service.handoff(h.ref);assertEquals(whole.hash(),handoff.p1ResultHash());assertEquals(2,handoff.stageParts());assertEquals(101,handoff.accepted());assertEquals("source-value-intent-handoff-v1",CanonicalJson.parse(handoff.json()).get("schema_version").asText());assertEquals(handoff.json(),read().json());assertEquals(1,h.children());assertEquals(1,h.relations());assertEquals("succeeded",h.state());assertEquals(revision+1,h.sourceRevision());assertEquals("failed",h.f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,a.f.plan.chunks().getLast().rootId()));assertEquals(calls,h.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));int rows=h.f.f.count("1=1");h.allowed=false;assertEquals(handoff.json(),service.handoff(h.ref).json());assertEquals(rows,h.f.f.count("1=1"));assertEquals(revision+1,h.sourceRevision());
    }
    @Test void AllFailedDedupStillCreatesItsOriginalValueIntentAndReadonlyRestartWorks()throws Exception{
        setup(false);var handoff=service.handoff(h.ref);assertEquals(1,handoff.accepted());assertEquals(1,handoff.stageParts());assertEquals("succeeded",h.state());assertEquals(1,h.children());assertEquals(0,h.f.f.count("record_kind='logical_task' AND task_type='news_value'"));h.f.f.jdbc.execute("DROP TABLE source_run_items");h.f.f.jdbc.execute("DROP TABLE analyzed_entities");service=provider(h.f.f.writes,a.service);assertEquals(handoff.json(),read().json());assertEquals(handoff.json(),service.handoff(h.ref).json());assertEquals(1,h.relations());
    }
    @Test void ChangedOriginalNativeAndFailedSourceLinkRollBackChildAndParentTogether()throws Exception{
        setup(false);long revision=h.sourceRevision();String original=h.state();var changed=spy(a.service);doAnswer(call->{h.f.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",a.f.plan.chunks().getFirst().rootId());return call.callRealMethod();}).when(changed).verify(any(),any());service=provider(h.f.f.writes,changed);assertThrows(RuntimeException.class,()->service.handoff(h.ref));assertEquals(0,h.children());assertEquals(0,h.relations());assertEquals(original,h.state());assertEquals(revision,h.sourceRevision());service=provider(h.f.f.writes,a.service);h.f.f.jdbc.execute("ALTER TABLE source_runs ADD CONSTRAINT fictional_partial_handoff_failure CHECK(revision<="+revision+")");assertThrows(RuntimeException.class,()->service.handoff(h.ref));assertEquals(0,h.children());assertEquals(0,h.relations());assertEquals(original,h.state());h.drop("source_runs","fictional_partial_handoff_failure");assertNotNull(service.handoff(h.ref));assertEquals(1,h.children());assertEquals(revision+1,h.sourceRevision());
    }
    @Test void CapacityTransactionsAndActualLostAckKeepOneOriginalRelationship()throws Exception{
        setup(false);assertThrows(RuntimeException.class,()->h.f.p1.tx(()->service.handoff(h.ref)));assertThrows(RuntimeException.class,()->h.f.readonly(()->service.handoff(h.ref)));var capped=spy(a.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).verify(any(),any());service=provider(h.f.f.writes,capped);assertThrows(GenerationFlowService.CapacityGuarded.class,()->service.handoff(h.ref));assertEquals(0,h.children());long revision=h.sourceRevision();var fault=new DataSourceTransactionManager(h.f.f.source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional partial handoff ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(h.f.f.source),fault,()->25,n->{});service=provider(writes,a.service);var handoff=service.handoff(h.ref);assertEquals(handoff.json(),read().json());service=provider(h.f.f.writes,a.service);assertEquals(handoff.json(),service.handoff(h.ref).json());assertEquals(1,h.children());assertEquals(1,h.relations());assertEquals(revision+1,h.sourceRevision());
    }
}
