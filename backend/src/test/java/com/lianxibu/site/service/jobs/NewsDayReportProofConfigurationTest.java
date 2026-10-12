package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class NewsDayReportProofConfigurationTest {
    static final List<String> FLAGS=CatalogDayReportProofConfigurationTest.FLAGS;
    @Test void DefaultsAndEachMissingFlagKeepOriginalProvidersAbsent(){var runner=new ApplicationContextRunner().withUserConfiguration(NewsDayReportProofConfiguration.class);runner.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("newsDayReportComponent"));});for(String missing:FLAGS)runner.withPropertyValues(FLAGS.stream().filter(f->!f.equals(missing)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourceValueReportCompletion"));assertFalse(c.containsBean("newsSourceReportComponent"));assertFalse(c.containsBean("newsDayReportComponent"));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});}
    @Test void FormalBeanUsesTheActualSourceGraphWithoutIoOrExecutors(){var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var clock=mock(Clock.class);var graph=new SourceAnalysisProofGraph(source,tx,clock,()->{throw new AssertionError("cold gate");});var cohorts=mock(SourceDayCohortSealService.class);var barriers=mock(SourceDayNewsDedupBarrierService.class);var registrations=mock(SourceDayRunClaimAdmissionService.class);var permits=mock(SourceDayNewsValuePermitService.class);
        new ApplicationContextRunner().withUserConfiguration(NewsDayReportProofConfiguration.class).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SourceAnalysisProofGraph.class,()->graph).withBean(SourceDayCohortSealService.class,()->cohorts).withBean(SourceDayNewsDedupBarrierService.class,()->barriers).withBean(SourceDayRunClaimAdmissionService.class,()->registrations).withBean(SourceDayNewsValuePermitService.class,()->permits).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceValueReportCompletionService.class).size());assertEquals(1,c.getBeansOfType(NewsSourceReportComponentService.class).size());assertEquals(1,c.getBeansOfType(NewsDayReportComponentService.class).size());assertSame(c.getBean("newsDayReportComponent"),c.getBean(NewsDayReportComponentService.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,clock,cohorts,barriers,registrations,permits);
    }
    @Test void EnabledMissingNativeGraphCannotStartWithAnEmptyFallback(){new ApplicationContextRunner().withUserConfiguration(NewsDayReportProofConfiguration.class).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).run(c->assertNotNull(c.getStartupFailure()));}
}
