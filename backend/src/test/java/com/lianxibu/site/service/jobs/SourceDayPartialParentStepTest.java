package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual parent SQL composition; fictional outcomes and an explicitly doubled day provider. */
class SourceDayPartialParentStepTest extends SourcePartialParentStepTest {
    @Override void status(SourceAnalysisParentStepService.Status expected,String id){super.status(expected,id);if(expected==SourceAnalysisParentStepService.Status.PREPARED&&id.equals(q.intent)){var plan=read(()->q.plans.readImmutable(ref()));var context=CanonicalJson.parse(plan.scope().stageContextJson());assertEquals("source-value-stage-context-v3",context.get("schema_version").asText());assertTrue(context.has(FrozenSourceDayDedupDependency.FIELD));}}
    @Override SourcePartialPlanCoordinatorTest coordinatorFixture(){return new SourcePartialPlanCoordinatorTest(){
        @Override GenerationJdbcStoreTest database(){return SourceDayPartialParentStepTest.this.database();}
        @Override SourcePlanPreparationCoordinator provider(SharedWriteExecutor writes,SourceDedupPartialDependencyPermitService permit){
            var deps=new SourceDedupDependencyService(q.a.observations,q.a.proven,permit);var days=mock(SourceDayNewsValuePermitService.class);
            when(days.capture(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var own=FrozenSourceDedupDependency.readPartial(call.getArgument(0),call.getArgument(1));var reference=new NewsValueDayPlanBuilderTest().day(own,call.getArgument(1),false);var captured=mock(SourceDayNewsValuePermitService.Captured.class);when(captured.dependency()).thenReturn(reference);return captured;});
            when(days.verify(any(),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return ((SourceDayNewsValuePermitService.Captured)call.getArgument(0)).dependency();});
            when(days.verifyObserved(any(),any())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return ((SourceDayNewsValuePermitService.Captured)call.getArgument(0)).dependency();});
            value=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(db.source),new JdbcFrozenSourceUniverses(db.source),q.h.f.p1.universes,plans,q.h.f.p1.collections,new NewsValueEligibilityService(new JdbcNewsDedupEligibility(db.source),new JdbcNewsValueEntities(db.source),q.h.f.p1.members,clock),deps,()->{settings++;return new FrozenGenerationSettings(1,java.util.List.of(),java.util.List.of());},clock,()->allowed,"2",days);
            parts=new SourceStagePreparationService(db.source,plans,q.h.f.p1.universes,q.h.f.p1.collections::read,clock,"fictional-owner",null,deps);
            return new SourcePlanPreparationCoordinator(q.h.f.p1.preparation,value,parts,q.service,q.h.proofs,new JdbcFrozenSourcePlans(db.source),writes,new DataSourceTransactionManager(db.source),clock,()->allowed,permit,days);
        }
    };}
}
