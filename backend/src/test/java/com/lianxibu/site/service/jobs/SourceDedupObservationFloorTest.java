package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupObservationFloorTest {
    SourceDedupStageAvailabilityServiceTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceDedupStageAvailabilityServiceTest(){@Override SourceDedupStageProofServiceTest fixture(){return new SourceDedupStageProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceDedupObservationFloorTest.this.database();}};}};f.setup();}
    Instant late(){return f.cutoff().plusSeconds(120);}
    void completeLate(){f.f.complete(0);f.f.complete(1);f.f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(late()),f.f.f.plan.chunks().getLast().rootId());}
    SourceDedupStageAvailabilityService atClock(Instant time){return f.observer(Clock.fixed(time,ZoneOffset.UTC),f.f.f.f.writes);}
    @Test void aLateOriginalFragmentCannotBeObservedByAnEarlierClock(){
        completeLate();assertThrows(RuntimeException.class,()->f.service.capture(f.f.ref));assertEquals(0,f.observations());
        assertThrows(RuntimeException.class,()->atClock(late().minusNanos(1)).capture(f.f.ref));assertEquals(0,f.observations());
        Instant observed=atClock(late()).capture(f.f.ref);assertEquals(late(),observed);var proof=f.f.read();assertNull(f.at(proof,late().minusNanos(1000)));assertEquals(late(),f.at(proof,late()));
        assertEquals(late(),atClock(late().plusSeconds(100)).capture(f.f.ref));assertEquals(1,f.observations());
    }
    @Test void rehashedEarlyFirstObservationCannotBeUsedOrRewrittenAsNewEvidence(){
        completeLate();atClock(late()).capture(f.f.ref);var proof=f.f.read();
        String id=f.f.f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_stage_availability'",String.class);
        String saved=f.f.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);var body=(ObjectNode)CanonicalJson.parse(saved);
        body.put("observed_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(late().minusSeconds(1)));String raw=JdbcFrozenInputs.canonical(body);
        f.f.f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,acted_at=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),Timestamp.from(late().minusSeconds(1)),id);
        assertThrows(RuntimeException.class,()->f.at(proof,late().plusSeconds(100)));assertThrows(RuntimeException.class,()->atClock(late().plusSeconds(100)).capture(f.f.ref));assertEquals(1,f.observations());assertEquals(raw,JdbcFrozenInputs.canonical(CanonicalJson.parse(f.f.f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id))));
    }
    @Test void emptyOriginalPlanStillRequiresAClockAtOrAfterItsInputCutoff(){
        f.f.install(0);f.installObserver();Instant minimum=f.f.f.plan.scope().cutoff();
        assertThrows(RuntimeException.class,()->atClock(minimum.minusNanos(1)).capture(f.f.ref));assertEquals(0,f.observations());
        assertEquals(minimum,atClock(minimum).capture(f.f.ref));assertEquals(minimum,f.at(f.f.read(),minimum));assertEquals(0,f.f.f.f.count("record_kind='logical_task'"));
    }
}
