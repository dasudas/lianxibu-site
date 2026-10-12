package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
class ReportProofRoutingConfigurationTest {
 final DataSource source=mock(DataSource.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);final SettingsUpdateService settings=mock(SettingsUpdateService.class);final SourceDayCohortSealService cohorts=mock(SourceDayCohortSealService.class);final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);final SourceDayCollectionAdmission.Api days=mock(SourceDayCollectionAdmission.Api.class);final ReportPreparedInputService prepared=mock(ReportPreparedInputService.class);final ReportNoUpdateProofService noUpdates=mock(ReportNoUpdateProofService.class);
 ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class,DispatchReadinessConfiguration.class).withPropertyValues(ReportDispatchProofConfigurationTest.FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(SourceDayCohortSealService.class,()->cohorts).withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCollectionAdmission.Api.class,()->days);}
 @Test void ReportEnabledWithoutActualPermissionPortFailsStartupInsteadOfLegacyRouting(){context().run(c->assertNotNull(c.getStartupFailure()));verifyNoInteractions(source,tx,settings,cohorts,runs,days);}
 @Test void CompleteNativeSourceCatalogAndReportRoutingComposeColdWithOneSharedReportProvider(){context().withUserConfiguration(ReportDispatchProofConfiguration.class).withBean(ReportPreparedInputService.class,()->prepared).withBean(ReportNoUpdateProofService.class,()->noUpdates).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportDispatchPermitService.class).size());assertEquals(1,c.getBeansOfType(DispatchReadinessService.class).size());assertSame(c.getBean(ResultApplicationFactory.class),c.getBean(AttemptOutcomeService.Applications.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,settings,cohorts,runs,days,prepared,noUpdates);}
}
