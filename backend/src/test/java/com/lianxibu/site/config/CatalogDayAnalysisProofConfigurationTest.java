package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class CatalogDayAnalysisProofConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"};
    final DataSource source=mock(DataSource.class);final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);final SettingsUpdateService settings=mock(SettingsUpdateService.class);
    final SourceDayCohortSealService cohorts=mock(SourceDayCohortSealService.class);final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);final OperationalInputGate gate=mock(OperationalInputGate.class);
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(CatalogDayAnalysisProofConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings);}
    ApplicationContextRunner enabled(){return runner().withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).withBean(SourceDayCohortSealService.class,()->cohorts).withBean(SourceDayRunRegistration.Api.class,()->runs);}
    @Test void EveryMissingFlagAndDefaultLeaveDayGraphsDisabledWithoutReadingPorts(){
        runner().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(CatalogDayAnalysisProofConfiguration.Graphs.class).isEmpty());});
        for(String absent:FLAGS)runner().withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(CatalogDayAnalysisProofConfiguration.Graphs.class).isEmpty());});verifyNoInteractions(source,tx,settings,cohorts,runs,gate);
    }
    @Test void EnabledActualFourCategoryRegistryAndBothPermissionMapsRemainColdAndImmutable(){
        enabled().withBean(OperationalInputGate.class,()->gate).withPropertyValues("site.execution.preparation.enabled=false","site.execution.catalog-preparation.enabled=false","site.execution.worker.enabled=false").run(c->{
            assertNull(c.getStartupFailure());var graphs=c.getBean(CatalogDayAnalysisProofConfiguration.Graphs.class);assertEquals(Set.of("agent","skill","workflow","model"),graphs.byCategory().keySet());
            for(String category:CatalogSourceProofConfiguration.CATEGORIES){assertEquals(category,graphs.graph(category).category());assertSame(graphs.graph(category).dispatch(),graphs.dispatchPermissions().get(category));assertSame(graphs.graph(category).result(),graphs.resultPermissions().get(category));}
            assertThrows(UnsupportedOperationException.class,()->graphs.byCategory().clear());assertThrows(UnsupportedOperationException.class,()->graphs.dispatchPermissions().clear());assertThrows(UnsupportedOperationException.class,()->graphs.resultPermissions().clear());assertThrows(RuntimeException.class,()->graphs.graph("news_tech"));
            assertTrue(c.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).isEmpty());verifyNoInteractions(source,tx,settings,cohorts,runs,gate);
        });
    }
    @Test void MissingNativeDayPortsFailStartupAndMissingGateDoesNotResolveDuringConstruction(){
        runner().withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->assertNotNull(c.getStartupFailure()));
        runner().withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).withBean(SourceDayCohortSealService.class,()->cohorts).run(c->assertNotNull(c.getStartupFailure()));
        enabled().run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(CatalogDayAnalysisProofConfiguration.Graphs.class));});verifyNoInteractions(source,tx,settings,cohorts,runs,gate);
    }
    @Test void RegistryRejectsMissingForeignAndNullGraphEntriesWithoutPortIo(){
        var clock=mock(Clock.class);var allowed=mock(java.util.function.BooleanSupplier.class);var originals=CatalogSourceProofConfiguration.assemble(source,tx,clock,settings,allowed);var all=CatalogDayAnalysisProofConfiguration.assemble(source,tx,clock,cohorts,runs,originals,settings,allowed);
        var missing=new HashMap<>(all.byCategory());missing.remove("model");assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofConfiguration.Graphs(missing));var alias=new HashMap<>(all.byCategory());alias.put("skill",all.graph("agent"));assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofConfiguration.Graphs(alias));var nul=new HashMap<>(all.byCategory());nul.put("model",null);assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofConfiguration.Graphs(nul));assertThrows(RuntimeException.class,()->CatalogDayAnalysisProofConfiguration.assemble(source,tx,clock,cohorts,runs,originals,null,allowed));verifyNoInteractions(source,tx,clock,settings,cohorts,runs,allowed);
    }
}
