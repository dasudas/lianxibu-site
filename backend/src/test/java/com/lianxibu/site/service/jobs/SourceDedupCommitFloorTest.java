package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.*;

class SourceDedupCommitFloorTest {
    SourceDedupStageProofServiceTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceDedupStageProofServiceTest(){@Override GenerationJdbcStoreTest fixture(){return SourceDedupCommitFloorTest.this.database();}};f.setup();}
    SourceDedupStageProofService.Committed read(){return f.f.readonly(()->f.service.readCommitted(f.ref));}
    @Test void originalLatestAppliedTimeIsTheWholeLowerBoundAndOldJsonRemainsExact(){
        assertNull(read());f.complete(0);assertNull(read());f.complete(1);var original=f.read();
        Instant late=f.f.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(120);
        var root=f.f.plan.chunks().getLast();f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(late),root.rootId());
        var committed=read();assertEquals(late,committed.minimumObservationTime());assertEquals(original.json(),committed.proof().json());assertEquals(original.hash(),committed.proof().hash());
        var applied=f.f.readonly(()->f.f.service.readApplied(root.rootId()));assertEquals(late,applied.appliedAt());assertEquals(root.rootId(),applied.proof().rootId());
        assertThrows(RuntimeException.class,()->f.service.readCommitted(f.ref));assertThrows(RuntimeException.class,()->f.f.tx(()->f.service.readCommitted(f.ref)));
    }
    @Test void anAppliedFragmentBeforeItsOriginalInputCutoffCannotGrantAProof(){
        f.complete(0);f.complete(1);var root=f.f.plan.chunks().getFirst();
        f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(f.f.plan.scope().cutoff().minusNanos(1000)),root.rootId());
        assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,f::read);
        f.f.f.jdbc.update("UPDATE jobs SET applied_at=? WHERE id=?",Timestamp.from(f.f.plan.scope().cutoff()),root.rootId());assertNotNull(read());
    }
    @Test void originalEmptyPlanHasOnlyItsInputCutoffLowerBoundAndNoAiCalls(){
        f.install(0);var original=f.read();var committed=read();assertEquals(f.f.plan.scope().cutoff(),committed.minimumObservationTime());assertEquals(original.json(),committed.proof().json());assertEquals(0,f.f.f.count("record_kind='logical_task'"));assertEquals(0,f.f.f.count("record_kind='ai_attempt'"));
    }
}
