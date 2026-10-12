package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDedupPartialConsumerTest {
    SourceDedupPartialDependencyPermitTest f;SourceDedupDependencyService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourceDedupDependencyServiceTest legacy(){return new SourceDedupDependencyServiceTest();}
    void setup(int count)throws Exception {f=new SourceDedupPartialDependencyPermitTest(){@Override GenerationJdbcStoreTest database(){return SourceDedupPartialConsumerTest.this.database();}};f.setup(count,0);service=new SourceDedupDependencyService(f.observations,f.proven,f.service);}
    SourceDedupDependencyService.Proven writer(SourceDedupPartialDependencyPermitService.Captured captured){return f.f.f.db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-partial-consumer","fictional-consumer-resource",null),key->null,c->service.readPartial(captured,captured.dependency().observedAt()));}
    @Test void explicitReadonlyAndOriginalShortWriterGrantOnlyTheExactSparseProofWithoutWrites()throws Exception {
        setup(201);f.f.applied(0);f.f.terminal(1,"failed");f.f.applied(2);var original=f.original();var captured=f.service.capture(original.toJson(),original.observedAt());int count=f.f.f.db.count("1=1"),calls=f.f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var readonly=f.f.f.read(()->service.readPartial(original.toJson(),original.observedAt()));var actual=writer(captured);
        assertEquals(original.json(),readonly.dependency().json());assertEquals(original.json(),actual.dependency().json());assertEquals("fictional-run",actual.runId());assertEquals("fictional-source",actual.sourceId());assertEquals(LocalDate.of(2026,1,1),actual.businessDate());assertEquals(1,actual.generation());assertEquals(2,actual.dependency().dependencies().size());assertEquals(100,actual.dependency().toJson().get("stage_result").get("unapplied_member_count").asInt());assertEquals(count,f.f.f.db.count("1=1"));assertEquals(calls,f.f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void defaultsAndOldReadRemainClosedEvenWithExplicitPartialProviders()throws Exception {
        setup(1);f.f.terminal(0,"failed");var original=f.original();var captured=f.service.capture(original.toJson(),original.observedAt());var closed=new SourceDedupDependencyService(f.observations);
        assertThrows(RuntimeException.class,()->f.f.f.read(()->closed.read(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.f.read(()->closed.readPartial(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.f.p1.tx(()->closed.readPartial(captured,original.observedAt())));
        assertThrows(RuntimeException.class,()->f.f.f.read(()->service.read(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.f.p1.tx(()->service.read(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.f.p1.tx(()->service.readPartial(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->f.f.f.read(()->service.readPartial(captured,original.observedAt())));assertThrows(RuntimeException.class,()->service.readPartial(original.toJson(),original.observedAt()));assertThrows(RuntimeException.class,()->service.readPartial(captured,original.observedAt()));
    }
    @Test void allFailedKeepsMissingAndTheConsumersOwnObservationMustAlsoMatch()throws Exception {
        setup(1);f.f.terminal(0,"failed");var original=f.original();var captured=f.service.capture(original.toJson(),original.observedAt());var actual=writer(captured);assertTrue(actual.dependency().dependencies().isEmpty());assertEquals(1,actual.dependency().toJson().get("stage_result").get("unapplied_member_count").asInt());var missing=mock(JdbcSourceStageAvailability.class);service=new SourceDedupDependencyService(missing,f.proven,f.service);assertThrows(RuntimeException.class,()->f.f.f.read(()->service.readPartial(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->writer(captured));verify(missing,times(2)).read(original.observationKeyJson());assertEquals(1,f.a.observations());
    }
    @Test void changedOriginalNativeOrMissingArchiveAfterCaptureCannotFallbackToV1()throws Exception {
        for(String damage:List.of("native","archive")){setup(1);f.f.terminal(0,"failed");var original=f.original();var captured=f.service.capture(original.toJson(),original.observedAt());if(damage.equals("native"))f.f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.plan.chunks().getFirst().rootId());else f.f.f.db.jdbc.update("DELETE FROM jobs WHERE record_kind='snapshot_part' AND purpose='result' AND part_no=1");int count=f.f.f.db.count("1=1");assertThrows(RuntimeException.class,()->f.f.f.read(()->service.readPartial(original.toJson(),original.observedAt())));assertThrows(RuntimeException.class,()->writer(captured));assertEquals(count,f.f.f.db.count("1=1"));}
    }
    @Test void originalCompleteV1UsesTheSameLedgerAndNeverCallsAnyPartialPort() {
        var old=legacy();old.setup();var original=old.complete();var partial=mock(SourceDedupPartialDependencyService.class);var permit=mock(SourceDedupPartialDependencyPermitService.class);var upgraded=new SourceDedupDependencyService(old.f.store,partial,permit);assertEquals(original.json(),old.f.f.f.readonly(()->upgraded.read(original.toJson(),original.observedAt())).dependency().json());assertEquals(original.json(),old.f.f.f.tx(()->upgraded.read(original.toJson(),original.observedAt())).dependency().json());verifyNoInteractions(partial,permit);
    }
    @Test void constructorsAreZeroSqlAndNewCapacityExceptionsRemainCapacityFailures()throws Exception {
        var ledger=mock(JdbcSourceStageAvailability.class);var partial=mock(SourceDedupPartialDependencyService.class);var permit=mock(SourceDedupPartialDependencyPermitService.class);new SourceDedupDependencyService(ledger,partial,permit);verifyNoInteractions(ledger,partial,permit);setup(1);f.f.terminal(0,"failed");var original=f.original();var captured=f.service.capture(original.toJson(),original.observedAt());var capped=spy(f.proven);doThrow(new GenerationFlowService.CapacityGuarded()).when(capped).read(any(),any());service=new SourceDedupDependencyService(f.observations,capped,f.service);assertThrows(GenerationFlowService.CapacityGuarded.class,()->f.f.f.read(()->service.readPartial(original.toJson(),original.observedAt())));var writerCap=spy(f.service);doThrow(new GenerationFlowService.CapacityGuarded()).when(writerCap).verify(any(),any());service=new SourceDedupDependencyService(f.observations,f.proven,writerCap);assertThrows(GenerationFlowService.CapacityGuarded.class,()->writer(captured));assertEquals(1,f.a.observations());
    }
}
