package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
class ReportNoUpdateProofConfigurationTest {
 static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(ReportNoUpdateProofConfiguration.class);}
 ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
 @Test void DefaultAndEveryMissingOrFalseFlagKeepNativeObserverAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportNoUpdateProofs"));});for(String missing:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportNoUpdateProofs"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(missing)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportNoUpdateProofs"));});}}
 @Test void ActualNativeNoUpdateAndPeriodComposeColdWithoutCaptureCycleOrExecutor(){var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);enabled().withUserConfiguration(ReportPeriodInputProofConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportNoUpdateProofService.class).size());assertEquals(1,c.getBeansOfType(ReportPeriodInputComponentService.class).size());assertTrue(c.getBeansOfType(ReportInputCaptureService.class).isEmpty());assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx);}
 @Test void MissingNativeDatasourceOrTransactionManagerFailsEnabledStartup(){enabled().withBean(DataSource.class,()->mock(DataSource.class)).run(c->assertNotNull(c.getStartupFailure()));enabled().withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).run(c->assertNotNull(c.getStartupFailure()));}
}
