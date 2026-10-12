package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/** Formal Spring consumer assembly over actual native INPUT and worker facts. Transport is fictional. */
class CatalogDayAnalysisRuntimeRoutingTest {
    boolean mysql(){return false;}
    static final String[] FLAGS={"site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.sources.day-roster.enabled=true"};
    CatalogDayAnalysisResultTest results(){return new CatalogDayAnalysisResultTest(){@Override boolean mysql(){return CatalogDayAnalysisRuntimeRoutingTest.this.mysql();}};}
    CatalogDayAnalysisDispatchTest dispatch(){return new CatalogDayAnalysisDispatchTest(){@Override boolean mysql(){return CatalogDayAnalysisRuntimeRoutingTest.this.mysql();}};}
    ApplicationContextRunner runner(CatalogDayFullNativeIntegrationTest.Fixture fixture){var f=fixture.f;return new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class,DispatchReadinessConfiguration.class,SourceDayCollectionAdmissionConfiguration.class).withPropertyValues(FLAGS).withPropertyValues("site.execution.worker.enabled=false","site.execution.catalog-preparation.enabled=false").withBean(DataSource.class,()->f.source).withBean(PlatformTransactionManager.class,()->f.tx).withBean("resultApplicationClock",java.time.Clock.class,()->java.time.Clock.fixed(fixture.f.clock.instant().plusSeconds(180),java.time.ZoneOffset.UTC)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).withBean(SourceDayCohortSealService.class,()->fixture.cohorts).withBean(SourceDayRunRegistration.Api.class,()->fixture.runs);}
    @Test void FormalFourCategoryFactoryAppliesActualContentAndEntityWithFullOriginalProof(){
        for(String category:List.of("agent","skill","workflow","model")){var helper=results();var built=helper.fixture(category,false);var data=built.data().data().data().data();var f=data.f().f;
            runner(data.f()).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(CatalogDayAnalysisProofConfiguration.Graphs.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());var factory=c.getBean(ResultApplicationFactory.class);assertSame(factory,c.getBean(AttemptOutcomeService.Applications.class));var application=factory.forAttempt(built.identity());var result=application.service().applyAutomatic(application.input(),built.raw(),helper.action(built,"spring-apply"));assertEquals(ResultApplicationService.Status.APPLIED,result.status());assertEquals("analyzed",f.jdbc.queryForObject("SELECT analysis_state FROM analyzed_entities",String.class));assertEquals(built.identity().rootId(),f.jdbc.queryForObject("SELECT input_job_id FROM contents WHERE id=?",String.class,result.contentId()));assertEquals(1,f.jdbc.queryForObject("SELECT call_count FROM jobs WHERE id=?",Integer.class,built.identity().rootId()));assertEquals(result,application.service().applyAutomatic(application.input(),built.raw(),helper.action(built,"spring-apply")));});
        }
    }
    @Test void FormalReadinessAuthorizesActualNativeWorkerAndSendsExactlyTheFrozenRequestOnce(){
        var helper=dispatch();var built=helper.fixture("skill",false);var data=built.data().data().data();var fixture=data.f();var f=fixture.f;var count=new AtomicInteger();
        runner(fixture).run(c->{assertNull(c.getStartupFailure());var readiness=c.getBean(DispatchReadinessService.class);var worker=new WorkerAuthorizationService(f.writes,new WorkerJdbcStore(new JdbcWorkerState(f.source),new JdbcJobRecords(f.source),"fictional-spring-analysis-epoch",()->false,readiness,built.clock(),f.tx),built.clock(),()->true);var claim=worker.claim(helper.action("spring-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());assertEquals(built.data().input().ownerId(),claim.permit().identity().rootId());assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{count.incrementAndGet();assertEquals(built.data().input().requestJson(),JdbcFrozenInputs.canonical(CanonicalJson.parse(request)));return "fictional Spring analysis response";}).state());assertEquals(1,count.get());assertEquals(1,f.jdbc.queryForObject("SELECT call_count FROM jobs WHERE id=?",Integer.class,claim.permit().identity().rootId()));});
    }
    @Test void FormalFactoryRejectsChangedCurrentGenerationAndRetainsValidatedResponseWithoutApplying(){
        var helper=results();var built=helper.fixture("model",false);var data=built.data().data().data().data();var f=data.f().f;f.jdbc.update("UPDATE analyzed_entities SET analysis_generation=analysis_generation+1");
        runner(data.f()).run(c->{assertNull(c.getStartupFailure());var application=c.getBean(ResultApplicationFactory.class).forAttempt(built.identity());assertThrows(RuntimeException.class,()->application.service().applyAutomatic(application.input(),built.raw(),helper.action(built,"spring-stale")));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE input_job_id=?",Integer.class,built.identity().rootId()));assertNull(f.jdbc.queryForObject("SELECT applied_at FROM jobs WHERE id=?",java.sql.Timestamp.class,built.identity().rootId()));assertNotNull(new JdbcResultState(f.source).root(built.identity().rootId()).resultHash());assertEquals(1,f.jdbc.queryForObject("SELECT call_count FROM jobs WHERE id=?",Integer.class,built.identity().rootId()));});
    }
    @Test void LegacyAssemblyRemainsColdAndEnabledDayRoutingRequiresNativeGraphs(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var runner=new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class,DispatchReadinessConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx);
        runner.withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true").run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(ResultApplicationFactory.class));assertNotNull(c.getBean(DispatchReadinessService.class));assertTrue(c.getBeansOfType(CatalogDayAnalysisProofConfiguration.Graphs.class).isEmpty());});
        runner.withPropertyValues(FLAGS).run(c->assertNotNull(c.getStartupFailure()));verifyNoInteractions(source,tx);
    }
}
