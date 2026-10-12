package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFinalAvailabilityTest {
    SourceValueFinalizationTest x;SourceValueStageProofTest f;JdbcSourceValueFinalAvailability store;SourceValueFinalAvailabilityService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){x=new SourceValueFinalizationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalAvailabilityTest.this.fixture();}};x.setup();install();}
    void install(){f=x.f;store=new JdbcSourceValueFinalAvailability(f.f.source);service=observer(f.clock,f.f.writes);}
    SourceValueFinalAvailabilityService observer(Clock clock,SharedWriteExecutor writes){return new SourceValueFinalAvailabilityService(x.service,store,writes,new DataSourceTransactionManager(f.f.source),clock);}
    int observations(){return f.f.count("target_type='source_value_final_availability'");}
    Instant available(SourceValueFinalizationService.Committed proof,Instant cutoff){return f.readonly(()->service.availableAt(proof,cutoff));}
    @Test void structuralCompleteIsNotFinalAndOnlyActualSealGetsAFirstCutoffObservation(){
        assertNull(service.capture(f.ref));f.complete(0);assertNull(service.capture(f.ref));assertEquals(0,observations());x.service.finish(f.ref);var proof=x.read();assertNull(available(proof,f.clock.instant().plusSeconds(100)));var observed=observer(Clock.fixed(f.clock.instant().plusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref);assertEquals(f.clock.instant().plusNanos(1000),observed);assertNull(available(proof,f.clock.instant()));assertEquals(observed,available(proof,observed));assertEquals(observed,observer(Clock.fixed(observed.plusSeconds(100),ZoneOffset.UTC),f.f.writes).capture(f.ref));assertEquals(1,observations());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void zeroPlanNeedsARealSealAndClockAfterReadonlyCannotPrecedeIt(){
        f.install(0);x.install();install();assertNull(service.capture(f.ref));x.service.finish(f.ref);var proof=x.read();assertNull(available(proof,proof.minimumObservationTime()));Clock guarded=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return f.clock.instant();}};assertThrows(RuntimeException.class,()->f.tx(()->observer(guarded,f.f.writes).capture(f.ref)));assertThrows(RuntimeException.class,()->observer(Clock.fixed(f.clock.instant().minusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref));var observed=observer(guarded,f.f.writes).capture(f.ref);assertEquals(observed,available(proof,observed));assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void changedRoleRehashedEarlierTimeAndStructuralNamespaceCannotGrantFinalAvailability(){
        f.complete(0);x.service.finish(f.ref);service.capture(f.ref);var proof=x.read();String id=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_value_final_availability'",String.class);String saved=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);f.f.jdbc.update("UPDATE jobs SET operator_id='source-value-stage-observer' WHERE id=?",id);assertThrows(RuntimeException.class,()->available(proof,f.clock.instant()));f.f.jdbc.update("UPDATE jobs SET operator_id='source-value-final-observer' WHERE id=?",id);var p=(ObjectNode)CanonicalJson.parse(saved);var early=f.clock.instant().minusSeconds(1);p.put("observed_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(early));String raw=JdbcFrozenInputs.canonical(p);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,acted_at=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),java.sql.Timestamp.from(early),id);assertThrows(RuntimeException.class,()->available(proof,f.clock.instant()));assertThrows(RuntimeException.class,()->service.capture(f.ref));var key=(ObjectNode)CanonicalJson.parse(proof.observationKeyJson());key.put("schema_version","source-value-stage-key-v1");assertThrows(RuntimeException.class,()->f.readonly(()->store.read(JdbcFrozenInputs.canonical(key))));assertThrows(RuntimeException.class,()->available(proof,f.clock.instant().plusNanos(1)));
    }
    @Test void failedAppendRollsBackAndActualAckLossRecoversTheSameFinalObservation(){
        f.complete(0);x.service.finish(f.ref);f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_final_observation_failure CHECK(target_type IS NULL OR target_type<>'source_value_final_availability')");assertThrows(RuntimeException.class,()->service.capture(f.ref));assertEquals(0,observations());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_final_observation_failure":"ALTER TABLE jobs DROP CHECK fictional_final_observation_failure");var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional final observer ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var observed=observer(f.clock,writes).capture(f.ref);assertEquals(observed,service.capture(f.ref));assertEquals(1,observations());assertEquals(1,x.seals());
    }
}
