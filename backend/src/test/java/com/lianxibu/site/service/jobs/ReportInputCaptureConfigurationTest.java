package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.ReportInputCaptureConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
class ReportInputCaptureConfigurationTest {
 static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(ReportInputCaptureConfiguration.class);}
 ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
 @Test void DefaultEveryMissingOrFalseFlagKeepsCaptureAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportInputCapture"));});for(String missing:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportInputCapture"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(missing)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportInputCapture"));});}}
 @Test void AllFlagsAndMandatoryPortsCreateOneColdCaptureWithoutExecutor(){var daily=mock(DailyReportInputComponentService.class);var period=mock(ReportPeriodInputComponentService.class);var tx=mock(PlatformTransactionManager.class);enabled().withBean(DailyReportInputComponentService.class,()->daily).withBean(ReportPeriodInputComponentService.class,()->period).withBean(PlatformTransactionManager.class,()->tx).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportInputCaptureService.class).size());assertSame(c.getBean("reportInputCapture"),c.getBean(ReportInputCaptureService.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());assertTrue(c.getBeansOfType(ReportInputSnapshotService.Source.class).isEmpty());});verifyNoInteractions(daily,period,tx);}
 @Test void EachMissingNativePortRejectsEnabledStartup(){for(int absent=0;absent<3;absent++){var runner=enabled();if(absent!=0)runner=runner.withBean(DailyReportInputComponentService.class,()->mock(DailyReportInputComponentService.class));if(absent!=1)runner=runner.withBean(ReportPeriodInputComponentService.class,()->mock(ReportPeriodInputComponentService.class));if(absent!=2)runner=runner.withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));runner.run(c->assertNotNull(c.getStartupFailure()));}}
}
