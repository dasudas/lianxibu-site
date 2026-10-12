package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual original directory and original empty closure. Unused source ports are explicit cold fixtures. */
class NewsDayReportComponentTest {
    SourceDayCohortSealServiceTest h;SourceDayCohortSealService cohorts;SourceDayNewsDedupBarrierService barrier;NewsDayReportComponentService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){h=new SourceDayCohortSealServiceTest(){@Override GenerationJdbcStoreTest fixture(){return NewsDayReportComponentTest.this.fixture();}};h.setup();wire();}
    void wire(){cohorts=h.service(()->new SourceDayRoster.Evidence(h.now,0,List.of()),Clock.fixed(h.now,ZoneOffset.UTC));barrier=new SourceDayNewsDedupBarrierService(cohorts,mock(SourceDayNewsDedupProgressService.class),h.archives==null?null:new JdbcSourceDayDedupClosures(h.fixture.source),h.fixture.writes,h.manager,Clock.fixed(h.now,ZoneOffset.UTC));service=create(cohorts,barrier);}
    NewsDayReportComponentService create(SourceDayCohortSealService c,SourceDayNewsDedupBarrierService b){return new NewsDayReportComponentService(c,b,new JdbcSourceDayDedupClosures(h.fixture.source),mock(SourceDayRunClaimAdmissionService.class),mock(FrozenSourceStagePlanService.class),mock(SourceDedupStageProofService.class),mock(SourceDedupStageAvailabilityService.class),mock(SourceValueIntentReceiptService.class),mock(SourceDayNewsValuePermitService.class),mock(SourceValueReportCompletionService.class),mock(NewsSourceReportComponentService.class),h.manager);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(h.manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    @Test void ActualMissingDirectoryIsUnknownAndOriginalEmptyDirectoryIsSuccessfulEmpty(){assertNull(service.capture(h.day,h.now));cohorts.seal(h.day);barrier.close(h.day);var value=service.capture(h.day,h.now);assertNotNull(value);assertTrue(value.completeSuccessfulEmpty());assertTrue(value.candidates().isEmpty());assertTrue(value.missing().isEmpty());assertSame(value,rr(()->service.verifyObserved(value)));assertEquals(cohorts.find(h.day).plan().bundleHash(),value.directory().bundleHash());assertThrows(UnsupportedOperationException.class,()->value.referenceTargetsByEvidence().clear());}
    @Test void OriginalUnconfiguredAndUnassignedSourcesAreExplicitMissingAndDisabledIsExcluded(){
        var rows=List.of(new SourceDayRoster.RawSource("fictional-disabled","news_tech",0,0,"disabled","draft",null,false,false,null),new SourceDayRoster.RawSource("fictional-unconfigured","news_tech",0,0,"draft","enabled",null,false,false,null),new SourceDayRoster.RawSource("fictional-unassigned",null,0,0,"draft","disabled",null,false,false,null));
        cohorts=h.service(()->new SourceDayRoster.Evidence(h.now,rows.size(),rows),Clock.fixed(h.now,ZoneOffset.UTC));barrier=new SourceDayNewsDedupBarrierService(cohorts,mock(SourceDayNewsDedupProgressService.class),new JdbcSourceDayDedupClosures(h.fixture.source),h.fixture.writes,h.manager,Clock.fixed(h.now,ZoneOffset.UTC));service=create(cohorts,barrier);var sealed=cohorts.seal(h.day);assertEquals(3,sealed.plan().directory().evidence().sourceCount());assertEquals(1,sealed.plan().unassignedSourceIds().size());barrier.close(h.day);var value=service.capture(h.day,h.now);assertFalse(value.completeSuccessfulEmpty(),value.missing().toString());assertEquals(Set.of("unconfigured","category_unassigned"),value.missing().stream().map(ReportInputSnapshotService.MissingInput::reason).collect(java.util.stream.Collectors.toSet()));assertFalse(value.missing().stream().anyMatch(m->m.id().equals("fictional-disabled")));assertSame(value,rr(()->service.verifyObserved(value)));
    }
    @Test void OriginalArchiveMutationAndWrongIssuerOrTransactionCannotReuseCapture(){cohorts.seal(h.day);barrier.close(h.day);var value=service.capture(h.day,h.now);assertThrows(RuntimeException.class,()->service.verifyObserved(value));assertThrows(RuntimeException.class,()->new TransactionTemplate(h.manager).execute(s->service.verifyObserved(value)));var rc=new TransactionTemplate(h.manager);rc.setReadOnly(true);rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->rc.execute(s->service.verifyObserved(value)));assertThrows(RuntimeException.class,()->rr(()->create(cohorts,barrier).verifyObserved(value)));assertThrows(RuntimeException.class,()->rr(()->service.capture(h.day,h.now)));h.fixture.jdbc.update("UPDATE jobs SET operator_id='fictional-forged' WHERE target_type='source_day_dedup'");assertThrows(RuntimeException.class,()->rr(()->service.verifyObserved(value)));}
    @Test void MandatoryActualPortsAreColdAndMalformedCutoffCannotReadSql(){var c=mock(SourceDayCohortSealService.class);var b=mock(SourceDayNewsDedupBarrierService.class);var service=create(c,b);assertThrows(RuntimeException.class,()->service.capture(h.day,h.now.plusNanos(1)));verifyNoInteractions(c,b);assertThrows(NullPointerException.class,()->create(null,b));}
}
