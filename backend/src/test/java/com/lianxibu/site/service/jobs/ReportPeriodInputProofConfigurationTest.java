package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.ReportPeriodInputProofConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
class ReportPeriodInputProofConfigurationTest {
    static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
    ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(ReportPeriodInputProofConfiguration.class);}
    ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
    @Test void DefaultAndEveryMissingOrFalseFlagKeepBothReadersAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPeriodInputComponent"));assertFalse(c.containsBean("reportOriginalCompletions"));});for(String missing:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPeriodInputComponent"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(missing)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportPeriodInputComponent"));});}}
    @Test void AllFlagsComposeUniqueNativeReadersColdWithoutExecutorOrModelIo(){var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);enabled().withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(ReportNoUpdateProofService.class,()->mock(ReportNoUpdateProofService.class)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportOriginalCompletionService.class).size());assertEquals(1,c.getBeansOfType(ReportPeriodInputComponentService.class).size());assertSame(c.getBean("reportPeriodInputComponent"),c.getBean(ReportPeriodInputComponentService.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx);}
    @Test void MissingMandatoryProgramProofRejectsRatherThanUsingACountFallback(){enabled().withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).run(c->assertNotNull(c.getStartupFailure()));}
    @Test void EitherMissingNativeDatasourceOrTransactionManagerRejectsEnabledStartup(){enabled().withBean(DataSource.class,()->mock(DataSource.class)).run(c->assertNotNull(c.getStartupFailure()));enabled().withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).run(c->assertNotNull(c.getStartupFailure()));}
}
