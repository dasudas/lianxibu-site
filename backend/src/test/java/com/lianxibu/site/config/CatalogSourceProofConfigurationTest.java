package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class CatalogSourceProofConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"};
    @Test void DefaultAndEachMissingFlagCreateNoCatalogProviderOrIo(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);
        var runner=new ApplicationContextRunner().withUserConfiguration(CatalogSourceProofConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings);
        runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(CatalogSourceProofConfiguration.Graphs.class).isEmpty());});
        for(String absent:FLAGS)runner.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(CatalogSourceProofConfiguration.Graphs.class).isEmpty());});verifyNoInteractions(source,tx,settings);
    }
    @Test void EnabledProofAssemblyIsLazyClosedAndSeparateFromNewsAndPreparation(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);
        new ApplicationContextRunner().withUserConfiguration(CatalogSourceProofConfiguration.class,SourceAnalysisProofConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).withPropertyValues("site.execution.preparation.enabled=false","site.execution.catalog-preparation.enabled=false").run(c->{
            assertNull(c.getStartupFailure());var graphs=c.getBean(CatalogSourceProofConfiguration.Graphs.class);assertEquals(Set.of("agent","skill","workflow","model"),graphs.byCategory().keySet());for(String category:CatalogSourceProofConfiguration.CATEGORIES)assertEquals(category,graphs.graph(category).category());assertThrows(RuntimeException.class,()->graphs.graph("news_tech"));assertThrows(UnsupportedOperationException.class,()->graphs.byCategory().clear());assertNotNull(c.getBean(SourceAnalysisProofGraph.class));assertTrue(c.getBeansOfType(com.lianxibu.site.service.sources.SourceRunAnalysisService.class).isEmpty());assertTrue(c.getBeansOfType(CatalogSourceParentStepService.class).isEmpty());assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());verifyNoInteractions(source,tx,settings,gate);
        });
    }
    @Test void MissingSettingsFailsAndNoGateDoesNotResolveIoDuringConstruction(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);
        var runner=new ApplicationContextRunner().withUserConfiguration(CatalogSourceProofConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new));
        runner.run(c->assertNotNull(c.getStartupFailure()));runner.withBean(SettingsUpdateService.class,()->settings).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(CatalogSourceProofConfiguration.Graphs.class));});verifyNoInteractions(source,tx,settings);
    }
    @Test void RegistryRejectsMissingAliasedOrForeignGraphsAndNullSettings(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var clock=mock(Clock.class);var gate=mock(java.util.function.BooleanSupplier.class);var all=CatalogSourceProofConfiguration.assemble(source,tx,clock,settings,gate);
        var missing=new HashMap<>(all.byCategory());missing.remove("model");assertThrows(RuntimeException.class,()->new CatalogSourceProofConfiguration.Graphs(missing));var alias=new HashMap<>(all.byCategory());alias.put("skill",all.graph("agent"));assertThrows(RuntimeException.class,()->new CatalogSourceProofConfiguration.Graphs(alias));assertThrows(RuntimeException.class,()->CatalogSourceProofConfiguration.assemble(source,tx,clock,null,gate));verifyNoInteractions(source,tx,settings,clock,gate);
    }
}
