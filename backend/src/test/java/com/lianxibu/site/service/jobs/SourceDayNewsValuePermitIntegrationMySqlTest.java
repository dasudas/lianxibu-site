package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.SourceDayRunClaimAdmissionConfiguration;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSourceDayDedupClosures;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Actual original SQL composition; OPS and fictional already-applied P1 genesis remain fixtures. */
class SourceDayNewsValuePermitIntegrationMySqlTest extends SourceDayNewsDedupBarrierIntegrationMySqlTest {
    Instant cutoff(){return p1At.plusSeconds(120);}
    SourceDayNewsValuePermitService permits(Clock clock){
        var admission=new SourceDayRunClaimAdmissionConfiguration().create(f.source,manager,new SourceDayCollectionAdmissionService(cohorts));
        return new SourceDayNewsValuePermitService(barrier(clock),admission,new JdbcSourceDayDedupClosures(f.source),graph.dedupProofs(),graph.dedupObserver(),graph.dependencies(),graph.dedupPermits(),manager);
    }
    FrozenSourceDedupDependency own(){var tx=new TransactionTemplate(manager);tx.setReadOnly(true);return tx.execute(s->{var proof=graph.dedupProofs().readCommitted(p1.ref());return FrozenSourceDedupDependency.create(proof.proof(),p1At,cutoff());});}
    void originalReady(){f.jdbc.update("UPDATE sources SET activation_stage='disabled' WHERE id='fictional-second'");var original=coordinator.open(day);coordinator.reserve(original,"fictional-source");completed();}
    @Test void actualDayPermitPersistsExactOriginalAndRechecksInShortWriter(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var own=own();var captured=service.capture(own.toJson(),cutoff());assertNotNull(captured);assertTrue(captured.dependency().originalBatchEligible());
        var tx=new TransactionTemplate(manager);assertEquals(captured.dependency().json(),tx.execute(s->service.verify(captured,cutoff())).json());assertEquals(1,closures());
        f.jdbc.execute("DROP TABLE sources");var restarted=permits(org.mockito.Mockito.mock(Clock.class));var replay=restarted.captureExisting(captured.dependency().toJson(),own.toJson(),cutoff());assertEquals(captured.dependency().json(),tx.execute(s->restarted.verify(replay,cutoff())).json());
    }
    @Test void incompleteFullDayAndUnregisteredOwnSourceCannotGrantPermit(){
        coordinator.open(day);completed();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var own=own();assertNull(service.capture(own.toJson(),cutoff()));
        var original=coordinator.open(day);coordinator.reserve(original,"fictional-source");assertNull(service.capture(own.toJson(),cutoff()));assertEquals(0,closures());
    }
    @Test void syntacticallyValidButDifferentOriginalDayHashIsRejectedByActualSQL(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var own=own();var captured=service.capture(own.toJson(),cutoff());
        for(String field:List.of("closure_hash","bundle_hash","cohort_hash","config_hash")){
            var forged=(ObjectNode)captured.dependency().toJson();forged.put(field,"d".repeat(64));assertDoesNotThrow(()->FrozenSourceDayDedupDependency.read(new String(CanonicalJson.bytes(forged),java.nio.charset.StandardCharsets.UTF_8),own,cutoff()));
            assertThrows(RuntimeException.class,()->service.captureExisting(forged,own.toJson(),cutoff()),field);
        }
    }
    @Test void originalRunReceiptAndClosureDamageAfterCaptureFailInWriter(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var original=own();var captured=service.capture(original.toJson(),cutoff());var tx=new TransactionTemplate(manager);String run=original.toJson().get("stage_key").get("run_id").asText();
        f.jdbc.update("UPDATE source_runs SET config_version=4 WHERE id=?",run);assertThrows(RuntimeException.class,()->tx.execute(s->service.verify(captured,cutoff())));f.jdbc.update("UPDATE source_runs SET config_version=3 WHERE id=?",run);
        f.jdbc.update("DELETE FROM request_receipts");assertThrows(RuntimeException.class,()->tx.execute(s->service.verify(captured,cutoff())));
        install(5);originalReady();var restored=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var fresh=restored.capture(own().toJson(),cutoff());f.jdbc.update("UPDATE jobs SET operator_id='fictional-forged' WHERE target_type='source_day_dedup'");assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(s->restored.verify(fresh,cutoff())));
    }
    @Test void originalObservationDamageAndWrongTransactionOrOwnerCannotGrantPermit(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var captured=service.capture(own().toJson(),cutoff());var tx=new TransactionTemplate(manager);
        assertThrows(RuntimeException.class,()->service.verify(captured,cutoff()));var ro=new TransactionTemplate(manager);ro.setReadOnly(true);assertThrows(RuntimeException.class,()->ro.execute(s->service.verify(captured,cutoff())));
        var ownProfile=own().toJson();assertThrows(RuntimeException.class,()->tx.execute(s->service.capture(ownProfile,cutoff())));var other=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));assertThrows(RuntimeException.class,()->tx.execute(s->other.verify(captured,cutoff())));
        f.jdbc.update("UPDATE jobs SET operator_id='fictional-forged' WHERE target_type='source_stage_availability'");assertThrows(RuntimeException.class,()->tx.execute(s->service.verify(captured,cutoff())));
    }
    @Test void lateOrdinaryProofCannotEnterAlreadyClosedOriginalBatch(){
        install(0);f.jdbc.update("UPDATE sources SET activation_stage='disabled' WHERE id='fictional-second'");var original=coordinator.open(day);coordinator.reserve(original,"fictional-source");
        Instant deadline=SourceDedupBarrier.deadline(day),later=deadline.plusSeconds(60);var closed=barrier(Clock.fixed(deadline,ZoneOffset.UTC)).close(day);String originalClosure=closed.closure().json();
        var lateGraph=new com.lianxibu.site.config.SourceAnalysisProofGraph(f.source,manager,Clock.fixed(later,ZoneOffset.UTC),()->false);lateGraph.dedupObserver().capture(p1.ref());
        var read=new TransactionTemplate(manager);read.setReadOnly(true);var own=read.execute(s->FrozenSourceDedupDependency.create(lateGraph.dedupProofs().readCommitted(p1.ref()).proof(),later,later.plusSeconds(60)));
        var service=permits(org.mockito.Mockito.mock(Clock.class));var captured=service.captureExisting(FrozenSourceDayDedupDependency.create(closed.original().plan().directory().ownerId(),closed.original().plan().bundleHash(),closed.closure(),own,later.plusSeconds(60)).toJson(),own.toJson(),later.plusSeconds(60));
        assertFalse(new TransactionTemplate(manager).execute(s->service.verify(captured,later.plusSeconds(60))).originalBatchEligible());assertEquals(originalClosure,barrier(org.mockito.Mockito.mock(Clock.class)).find(day).closure().json());
    }
    @Test void missingSqlAndDamagedOriginalP1AreNeverAnEmptySuccess(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var own=own();service.capture(own.toJson(),cutoff());f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],p1.f.part());
        assertThrows(RuntimeException.class,()->service.capture(own.toJson(),cutoff()));f.jdbc.execute("DROP TABLE request_receipts");assertThrows(RuntimeException.class,()->service.capture(own.toJson(),cutoff()));
    }
    @Test void explicitReadOnlyOriginalLookupChecksRealRunDayAndOwnP1WithoutGrantingWriter(){
        originalReady();var service=permits(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC));var own=own();var captured=service.capture(own.toJson(),cutoff());var ro=new TransactionTemplate(manager);ro.setReadOnly(true);
        assertEquals(captured.dependency().json(),ro.execute(s->service.verifyObserved(captured,cutoff())).json());assertThrows(RuntimeException.class,()->service.verifyObserved(captured,cutoff()));assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(s->service.verifyObserved(captured,cutoff())));
        String run=own.toJson().get("stage_key").get("run_id").asText();f.jdbc.update("UPDATE source_runs SET config_version=4 WHERE id=?",run);assertThrows(RuntimeException.class,()->ro.execute(s->service.verifyObserved(captured,cutoff())));f.jdbc.update("UPDATE source_runs SET config_version=3 WHERE id=?",run);
        f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],p1.f.part());assertThrows(RuntimeException.class,()->ro.execute(s->service.verifyObserved(captured,cutoff())));
    }
}
