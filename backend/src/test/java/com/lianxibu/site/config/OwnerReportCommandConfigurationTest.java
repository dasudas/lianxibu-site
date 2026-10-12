package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual bean assembly remains cold, opt-in and mandatory-proof complete. */
class OwnerReportCommandConfigurationTest {
 static final List<String> FLAGS=List.of("site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled");
 final DataSource source=mock(DataSource.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);final ReportPreparedInputService originals=mock(ReportPreparedInputService.class);final ReportInputWriterAdmissionService admission=mock(ReportInputWriterAdmissionService.class);final SettingsUpdateService settings=mock(SettingsUpdateService.class);final OperationalInputGate gate=mock(OperationalInputGate.class);
 ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(OwnerReportCommandConfiguration.class);}
 ApplicationContextRunner enabled(){return base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));}
 ApplicationContextRunner ports(ApplicationContextRunner context,int missing){if(missing!=0)context=context.withBean(DataSource.class,()->source);if(missing!=1)context=context.withBean(PlatformTransactionManager.class,()->tx);if(missing!=2)context=context.withBean(ReportPreparedInputService.class,()->originals);if(missing!=3)context=context.withBean(ReportInputWriterAdmissionService.class,()->admission);if(missing!=4)context=context.withBean(SettingsUpdateService.class,()->settings);if(missing!=5)context=context.withBean(OperationalInputGate.class,()->gate);return context;}
 void untouched(){verifyNoInteractions(source,tx,originals,admission,settings,gate);}
 @Test void DefaultAndEveryMissingOrDisabledScopeCreatesNoCommandsOrDependencyTraffic(){base().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("ownerReportOperations"));});for(String flag:FLAGS){base().withPropertyValues(FLAGS.stream().filter(f->!f.equals(flag)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("ownerReportOperations"));});enabled().withPropertyValues(flag+"=false").run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("ownerReportOperations"));});}untouched();}
 @Test void EnabledAssemblyCreatesActualColdCommandServicesWithoutExecutor(){ports(enabled(),-1).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(OwnerReportOperations.class));assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());});untouched();}
 @Test void EachMissingMandatoryProofDependencyFailsStartupBeforeIo(){for(int missing=0;missing<6;missing++)ports(enabled(),missing).run(c->assertNotNull(c.getStartupFailure()));untouched();}
 @Test void PublicFacadeUsesConfiguredActualOwnerServiceForBothRoutes(){ports(enabled(),-1).run(c->{assertNull(c.getStartupFailure());var reports=c.getBean(OwnerReportOperations.class);try(var provider=new GenericApplicationContext()){provider.registerBean(OwnerReportOperations.class,()->reports);provider.refresh();var facade=new PublicContentRuntimeConfiguration().publicContent(source,tx,provider.getBeanProvider(OwnerReportOperations.class));assertEquals(IdentityService.Refusal.UNAUTHENTICATED,assertThrows(IdentityService.Rejected.class,()->facade.generationIntent(null,null,null,null,null)).refusal());assertEquals(IdentityService.Refusal.FORBIDDEN,assertThrows(IdentityService.Rejected.class,()->facade.action(new IdentityService.Actor("fictional-reader","reader"),"report","fictional-report",new ContentService.ContentAction(1,"supplement_generate","Fictional reason",null),"fictional-key","fictional-csrf","https://fictional.invalid")).refusal());}});untouched();}
 @Test void PublicFacadeWithoutReportProofConfigurationRetainsFailClosedOptionalRoute(){try(var provider=new GenericApplicationContext()){provider.refresh();var facade=new PublicContentRuntimeConfiguration().publicContent(source,tx,provider.getBeanProvider(OwnerReportOperations.class));assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,assertThrows(IdentityService.Rejected.class,()->facade.generationIntent(new IdentityService.Actor("fictional-owner","owner"),null,null,null,null)).refusal());}untouched();}
}
