package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual partial P1/P2 SQL and fictional outcomes; the day provider is an explicit double. */
class SourceValueDayResolutionTest extends SourceValuePartialResolutionTest {
    @Override SourcePartialStagePreparationTest planFixture(){return new SourcePartialStagePreparationTest(){
        @Override GenerationJdbcStoreTest database(){return SourceValueDayResolutionTest.this.database();}
        @Override NewsValuePartialPlanPreparationTest planFixture(){return new DayPartialPlanFixture(){@Override GenerationJdbcStoreTest database(){return SourceValueDayResolutionTest.this.database();}};}
    };}
    static class DayPartialPlanFixture extends NewsValuePartialPlanPreparationTest {
        SourceDayNewsValuePermitService days;SourceDayNewsValuePermitService.Captured day;
        @Override NewsValueSourcePlanPreparationService provider(SourceDedupDependencyService dependencies){
            var reference=new NewsValueDayPlanBuilderTest().day(dependency,clock.instant(),false);days=mock(SourceDayNewsValuePermitService.class);day=mock(SourceDayNewsValuePermitService.Captured.class);when(day.dependency()).thenReturn(reference);
            when(days.verify(eq(day),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return reference;});
            when(days.verifyObserved(eq(day),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return reference;});
            return new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(db.source),new JdbcFrozenSourceUniverses(db.source),f.f.f.p1.universes,plans,f.f.f.p1.collections,new NewsValueEligibilityService(new JdbcNewsDedupEligibility(db.source),new JdbcNewsValueEntities(db.source),f.f.f.p1.members,clock),dependencies,()->{settingsReads++;return new FrozenGenerationSettings(1,List.of(),blocked);},clock,()->allowed,"2",days);
        }
        @Override NewsValueSourcePlanPreparationService.Captured capture(){return f.f.f.p1.tx(()->service.captureDayPartial(intent,"fictional-run",permit,day));}
        @Override SourcePartitionPlanner.Plan commit(NewsValueSourcePlanPreparationService.PreparedPlan prepared){return db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-day-partial-p2-commit",intent,null),k->service.committedDay(intent,"fictional-run",day,permit),a->service.commit(prepared));}
    }
    @Test void AllAppliedValueRetainsBothOriginalDayAndOriginalDedupFailures()throws Exception{
        setup(true);for(int n=0;n<f.plan.chunks().size();n++){prepare(n);outcomes.complete(n);}var snapshot=read();assertTrue(snapshot.settled());assertFalse(snapshot.hasFailures());var result=SourceValueStageResult.createResolved(snapshot);var body=CanonicalJson.parse(result.json());assertEquals("source-value-stage-result-v2",body.get("schema_version").asText());assertEquals(200,body.get("applied_member_count").asInt());assertEquals(1,body.get("missing").size());assertEquals("dedup_failed",body.get("missing").get(0).get("reason").asText());assertEquals(CanonicalJson.parse(f.plan.scope().stageContextJson()),body.get("stage_context"));assertFalse(body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD).get("original_batch_eligible").asBoolean());assertThrows(RuntimeException.class,()->SourceValueStageResult.create(f.plan,snapshot.universe(),ref,snapshot.fragments().stream().map(n->n.value().proof()).toList()));
    }
    @Test void ZeroValuePlanAndItsProgressCannotBypassActualPartialDedupReceipt()throws Exception{
        setup(false);var body=CanonicalJson.parse(SourceValueStageResult.createResolved(read()).json());assertEquals("source-value-stage-result-v2",body.get("schema_version").asText());assertEquals(1,body.get("missing").size());assertTrue(body.get("fragments").isEmpty());assertTrue(original.read(()->f.service.progress(ref.ownerId())).complete());db.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcSourceDedupPartialReceipts.receiptId(authority.f.ref.ownerId()));assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->original.read(()->f.service.progress(ref.ownerId())));
    }
}
