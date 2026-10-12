package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class NewsValueFinalRuntimeConfigurationTest {
    SourceValueFinalizationTest x;SourceValueStageProofTest f;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){x=new SourceValueFinalizationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueFinalRuntimeConfigurationTest.this.fixture();}};x.setup();f=x.f;}
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class).withBean(DataSource.class,()->f.f.source).withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.source)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).withBean(OperationalInputGate.class,()->mock(OperationalInputGate.class)).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.preparation.enabled=true");}
    @Test void actualConfiguredOriginalIsFinalizedAndObservedExactlyOnce(){
        context().run(c->{assertNull(c.getStartupFailure());var finals=c.getBean(SourceValueFinalizationService.class);var observer=c.getBean(SourceValueFinalAvailabilityService.class);assertNull(finals.finish(f.ref));f.complete(0);assertNull(observer.capture(f.ref));var sealed=finals.finish(f.ref);assertEquals(1,x.seals());assertEquals("succeeded",x.intentState());assertNotNull(x.sourceText("analysis_summary"));var committed=f.readonly(()->finals.readCommitted(f.ref));assertEquals(sealed.json(),committed.receipt().json());var observed=observer.capture(f.ref);assertNotNull(observed);assertFalse(observed.isBefore(committed.minimumObservationTime()));assertEquals(observed,observer.capture(f.ref));assertEquals(observed,f.readonly(()->observer.availableAt(committed,observed)));assertNull(f.readonly(()->observer.availableAt(committed,observed.minusNanos(1000))));int count=f.f.count("1=1");assertEquals(sealed.json(),finals.finish(f.ref).json());assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));});
    }
    @Test void configuredEmptyAndBadOriginalCannotCreateFalseFinalAvailability(){
        f.install(0);x.install();context().run(c->{assertNull(c.getStartupFailure());var finals=c.getBean(SourceValueFinalizationService.class);var observer=c.getBean(SourceValueFinalAvailabilityService.class);assertNull(observer.capture(f.ref));assertNotNull(finals.finish(f.ref));assertNotNull(observer.capture(f.ref));assertEquals("no_input",x.intentState());assertEquals(0,f.f.count("record_kind='logical_task'"));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,()->observer.capture(f.ref));assertEquals(1,f.f.count("target_type='source_value_final_availability'"));});
    }
}
