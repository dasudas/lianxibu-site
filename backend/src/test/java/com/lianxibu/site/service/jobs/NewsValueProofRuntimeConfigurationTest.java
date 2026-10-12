package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.NewsSourcePreparationConfiguration;
import com.lianxibu.site.config.OperationalInputGate;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class NewsValueProofRuntimeConfigurationTest {
    SourceValueStageProofTest f;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueProofRuntimeConfigurationTest.this.fixture();}};f.setup();}
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class).withBean(DataSource.class,()->f.f.source).withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.source)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).withBean(OperationalInputGate.class,()->mock(OperationalInputGate.class)).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.preparation.enabled=true");}
    @Test void actualConfiguredOriginalResultsGetOneObservationWithNoAdditionalAiCall(){
        context().run(c->{assertNull(c.getStartupFailure());var proofs=c.getBean(SourceValueStageProofService.class);var observations=c.getBean(SourceValueStageAvailabilityService.class);assertNull(observations.capture(f.ref));assertNull(f.readonly(()->proofs.read(f.ref)));assertEquals(0,f.f.count("target_type='source_value_stage_availability'"));
            f.complete(0);var proof=f.readonly(()->proofs.readCommitted(f.ref));assertEquals(f.read().json(),proof.result().json());var observed=observations.capture(f.ref);assertNotNull(observed);assertEquals(observed,observations.capture(f.ref));assertEquals(observed,f.readonly(()->observations.availableAt(proof,observed)));assertNull(f.readonly(()->observations.availableAt(proof,observed.minusNanos(1000))));assertEquals(1,f.f.count("target_type='source_value_stage_availability'"));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        });
    }
    @Test void configuredZeroPlanStillUsesNativeP1AndBadProofCannotBecomeAnObservation(){
        f.install(0);context().run(c->{assertNull(c.getStartupFailure());var proofs=c.getBean(SourceValueStageProofService.class);var observations=c.getBean(SourceValueStageAvailabilityService.class);assertTrue(f.readonly(()->proofs.read(f.ref)).members().isEmpty());var observed=observations.capture(f.ref);assertNotNull(observed);assertEquals(0,f.f.count("record_kind='logical_task'"));f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,()->observations.capture(f.ref));assertEquals(1,f.f.count("target_type='source_value_stage_availability'"));});
    }
}
