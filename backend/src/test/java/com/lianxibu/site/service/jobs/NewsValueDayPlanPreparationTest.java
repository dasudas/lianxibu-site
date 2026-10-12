package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual preparation SQL. This layer explicitly doubles the separately qualified day provider. */
class NewsValueDayPlanPreparationTest {
    NewsValueSourcePlanPreparationTest f;SourceDayNewsValuePermitService days;SourceDayNewsValuePermitService.Captured day;
    FrozenSourceDayDedupDependency reference;NewsValueSourcePlanPreparationService service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    Instant cutoff(){return f.clock.instant().truncatedTo(ChronoUnit.MICROS);}
    @BeforeEach void setup()throws Exception {
        f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueDayPlanPreparationTest.this.database();}};f.setup();
        var own=FrozenSourceDedupDependency.read(f.profile,cutoff());var key=own.toJson().get("stage_key");var businessDay=LocalDate.parse(key.get("business_date").asText());
        var member=new SourceDedupBarrier.Member(key.get("source_id").asText(),SourceDedupBarrier.Inclusion.ENABLED,3L,"a".repeat(64),key.get("run_id").asText(),1L);
        var cohort=SourceDedupBarrier.freeze("news_tech",businessDay,businessDay.atTime(16,10).toInstant(ZoneOffset.UTC),List.of(member));var closure=SourceDedupBarrier.close(cohort,SourceDedupBarrier.deadline(businessDay),List.of());
        reference=FrozenSourceDayDedupDependency.create("fictional-directory","b".repeat(64),closure,own,cutoff());days=mock(SourceDayNewsValuePermitService.class);day=mock(SourceDayNewsValuePermitService.Captured.class);when(day.dependency()).thenReturn(reference);
        when(days.verify(eq(day),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return reference;});
        service=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.f.source),new JdbcFrozenSourceUniverses(f.f.source),f.p1.universes,f.plans,f.p1.collections,new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.f.source),new JdbcNewsValueEntities(f.f.source),f.p1.members,f.clock),f.dependencies,()->{f.settingsReads++;return new FrozenGenerationSettings(1,List.of(),List.of());},f.clock,()->f.allowed,"2",days);
    }
    NewsValueSourcePlanPreparationService.PreparedPlan prepared(){var captured=f.p1.tx(()->service.captureDay(f.intent,"fictional-run",f.profile,day));assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return service.build(captured);}
    int receipts(){return f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=?",Integer.class,JdbcFrozenSourcePlans.receiptId(f.intent));}
    @Test void captureAndCommitRequireDayAuthorityAndPureBuildKeepsLateBatchExcluded(){
        var prepared=prepared();assertEquals(0,receipts());verify(days).verify(eq(day),any());var plan=f.p1.tx(()->service.commit(prepared));verify(days,times(2)).verify(eq(day),any());assertEquals(1,receipts());
        assertEquals(reference.toJson(),CanonicalJson.parse(plan.scope().stageContextJson()).get(FrozenSourceDayDedupDependency.FIELD));var snapshot=CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());assertTrue(snapshot.get(FrozenApplicationContext.FIELD).get("eligible_high_value_item_ids").isEmpty());assertEquals("2",snapshot.get(FrozenApplicationContext.FIELD).get("value_result_version").asText());assertEquals(0,f.f.count("record_kind='logical_task' AND task_type='news_value'"));
    }
    @Test void changedDayAuthorityAfterPureBuildRejectsPlanButPreservesHonestUniversePrefix(){
        var prepared=prepared();when(days.verify(eq(day),any())).thenThrow(new IllegalStateException("fictional changed original day"));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.commit(prepared)));assertEquals(0,receipts());assertEquals(0,f.f.count("record_kind='logical_task' AND task_type='news_value'"));
    }
    @Test void installedDayProviderCannotUseLegacyWriterEntriesOrNullCapability(){
        int before=f.f.count("1=1");assertThrows(RuntimeException.class,()->f.p1.tx(()->service.capture(f.intent,"fictional-run",f.profile)));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.prepare(f.intent,"fictional-run",f.profile)));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.captureDay(f.intent,"fictional-run",f.profile,null)));assertEquals(before,f.f.count("1=1"));verifyNoInteractions(days);
    }
    @Test void originalReplayStillRequiresDayAuthorityAndRetainsOriginalWithoutCurrentQualification(){
        var prepared=prepared();var plan=f.p1.tx(()->service.commit(prepared));int count=f.f.count("1=1"),settings=f.settingsReads;f.allowed=false;f.f.jdbc.execute("DROP TABLE source_run_items");f.f.jdbc.execute("DROP TABLE analyzed_entities");
        assertEquals(plan,f.readonly(()->service.committed(f.intent,"fictional-run")));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.committed(f.intent,"fictional-run")));
        var captured=f.p1.tx(()->service.captureCommittedDay(f.intent,"fictional-run",day,null));var replay=service.build(captured);assertEquals(plan,f.p1.tx(()->service.commit(replay)));assertEquals(settings,f.settingsReads);assertEquals(count,f.f.count("1=1"));
        when(days.verify(eq(day),any())).thenThrow(new IllegalStateException("fictional deleted original day"));assertThrows(RuntimeException.class,()->f.p1.tx(()->service.committedDay(f.intent,"fictional-run",day,null)));assertEquals(count,f.f.count("1=1"));
    }
    @Test void zeroCurrentQualificationStillStoresOriginalDayAndNoSyntheticRoot(){
        f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0");var prepared=prepared();var plan=f.p1.tx(()->service.commit(prepared));assertTrue(plan.chunks().isEmpty());assertEquals(reference.toJson(),CanonicalJson.parse(plan.scope().stageContextJson()).get(FrozenSourceDayDedupDependency.FIELD));assertEquals(1,receipts());assertEquals(0,f.f.count("record_kind='logical_task' AND task_type='news_value'"));
    }
}
