package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueStageAvailabilityTest {
    SourceValueStageProofTest f;JdbcSourceValueStageAvailability store;SourceValueStageAvailabilityService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueStageAvailabilityTest.this.fixture();}};f.setup();installObserver();}
    void installObserver(){store=new JdbcSourceValueStageAvailability(f.f.source);service=observer(f.clock,f.f.writes);}
    SourceValueStageAvailabilityService observer(Clock clock,SharedWriteExecutor writes){return new SourceValueStageAvailabilityService(f.service,store,writes,new DataSourceTransactionManager(f.f.source),clock);}
    int observations(){return f.f.count("target_type='source_value_stage_availability'");}
    SourceValueStageProofService.Committed committed(){return f.readonly(()->f.service.readCommitted(f.ref));}
    Instant available(SourceValueStageProofService.Committed proof,Instant cutoff){return f.readonly(()->service.availableAt(proof,cutoff));}
    @Test void completeOriginalStageGetsOneFirstObservationAndExactCutoffAvailability(){
        assertNull(service.capture(f.ref));assertEquals(0,observations());f.complete(0);var proof=committed();assertNull(available(proof,f.clock.instant().plusSeconds(100)));var now=f.clock.instant().plusNanos(1);var observed=observer(Clock.fixed(now,ZoneOffset.UTC),f.f.writes).capture(f.ref);assertEquals(f.clock.instant().plusNanos(1000),observed);assertNull(available(proof,f.clock.instant()));assertEquals(observed,available(proof,observed));
        assertEquals(observed,observer(Clock.fixed(now.plusSeconds(100),ZoneOffset.UTC),f.f.writes).capture(f.ref));assertEquals(1,observations());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertTrue(proof.result().observationKeyJson().length()<2048);
    }
    @Test void zeroOriginalPlanStillNeedsItsOwnCommittedObservation(){
        f.install(0);installObserver();var proof=committed();assertNull(available(proof,f.clock.instant().plusSeconds(100)));var observed=service.capture(f.ref);assertEquals(f.clock.instant(),observed);assertEquals(observed,available(proof,observed));assertNull(available(proof,proof.minimumObservationTime()));assertEquals(1,observations());assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void clockIsAfterTheReadTransactionAndCannotPrecedeAnOriginalApplication(){
        f.complete(0);Clock guarded=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(0,observations());return f.clock.instant().plusNanos(1);}};
        assertThrows(RuntimeException.class,()->f.tx(()->observer(guarded,f.f.writes).capture(f.ref)));assertThrows(RuntimeException.class,()->observer(Clock.fixed(f.clock.instant().minusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref));assertEquals(0,observations());assertEquals(f.clock.instant().plusNanos(1000),observer(guarded,f.f.writes).capture(f.ref));
    }
    @Test void changedRolesRehashedEarlyTimesAndBadOriginalProofNeverGrantAvailability(){
        f.complete(0);service.capture(f.ref);var proof=committed();String id=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_value_stage_availability'",String.class);String saved=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);
        f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,()->available(proof,f.clock.instant()));f.f.jdbc.update("UPDATE jobs SET operator_id='source-value-stage-observer' WHERE id=?",id);
        var p=(ObjectNode)CanonicalJson.parse(saved);var early=f.clock.instant().minusSeconds(1);p.put("observed_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(early));String raw=JdbcFrozenInputs.canonical(p);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,acted_at=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),java.sql.Timestamp.from(early),id);assertThrows(RuntimeException.class,()->available(proof,f.clock.instant()));assertThrows(RuntimeException.class,()->service.capture(f.ref));
        f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,acted_at=? WHERE id=?",saved,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(saved))),java.sql.Timestamp.from(f.clock.instant()),id);f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(f.ref.ownerId()));assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(1,observations());
    }
    @Test void appendRollbackAndCommittedAckLossRecoverTheSamePermanentObservation(){
        f.complete(0);f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_value_observer_failure CHECK(target_type IS NULL OR target_type<>'source_value_stage_availability')");assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(0,observations());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_value_observer_failure":"ALTER TABLE jobs DROP CHECK fictional_value_observer_failure");
        var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var observed=observer(f.clock,writes).capture(f.ref);assertEquals(observed,service.capture(f.ref));assertEquals(1,observations());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void closedP2IdentityBoundedObservationAndPrecisionAreStrict(){
        f.complete(0);service.capture(f.ref);var proof=committed();var key=(ObjectNode)CanonicalJson.parse(proof.result().observationKeyJson());key.put("stage","news_dedup");assertThrows(RuntimeException.class,()->f.readonly(()->store.read(JdbcFrozenInputs.canonical(key))));assertThrows(RuntimeException.class,()->service.availableAt(proof,f.clock.instant()));assertThrows(RuntimeException.class,()->available(proof,f.clock.instant().plusNanos(1)));
        String huge="{\"fictional\":\""+"F".repeat(2049)+"\"}";f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_value_stage_availability'",huge,JdbcGenerationLineage.hash(huge));assertThrows(RuntimeException.class,()->available(proof,f.clock.instant()));assertEquals(1,f.f.count("target_type='source_stage_availability'"));
    }
}
