package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.SiteApplication;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.content.OwnerReportOperations;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import java.io.IOException;

/** Real application scan and native business beans together; lifecycle scheduling is explicitly
 * deferred only for this cold assembly gate. Runtime start/stop is covered by each loop's tests. */
class SiteBusinessCompositionTest {
 @TempDir Path directory;
 static final List<String> FLAGS=List.of("site.browser.enabled","site.collector.enabled","site.content.enabled","site.content.availability.enabled","site.content.propagation.enabled","site.articles.enabled","site.sources.enabled","site.sources.day-roster.enabled","site.execution.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled","site.execution.report-preparation.enabled","site.execution.report-runtime.enabled","site.execution.ad-hoc.enabled","site.execution.worker.enabled","site.learning.enabled","site.scheduler.enabled");
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 static class DeferredLifecycles {
  @Bean(name="lifecycleProcessor") DefaultLifecycleProcessor deferred(){return new DefaultLifecycleProcessor(){@Override public void onRefresh(){/* Explicit cold-only gate; no scheduler starts. */}};}
 }
 WebApplicationContextRunner runner(DataSource source){
  var properties=new ArrayList<String>();FLAGS.forEach(flag->properties.add(flag+"=true"));properties.addAll(List.of("SITE_DB_URL=jdbc:mysql://127.0.0.1:9/fictional_unreachable","SITE_DB_USER=fictional","SITE_DB_PASSWORD=fictional","SITE_BROWSER_ORIGIN=https://example.invalid","SITE_OWNER_IDS=fictional-owner","SITE_USER_IDS=fictional-user","SITE_RUN_LEASE_KEY_B64="+Base64.getEncoder().encodeToString(new byte[32]),"SITE_RUN_LEASE_KEY_ID=fictional-cold-key","SITE_DEEPSEEK_API_KEY=fictional-provider-credential","SITE_LEARNING_IMPORT_DIRECTORY="+directory.toAbsolutePath(),"site.ops.cloud.enabled=false","site.ops.rds.enabled=false","site.ops.delivery.enabled=false","logging.level.root=ERROR"));
  properties.add("SITE_NODE_TOKEN_SECRET_B64="+Base64.getEncoder().encodeToString(new byte[32]));
  String testOrigin=SiteBusinessCompositionTest.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();assertNotEquals(testOrigin,SiteApplication.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
  var testOnlyFilter=new TypeExcludeFilter(){@Override public boolean match(MetadataReader reader,MetadataReaderFactory factory)throws IOException{return reader.getResource().getURL().toExternalForm().startsWith(testOrigin);}};
  return new WebApplicationContextRunner().withUserConfiguration(SiteApplication.class,DeferredLifecycles.class).withPropertyValues(properties.toArray(String[]::new)).withBean(TypeExcludeFilter.class,()->testOnlyFilter).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
 }
 @Test void AllFormalBusinessModesComposeOneActualNativeGraphWithoutSqlOrModelCalls() throws Exception{
  var source=mock(DataSource.class);runner(source).run(context->{assertNull(context.getStartupFailure());assertEquals(List.of("formal-business-v1"),context.getBean(RuntimeCapabilities.class).supportedFeatures());assertEquals(1,context.getBeansOfType(ContentService.class).size());assertEquals(1,context.getBeansOfType(SourceAnalysisProofGraph.class).size());assertEquals(1,context.getBeansOfType(CatalogSourceProofConfiguration.Graphs.class).size());assertEquals(1,context.getBeansOfType(CatalogDayAnalysisProofConfiguration.Graphs.class).size());assertEquals(1,context.getBeansOfType(ReportPreparationService.class).size());assertEquals(1,context.getBeansOfType(ReportDispatchPermitService.class).size());assertEquals(1,context.getBeansOfType(OwnerReportOperations.class).size());assertEquals(1,context.getBeansOfType(AttemptOutcomeService.Applications.class).size());assertEquals(1,context.getBeansOfType(WorkerRuntimeCycle.class).size());assertEquals(1,context.getBeansOfType(DailyReportInputComponentService.class).size());assertEquals(1,context.getBeansOfType(ReportPeriodInputComponentService.class).size());assertEquals(4,context.getBean(CatalogDayAnalysisProofConfiguration.Graphs.class).byCategory().size());});noDatabaseConnection(source);
 }
 void noDatabaseConnection(DataSource source) throws java.sql.SQLException {verify(source,never()).getConnection();verify(source,never()).getConnection(anyString(),anyString());}
 void failure(Throwable value,String message){assertNotNull(value);for(int n=0;n<32&&value!=null;n++,value=value.getCause())if(message.equals(value.getMessage()))return;fail("exact required configuration refusal absent: "+message);}
 @Test void MissingOriginalCollectorLeaseKeyRejectsWholeActivatedAssembly() throws Exception{var source=mock(DataSource.class);runner(source).withPropertyValues("SITE_RUN_LEASE_KEY_B64=").run(context->failure(context.getStartupFailure(),"collector lease key unavailable"));noDatabaseConnection(source);}
 @Test void MissingProviderCredentialRejectsWholeActivatedWorkerAssembly() throws Exception{var source=mock(DataSource.class);runner(source).withPropertyValues("SITE_DEEPSEEK_API_KEY=").run(context->failure(context.getStartupFailure(),"worker provider credential unavailable"));noDatabaseConnection(source);}
}
