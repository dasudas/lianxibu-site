package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayProofConsumerConfigurationTest {
    final DataSource source=mock(DataSource.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SourceDayCollectionAdmission.Api days=mock(SourceDayCollectionAdmission.Api.class);final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);
    final SourceDayCohortSealService cohorts=mock(SourceDayCohortSealService.class);final SettingsUpdateService settings=mock(SettingsUpdateService.class);final OperationalInputGate gate=mock(OperationalInputGate.class);
    ApplicationContextRunner core(){return new ApplicationContextRunner().withUserConfiguration(NewsSourcePreparationConfiguration.class,DispatchReadinessConfiguration.class,ResultApplicationConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate);}
    ApplicationContextRunner ports(){return core().withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCohortSealService.class,()->cohorts);}
    String[] flags(){return java.util.stream.Stream.concat(SourceDayNewsDedupConfigurationTest.FLAGS.stream(),java.util.stream.Stream.of("site.execution.preparation.enabled")).map(f->f+"=true").toArray(String[]::new);}
    void noWork(){verifyNoInteractions(source,manager,days,runs,cohorts,settings,gate);}
    @Test void enabledDayDirectoryWiresOneRealDayAuthorityIntoAllThreeConsumers(){
        ports().withPropertyValues(flags()).run(c->{
            assertNull(c.getStartupFailure());var permit=c.getBean(SourceDayNewsValuePermitService.class);assertFalse(mockingDetails(permit).isMock());assertEquals(1,c.getBeansOfType(SourceAnalysisProofGraph.class).size());assertEquals(1,c.getBeansOfType(SourceDayNewsValuePermitService.class).size());
            assertSame(permit,ReflectionTestUtils.getField(c.getBean(NewsValueSourcePlanPreparationService.class),"dayPermits"));
            assertSame(permit,ReflectionTestUtils.getField(c.getBean(SourcePlanPreparationCoordinator.class),"dayPermits"));
            assertSame(permit,ReflectionTestUtils.getField(c.getBean(DispatchReadinessService.class),"dayPermits"));
            var factory=c.getBean(ResultApplicationFactory.class);var entity=ReflectionTestUtils.getField(ReflectionTestUtils.getField(factory,"dedup"),"entities");assertInstanceOf(com.lianxibu.site.repository.jobs.JdbcEntityHeatSnapshots.class,ReflectionTestUtils.getField(ReflectionTestUtils.getField(entity,"log"),"heat"));
            var catalogHandlers=(java.util.Map<?,?>)ReflectionTestUtils.getField(factory,"catalogDedup");assertEquals(4,catalogHandlers.size());for(var handler:catalogHandlers.values())assertSame(entity,ReflectionTestUtils.getField(handler,"entities"));
        });noWork();
    }
    @Test void enabledDayWithoutCollectionRunOrCohortFailsStartup(){
        core().withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCohortSealService.class,()->cohorts).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        core().withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(SourceDayCohortSealService.class,()->cohorts).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        core().withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(SourceDayRunRegistration.Api.class,()->runs).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));noWork();
    }
    @Test void enabledDayWithCollectorOffCannotSilentlyInstallLegacyDispatch(){
        ports().withPropertyValues(flags()).withPropertyValues("site.collector.enabled=false","site.execution.preparation.enabled=false").run(c->assertNotNull(c.getStartupFailure()));noWork();
    }
    @Test void explicitlyDisabledDayRetainsLegacyAssemblyWithoutStartingAnyWork(){
        core().withPropertyValues(flags()).withPropertyValues("site.sources.day-roster.enabled=false").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceDayNewsValuePermitService.class).isEmpty());assertNull(ReflectionTestUtils.getField(c.getBean(NewsValueSourcePlanPreparationService.class),"dayPermits"));assertNull(ReflectionTestUtils.getField(c.getBean(SourcePlanPreparationCoordinator.class),"dayPermits"));assertNull(ReflectionTestUtils.getField(c.getBean(DispatchReadinessService.class),"dayPermits"));var entity=ReflectionTestUtils.getField(ReflectionTestUtils.getField(c.getBean(ResultApplicationFactory.class),"dedup"),"entities");assertNull(ReflectionTestUtils.getField(ReflectionTestUtils.getField(entity,"log"),"heat"));});noWork();
    }
}
