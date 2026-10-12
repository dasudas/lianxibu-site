package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
class ReportDispatchProofConfigurationTest {
 static final List<String> FLAGS=List.of("site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled");
 final DataSource source=mock(DataSource.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);final ReportPreparedInputService prepared=mock(ReportPreparedInputService.class);final ReportNoUpdateProofService noUpdates=mock(ReportNoUpdateProofService.class);
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(ReportDispatchProofConfiguration.class);}
 ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
 @Test void DefaultAndEveryMissingOrFalseFlagKeepReportPermissionAbsent(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportDispatchPermits"));});for(String flag:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(flag)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportDispatchPermits"));});base().withPropertyValues(FLAGS.stream().map(f->f+"="+!f.equals(flag)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reportDispatchPermits"));});}}
 @Test void ActualNativePermissionBeanIsUniqueColdWithoutBackgroundWork(){enabled().withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(ReportPreparedInputService.class,()->prepared).withBean(ReportNoUpdateProofService.class,()->noUpdates).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ReportDispatchPermitService.class).size());assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,prepared,noUpdates);}
 @Test void EveryMissingNativePortFailsEnabledStartup(){for(int missing=0;missing<4;missing++){var c=enabled();if(missing!=0)c=c.withBean(DataSource.class,()->source);if(missing!=1)c=c.withBean(PlatformTransactionManager.class,()->tx);if(missing!=2)c=c.withBean(ReportPreparedInputService.class,()->prepared);if(missing!=3)c=c.withBean(ReportNoUpdateProofService.class,()->noUpdates);c.run(x->assertNotNull(x.getStartupFailure()));}verifyNoInteractions(source,tx,prepared,noUpdates);}
}
