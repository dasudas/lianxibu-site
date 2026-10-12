package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Native archived fragment fixture; does not claim collector/business-handler end-to-end coverage. */
class SourceValueReportCompletionTest {
    SourceValueFinalAvailabilityTest original;SourceValueStageProofTest f;SourceValueReportCompletionService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){original=new SourceValueFinalAvailabilityTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueReportCompletionTest.this.fixture();}};original.setup();install();}
    void install(){f=original.f;service=new SourceValueReportCompletionService(f.plans,f.service,original.x.service,original.service,new DataSourceTransactionManager(f.f.source));}
    void complete(){for(int i=0;i<f.plan.chunks().size();i++)f.complete(i);original.x.service.finish(f.ref);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    SourceValueReportCompletionService.Completed find(Instant cutoff){return service.find(f.ref,cutoff);}
    @Test void OriginalCompleteBodyAllFragmentsAndFirstObservationAreRequired(){
        assertNull(find(f.clock.instant()));f.complete(0);assertNull(find(f.clock.instant()));original.x.service.finish(f.ref);assertNull(find(f.clock.instant().plusSeconds(60)));
        var instant=original.observer(Clock.fixed(f.clock.instant().plusNanos(1),ZoneOffset.UTC),f.f.writes).capture(f.ref);
        assertNull(find(instant.minusNanos(1000)));var done=find(instant);assertEquals(instant,done.availableAt());assertEquals(f.plan.logicalKey(),done.plan().logicalKey());assertEquals(f.plan.chunks().size(),done.stage().fragments().size());assertEquals(original.x.read().result().json(),done.finalResult().result().json());assertEquals(done.finalResult().result().hash(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(done.finalResult().result().json())));
        int rows=f.f.count("1=1");assertEquals(done.finalResult().result().json(),rr(()->service.read(f.ref,instant)).finalResult().result().json());assertEquals(rows,f.f.count("1=1"));assertEquals(1,original.observations());
    }
    @Test void FullMultiFragmentResultRetainsEveryOriginalMember(){
        f.install(501);original.x.install();original.install();install();assertEquals(2,f.plan.chunks().size());f.complete(0);assertNull(find(f.clock.instant()));f.complete(1);original.x.service.finish(f.ref);original.service.capture(f.ref);var done=find(f.clock.instant());assertEquals(2,done.stage().fragments().size());assertEquals(f.plan.decisions().size(),done.stage().decisions().size());assertEquals(501,done.stage().decisions().size());assertEquals(f.plan.decisions().stream().filter(d->d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE).count(),done.stage().members().size());assertThrows(UnsupportedOperationException.class,()->done.stage().fragments().clear());
    }
    @Test void OriginalZeroPlanNeedsItsOwnNativeFinalReceiptAndObservation(){
        f.install(0);original.x.install();original.install();install();assertNull(find(f.clock.instant()));complete();assertNull(find(f.clock.instant()));original.service.capture(f.ref);var done=find(f.clock.instant());assertEquals("no_input",original.x.intentState());assertTrue(done.stage().members().isEmpty());assertTrue(done.stage().fragments().isEmpty());assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void DeletedCurrentProjectionsAndLaterGenerationCannotChangeOriginalCompletion(){
        complete();original.service.capture(f.ref);var done=find(f.clock.instant());f.f.jdbc.update("DELETE FROM source_runs");f.f.jdbc.update("DELETE FROM sources");f.tx(()->new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",f.plan.logicalKey()+":2"),f.plan.logicalKey(),2,"news_value",f.plan.scope().businessDate()),f.clock.instant()));install();assertEquals(done.finalResult().result().json(),find(f.clock.instant()).finalResult().result().json());assertEquals(done.stage().json(),find(f.clock.instant()).stage().json());assertEquals(1,original.observations());
    }
    @Test void CorruptOriginalObservationOrArchiveCannotGrantCompletion(){
        complete();original.service.capture(f.ref);assertNotNull(find(f.clock.instant()));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_value_final_availability'",new byte[32]);assertThrows(RuntimeException.class,()->find(f.clock.instant()));
    }
    @Test void OnlyOneReadonlyRrSnapshotMayProduceTheBorrowedProof(){
        assertThrows(RuntimeException.class,()->service.read(f.ref,f.clock.instant()));assertThrows(RuntimeException.class,()->f.readonly(()->service.read(f.ref,f.clock.instant())));assertThrows(RuntimeException.class,()->f.tx(()->service.read(f.ref,f.clock.instant())));assertThrows(RuntimeException.class,()->rr(()->find(f.clock.instant())));assertThrows(RuntimeException.class,()->find(f.clock.instant().plusNanos(1)));
    }
    @Test void MandatoryPortsConstructWithoutIoOrClock(){
        var plans=mock(FrozenSourceStagePlanService.class);var stages=mock(SourceValueStageProofService.class);var finals=mock(SourceValueFinalizationService.class);var available=mock(SourceValueFinalAvailabilityService.class);var tx=mock(PlatformTransactionManager.class);new SourceValueReportCompletionService(plans,stages,finals,available,tx);verifyNoInteractions(plans,stages,finals,available,tx);assertThrows(NullPointerException.class,()->new SourceValueReportCompletionService(plans,stages,null,available,tx));
    }
}
