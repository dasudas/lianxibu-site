package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.DailyReportInputProofConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class DailyReportInputProofConfigurationTest {
    static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
    ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(DailyReportInputProofConfiguration.class);}
    ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
    @Test void DefaultAndEachMissingOrFalseFlagKeepTheDailyProofAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("dailyReportInputComponent"));});for(String missing:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("dailyReportInputComponent"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(missing)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("dailyReportInputComponent"));});}}
    @Test void FormalDailyConsumerUsesMandatoryNativeGroupProvidersWithoutIoOrExecutor(){var news=mock(NewsDayReportComponentService.class);var catalog=mock(CatalogDayReportAggregateService.class);var heat=mock(EntityHeatReportComponentService.class);var tx=mock(PlatformTransactionManager.class);enabled().withBean(NewsDayReportComponentService.class,()->news).withBean(CatalogDayReportAggregateService.class,()->catalog).withBean(EntityHeatReportComponentService.class,()->heat).withBean(PlatformTransactionManager.class,()->tx).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(DailyReportInputComponentService.class).size());assertSame(c.getBean("dailyReportInputComponent"),c.getBean(DailyReportInputComponentService.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(news,catalog,heat,tx);}
    @Test void AnyMissingNativeGroupOrTransactionPortRejectsEnabledStartup(){for(int missing=0;missing<4;missing++){var runner=enabled();if(missing!=0)runner=runner.withBean(NewsDayReportComponentService.class,()->mock(NewsDayReportComponentService.class));if(missing!=1)runner=runner.withBean(CatalogDayReportAggregateService.class,()->mock(CatalogDayReportAggregateService.class));if(missing!=2)runner=runner.withBean(EntityHeatReportComponentService.class,()->mock(EntityHeatReportComponentService.class));if(missing!=3)runner=runner.withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));runner.run(c->assertNotNull(c.getStartupFailure()));}}
}
