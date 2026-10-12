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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real P1/handoff/qualification/plan/part SQL, with an explicitly doubled day provider. */
class SourceDayPlanCoordinatorTest {
    SourceValueIntentHandoffTest f;SourceDayNewsValuePermitService days;SourceDayNewsValuePermitService.Captured day;FrozenSourceDayDedupDependency reference;
    NewsValueSourcePlanPreparationService value;SourceStagePreparationService parts;SourcePlanPreparationCoordinator service;String intent;int settings;boolean allowed=true;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup()throws Exception{
        f=new SourceValueIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return SourceDayPlanCoordinatorTest.this.database();}};f.setup();intent=f.service.handoff(f.ref).p2Owner();var v=f.f;var own=FrozenSourceDedupDependency.read(v.profile,v.clock.instant().truncatedTo(ChronoUnit.MICROS));var k=own.toJson().get("stage_key");var businessDay=LocalDate.parse(k.get("business_date").asText());
        var member=new SourceDedupBarrier.Member(k.get("source_id").asText(),SourceDedupBarrier.Inclusion.ENABLED,3L,"a".repeat(64),k.get("run_id").asText(),1L);var cohort=SourceDedupBarrier.freeze("news_tech",businessDay,businessDay.atTime(16,10).toInstant(ZoneOffset.UTC),List.of(member));reference=FrozenSourceDayDedupDependency.create("fictional-directory","b".repeat(64),SourceDedupBarrier.close(cohort,SourceDedupBarrier.deadline(businessDay),List.of()),own,v.clock.instant().truncatedTo(ChronoUnit.MICROS));
        days=mock(SourceDayNewsValuePermitService.class);day=mock(SourceDayNewsValuePermitService.Captured.class);when(day.dependency()).thenReturn(reference);when(days.capture(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return day;});
        when(days.verify(eq(day),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return reference;});when(days.verifyObserved(eq(day),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return reference;});
        value=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(v.f.source),new JdbcFrozenSourceUniverses(v.f.source),v.p1.universes,v.plans,v.p1.collections,new NewsValueEligibilityService(new JdbcNewsDedupEligibility(v.f.source),new JdbcNewsValueEntities(v.f.source),v.p1.members,v.clock),v.dependencies,()->{settings++;return new FrozenGenerationSettings(1,List.of(),List.of());},v.clock,()->allowed,"2",days);
        parts=new SourceStagePreparationService(v.f.source,v.plans,v.p1.universes,v.p1.collections::read,v.clock,"fictional-owner",null,v.dependencies);service=provider(v.f.writes);
    }
    SourcePlanPreparationCoordinator provider(SharedWriteExecutor writes){var v=f.f;return new SourcePlanPreparationCoordinator(v.p1.preparation,value,parts,f.service,f.proofs,new JdbcFrozenSourcePlans(v.f.source),writes,new DataSourceTransactionManager(v.f.source),v.clock,()->allowed,null,days);}
    SourcePlanPreparationCoordinator.Prepared prepare(){return service.prepareValue(intent,"fictional-run",f.ref,0);}
    int roots(){return f.f.f.count("record_kind='logical_task' AND task_type='news_value'");}
    @Test void completeDayQualificationReachesOneExactOriginalPartAndReplaysWithRoProof(){
        int calls=f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var prepared=prepare();assertNotNull(prepared.root());assertEquals(reference.toJson(),CanonicalJson.parse(prepared.root().snapshotJson()).get(FrozenSourceDayDedupDependency.FIELD));assertEquals(1,roots());assertEquals(calls,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        int count=f.f.f.count("1=1"),reads=settings;allowed=false;f.f.f.jdbc.execute("DROP TABLE source_run_items");f.f.f.jdbc.execute("DROP TABLE analyzed_entities");assertEquals(prepared,prepare());assertEquals(count,f.f.f.count("1=1"));assertEquals(reads,settings);verify(days,atLeastOnce()).verifyObserved(eq(day),any());
        when(days.verifyObserved(eq(day),any())).thenThrow(new IllegalStateException("fictional damaged original day"));assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.f.f.count("1=1"));
    }
    @Test void wholeDayStillPendingCreatesNoP2PlanOrRoot(){
        when(days.capture(any(),any())).thenReturn(null);int count=f.f.f.count("1=1");assertNull(prepare());assertEquals(count,f.f.f.count("1=1"));assertEquals(0,roots());assertEquals(0,settings);
    }
    @Test void dayAuthorityLostBeforePartLeavesCompleteOriginalPlanWithoutPartialRoot(){
        int[] checks={0};when(days.verify(eq(day),any())).thenAnswer(call->{if(++checks[0]==3)throw new IllegalStateException("fictional day lost before root");return reference;});assertThrows(RuntimeException.class,this::prepare);assertEquals(0,roots());assertNotNull(f.f.readonly(()->new JdbcFrozenSourcePlans(f.f.f.source).immutableReceipt(intent)));int reads=settings;
        assertNotNull(prepare().root());assertEquals(reads,settings);assertEquals(1,roots());
    }
    @Test void emptyQualificationKeepsDayContextAndNoSyntheticRoot(){
        f.f.f.jdbc.update("UPDATE source_run_items SET effective_valid=0");var prepared=prepare();assertNull(prepared.root());assertTrue(prepared.plan().chunks().isEmpty());assertEquals(reference.toJson(),CanonicalJson.parse(prepared.plan().scope().stageContextJson()).get(FrozenSourceDayDedupDependency.FIELD));assertEquals(0,roots());assertEquals(prepared,prepare());
    }
    @Test void actualCommitAckLossUsesVerifiedOriginalPlanAndRoot()throws Exception{
        var v=f.f;var fault=new DataSourceTransactionManager(v.f.source){int commits;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&++commits==2)throw new org.springframework.transaction.TransactionSystemException("fictional day plan ACK lost");}};service=provider(new SharedWriteExecutor(new JdbcSharedWriteEntry(v.f.source),fault,()->25,n->{}));var original=prepare();assertNotNull(original.root());service=provider(v.f.writes);assertEquals(original,prepare());assertEquals(1,roots());
    }
}
