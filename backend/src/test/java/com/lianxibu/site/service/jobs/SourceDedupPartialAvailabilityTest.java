package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceDedupPartialAvailabilityTest {
    SourceDedupPartialSealTest f;SourceDedupStageProofService proof;SourceDedupStageAvailabilityService observer;
    SourceDedupPartialSealTest fixture(){return new SourceDedupPartialSealTest();}
    void setup(int count)throws Exception {f=fixture();f.setup(count);var a=f.a;var results=new JdbcSourceDedupResults(a.f.f.db.source);proof=new SourceDedupStageProofService(a.f.f.plans,a.f.f.p1.universes,new SourceDedupResultProofService(results,a.f.f.plans,a.f.f.p1.universes),f.service);observe(a.f.f.clock);}
    void observe(Clock clock){var a=f.a;observer=new SourceDedupStageAvailabilityService(proof,new JdbcSourceStageAvailability(a.f.f.db.source),a.f.f.db.writes,new DataSourceTransactionManager(a.f.f.db.source),clock);}
    int observations(){return f.a.f.f.db.count("target_type='source_stage_availability'");}
    @Test void purePartialAndFullStagedBlocksCannotGrantAnyCommittedObservation()throws Exception {
        setup(1);var a=f.a;a.f.terminal(0,"failed");var pure=SourceDedupStageResult.createResolved(a.f.snapshot(a.f.ref));assertNull(a.f.f.read(()->proof.readCommitted(a.f.ref)));assertNull(observer.capture(a.f.ref));assertThrows(RuntimeException.class,()->a.f.f.read(()->observer.availableAt(pure,a.f.f.clock.instant())));a.service.stage(a.f.ref);assertEquals(2,a.blocks());assertNull(a.f.f.read(()->proof.readCommitted(a.f.ref)));assertNull(observer.capture(a.f.ref));assertEquals(0,observations());assertThrows(RuntimeException.class,()->a.f.f.p1.tx(()->observer.capture(a.f.ref)));
    }
    @Test void actualSparseSealObservedOnlyAfterCommitWithIndependentCeilClockAndStableReplay()throws Exception {
        setup(201);var a=f.a;a.f.applied(0);a.f.terminal(1,"failed");a.f.applied(2);var seal=f.service.seal(a.f.ref);var committed=a.f.f.read(()->proof.readCommitted(a.f.ref));assertEquals(seal.proof().json(),committed.proof().json());assertEquals(seal.minimumObservationTime(),committed.minimumObservationTime());assertEquals(0,observations());
        Instant floor=seal.minimumObservationTime();observe(Clock.fixed(floor.minusNanos(1),ZoneOffset.UTC));assertThrows(RuntimeException.class,()->observer.capture(a.f.ref));assertEquals(0,observations());observe(Clock.fixed(floor.plusNanos(701),ZoneOffset.UTC));Instant observed=observer.capture(a.f.ref);assertEquals(floor.plusNanos(1000),observed);assertEquals(1,observations());assertNull(a.f.f.read(()->observer.availableAt(committed.proof(),floor)));assertEquals(observed,a.f.f.read(()->observer.availableAt(committed.proof(),observed)));int count=a.f.f.db.count("1=1");observe(Clock.fixed(floor.plusSeconds(100),ZoneOffset.UTC));assertEquals(observed,observer.capture(a.f.ref));assertEquals(count,a.f.f.db.count("1=1"));assertEquals(5,a.f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void allFailedStillHasExplicitMissingAndDamagedSealCannotFallbackOrReuseObservation()throws Exception {
        setup(1);var a=f.a;a.f.terminal(0,"failed");var seal=f.service.seal(a.f.ref);var observed=observer.capture(a.f.ref);var current=a.f.f.read(()->proof.readCommitted(a.f.ref));assertEquals(1,CanonicalJson.parse(current.proof().json()).get("unapplied_member_count").asInt());assertTrue(current.proof().members().isEmpty());assertEquals(observed,a.f.f.read(()->observer.availableAt(current.proof(),observed)));
        a.f.f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcSourceDedupPartialReceipts.receiptId(a.f.ref.ownerId()));assertThrows(RuntimeException.class,()->observer.capture(a.f.ref));assertThrows(RuntimeException.class,()->a.f.f.read(()->proof.readCommitted(a.f.ref)));assertThrows(RuntimeException.class,()->a.f.f.read(()->observer.availableAt(seal.proof(),observed)));assertEquals(1,observations());
    }
    @Test void explicitPartialCapabilityKeepsAllAppliedAndZeroV1BytesExactly()throws Exception {
        for(int count:java.util.List.of(1,0)){setup(count);var a=f.a;if(count>0)a.f.applied(0);var fullOnly=new SourceDedupStageProofService(a.f.f.plans,a.f.f.p1.universes,new SourceDedupResultProofService(new JdbcSourceDedupResults(a.f.f.db.source),a.f.f.plans,a.f.f.p1.universes));var original=a.f.f.read(()->fullOnly.readCommitted(a.f.ref));var upgraded=a.f.f.read(()->proof.readCommitted(a.f.ref));assertEquals(original.proof().json(),upgraded.proof().json());assertEquals(original.proof().hash(),upgraded.proof().hash());assertEquals(original.minimumObservationTime(),upgraded.minimumObservationTime());assertEquals("source-dedup-stage-result-v1",CanonicalJson.parse(upgraded.proof().json()).get("schema_version").asText());assertNotNull(observer.capture(a.f.ref));assertEquals(1,observations());assertEquals(0,f.seals());}
    }
}
