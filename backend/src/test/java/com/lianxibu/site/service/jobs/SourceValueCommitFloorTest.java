package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class SourceValueCommitFloorTest {
    SourceValueStageProofTest f;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueCommitFloorTest.this.fixture();}};f.setup();}
    @Test void nativeExactAppliedTimeIsAnIndependentLowerBoundAndOriginalJsonStaysIdentical(){
        f.complete(0);var applied=f.readonly(()->new SourceValueResultProofService(new JdbcSourceDedupResults(f.f.source),f.plans,f.universes,new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.f.source))).readApplied(f.plan.chunks().getFirst().rootId()));assertEquals(f.clock.instant(),applied.appliedAt());
        var complete=f.readonly(()->f.service.readCommitted(f.ref));assertEquals(f.clock.instant(),complete.minimumObservationTime());assertEquals(f.read().json(),complete.result().json());assertEquals(applied.proof().members(),complete.result().members());
    }
    @Test void wholeLowerBoundUsesTheLatestOriginalPartAndNeverPartialCoverage(){
        f.install(501);assertNull(f.readonly(()->f.service.readCommitted(f.ref)));f.complete(0);assertNull(f.readonly(()->f.service.readCommitted(f.ref)));f.complete(1);var later=f.clock.instant().plusSeconds(25).plusNanos(123000);f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",java.sql.Timestamp.from(later),f.plan.chunks().getLast().rootId());assertEquals(later,f.readonly(()->f.service.readCommitted(f.ref)).minimumObservationTime());
    }
    @Test void zeroPlanHasOnlyItsOriginalCutoffLowerBound(){
        f.install(0);var committed=f.readonly(()->f.service.readCommitted(f.ref));assertEquals(f.plan.scope().cutoff(),committed.minimumObservationTime());assertTrue(committed.result().members().isEmpty());assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void metadataRequiresTheSameReadOnlyProofAndDamagedApplicationTimeRefuses(){
        assertThrows(RuntimeException.class,()->f.service.readCommitted(f.ref));assertThrows(RuntimeException.class,()->f.tx(()->f.service.readCommitted(f.ref)));f.complete(0);f.f.jdbc.update("UPDATE jobs SET applied_at=NULL WHERE id=?",f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,()->f.readonly(()->f.service.readCommitted(f.ref)));
    }
}
