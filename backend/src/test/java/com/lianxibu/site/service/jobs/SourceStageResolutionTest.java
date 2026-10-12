package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** Real archives and retained fictional results, with native terminal fault injection. */
class SourceStageResolutionTest {
    SourceAnalysisParentStepTest f;SourceStageResolutionService service;JdbcSourceStageRoots roots;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourceAnalysisParentStepTest fixture(){return new SourceAnalysisParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceStageResolutionTest.this.database();}};}
    void provider(){roots=new JdbcSourceStageRoots(f.db.source);service=provider(roots);}
    SourceStageResolutionService provider(JdbcSourceStageRoots rootStore){
        var nativeResults=new JdbcSourceDedupResults(f.db.source);var dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.db.source));
        return new SourceStageResolutionService(rootStore,f.plans,f.p1.universes,f.parts,new JdbcFrozenInputs(f.db.source),nativeResults,
                new SourceDedupResultProofService(nativeResults,f.plans,f.p1.universes),new SourceValueResultProofService(nativeResults,f.plans,f.p1.universes,dependencies),dependencies);
    }
    @BeforeEach void setup() throws Exception {f=fixture();f.newSource();provider();}
    FrozenSourceStagePlan.Reference prepared(){f.step.step(f.candidate(f.p1.collection.intentId()));return f.reference(f.p1.collection.intentId());}
    SourceStageResolutionService.Snapshot read(FrozenSourceStagePlan.Reference ref){return f.read(()->service.read(ref));}
    @Test void plannedButUnpreparedIsMissingAndUnknownOrConfigurationStillWaits(){
        f.p1.prepare();var ref=f.reference(f.p1.collection.intentId());var missing=read(ref);assertFalse(missing.settled());assertFalse(missing.hasFailures());assertEquals(SourceStageResolutionService.State.MISSING,missing.fragments().getFirst().state());assertNull(missing.fragments().getFirst().nativeRoot());
        f.p1.tx(()->f.parts.preparePart(ref.ownerId(),0,SourcePlanPreparationCoordinator.partAction(ref.ownerId(),0)));String root=missing.plan().chunks().getFirst().rootId();int count=f.db.count("1=1");
        for(String state:List.of("queued","running","waiting_retry","cancel_requested","unknown_external","blocked","blocked_configuration")){
            f.db.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,root);var current=read(ref);assertEquals(SourceStageResolutionService.State.PENDING,current.fragments().getFirst().state());assertFalse(current.settled());assertFalse(current.hasFailures());
        }
        assertEquals(count,f.db.count("1=1"));assertEquals(0,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertThrows(UnsupportedOperationException.class,()->missing.fragments().clear());
    }
    @Test void onlyTheOriginalPreparedTerminalStatesResolveAsMissingEvidence(){
        var ref=prepared();String root=read(ref).plan().chunks().getFirst().rootId();int count=f.db.count("1=1");
        for(String state:List.of("failed","cancelled","discarded","superseded")){
            f.db.jdbc.update("UPDATE jobs SET state=?,call_count=? WHERE id=?",state,state.equals("failed")?3:0,root);var value=read(ref);assertTrue(value.settled());assertTrue(value.hasFailures());assertEquals(state.toUpperCase(Locale.ROOT),value.fragments().getFirst().state().name());assertNull(value.fragments().getFirst().dedup());assertNull(value.fragments().getFirst().value());
        }
        assertEquals(count,f.db.count("1=1"));assertEquals("SourceStageResolution[redacted]",read(ref).toString());
    }
    @Test void actualAppliedP1AndOriginalV2P2RequireCompleteRetainedProofs() throws Exception {
        f=fixture();f.completedP1();provider();var p1=read(f.handoffs.ref);assertTrue(p1.settled());assertFalse(p1.hasFailures());assertNotNull(p1.fragments().getFirst().dedup());assertEquals(SourceStageResolutionService.State.APPLIED,p1.fragments().getFirst().state());
        f.step.step(f.candidate(f.handoffs.ref.ownerId()));String child=f.handoffs.read().p2Owner();f.step.step(f.candidate(child));var ref=f.reference(child);assertFalse(read(ref).settled());f.completeP2(ref);var p2=read(ref);assertTrue(p2.settled());assertNotNull(p2.fragments().getFirst().value());assertNull(p2.fragments().getFirst().dedup());int count=f.db.count("1=1");provider();assertEquals(p2.fragments().getFirst().value().proof().handoffHash(),read(ref).fragments().getFirst().value().proof().handoffHash());assertEquals(count,f.db.count("1=1"));
        f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_member_stage' AND target_key_json LIKE ?",new byte[32],"%"+p2.plan().chunks().getFirst().rootId()+"%");assertThrows(RuntimeException.class,()->read(ref));
    }
    @Test void retainedSupersededOrDiscardedResultsNeverBecomeApplied() throws Exception {
        f=fixture();f.completedP1();f.step.step(f.candidate(f.handoffs.ref.ownerId()));String child=f.handoffs.read().p2Owner();f.step.step(f.candidate(child));var ref=f.reference(child);f.completeP2(ref);provider();String root=read(ref).plan().chunks().getFirst().rootId();
        f.db.jdbc.update("DELETE FROM jobs WHERE target_type='source_member_stage' AND target_key_json LIKE ?","%"+root+"%");
        for(String state:List.of("superseded","discarded")){
            f.db.jdbc.update("UPDATE jobs SET state=?,application_state=?,applied_at=NULL WHERE id=?",state,state.toUpperCase(Locale.ROOT),root);var value=read(ref);assertTrue(value.settled());assertTrue(value.hasFailures());assertNull(value.fragments().getFirst().value());
        }
        f.db.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result'",new byte[32],root);assertThrows(RuntimeException.class,()->read(ref));
    }
    @Test void badBudgetPreparationInputAndContradictoryAppliedHeadersAreRejected(){
        final var ref=prepared();String root=read(ref).plan().chunks().getFirst().rootId();f.db.jdbc.update("UPDATE jobs SET state='failed',call_count=2 WHERE id=?",root);assertThrows(RuntimeException.class,()->read(ref));
        f.db.jdbc.update("UPDATE jobs SET state='failed',call_count=3,application_state='APPLIED' WHERE id=?",root);assertThrows(RuntimeException.class,()->read(ref));f.db.jdbc.update("UPDATE jobs SET state='queued',call_count=0,application_state=NULL WHERE id=?",root);
        f.db.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='input'",new byte[32],root);assertThrows(RuntimeException.class,()->read(ref));
        f=fixture();assertDoesNotThrow(()->f.newSource());provider();final var original=prepared();f.db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenInputs.stableId("prepare-action",SourcePlanPreparationCoordinator.partAction(original.ownerId(),0)));assertThrows(RuntimeException.class,()->read(original));
    }
    @Test void zeroPlansAndValueDependenciesRemainStrictWithoutAnyObservationOrModel() throws Exception {
        f.db.jdbc.update("UPDATE source_run_items SET effective_valid=0");f.p1.prepare();var ref=f.reference(f.p1.collection.intentId());var zero=read(ref);assertTrue(zero.settled());assertFalse(zero.hasFailures());assertTrue(zero.fragments().isEmpty());assertEquals(0,f.db.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(0,f.db.count("target_type='source_stage_availability'"));
        f=fixture();f.completedP1();f.step.step(f.candidate(f.handoffs.ref.ownerId()));String child=f.handoffs.read().p2Owner();f.step.step(f.candidate(child));provider();final var valueRef=f.reference(child);f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,()->read(valueRef));
    }
    @Test void changedNativeFactsDuringReadAndWrongTransactionCannotDeclareResolution(){
        final var ref=prepared();String root=read(ref).plan().chunks().getFirst().rootId();var concurrent=spy(roots);var count=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation->{var result=invocation.callRealMethod();if(count.incrementAndGet()==1){CompletableFuture.runAsync(()->f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root)).get(5,TimeUnit.SECONDS);}return result;}).when(concurrent).find(root);service=provider(concurrent);assertThrows(RuntimeException.class,()->read(ref));
        assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.read(ref)));
        service=provider(roots);assertThrows(RuntimeException.class,()->read(new FrozenSourceStagePlan.Reference(ref.ownerId(),"c".repeat(64),ref.indexHash())));
    }
}
