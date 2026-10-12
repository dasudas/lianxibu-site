package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.ReportPreparationProofConfiguration;
import com.lianxibu.site.config.OperationalInputGate;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class ReportPreparationProofConfigurationTest {
 static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(ReportPreparationProofConfiguration.class);}
 ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
 @Test void DefaultEveryMissingOrFalseFlagKeepsAllPreparationProvidersAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPreparation"));assertFalse(c.containsBean("reportInputWriterAdmission"));assertFalse(c.containsBean("reportPreparedInputs"));});for(String missing:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPreparation"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(missing)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPreparation"));});}}
 @Test void AllFlagsMandatoryNativePortsComposeUniqueColdPreparationWithoutExecutors(){var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var inputs=mock(ReportInputCaptureService.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);enabled().withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(ReportInputCaptureService.class,()->inputs).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportPreparedInputService.class).size());assertEquals(1,c.getBeansOfType(ReportInputWriterAdmissionService.class).size());assertEquals(1,c.getBeansOfType(ReportPreparationService.class).size());assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());assertTrue(c.getBeansOfType(ReportInputSnapshotService.Source.class).isEmpty());});verifyNoInteractions(source,tx,inputs,settings,gate);}
 @Test void EachMissingNativeSourceTxSettingsOrOperationalGateRejectsEnabledStartup(){for(int missing=0;missing<5;missing++){var r=enabled();if(missing!=0)r=r.withBean(DataSource.class,()->mock(DataSource.class));if(missing!=1)r=r.withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));if(missing!=2)r=r.withBean(ReportInputCaptureService.class,()->mock(ReportInputCaptureService.class));if(missing!=3)r=r.withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class));if(missing!=4)r=r.withBean(OperationalInputGate.class,()->mock(OperationalInputGate.class));r.run(c->assertNotNull(c.getStartupFailure()));}}
}
