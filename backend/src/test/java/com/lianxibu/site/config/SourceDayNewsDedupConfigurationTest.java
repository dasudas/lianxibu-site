package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayNewsDedupConfigurationTest {
    final DataSource source=mock(DataSource.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);final SourceDayCohortSealService cohorts=mock(SourceDayCohortSealService.class);
    static final List<String> FLAGS=List.of("site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayNewsDedupConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCohortSealService.class,()->cohorts);}
    String[] flags(){return FLAGS.stream().map(f->f+"=true").toArray(String[]::new);}
    @Test void defaultAndEachMissingFlagDoNotCreateDayProviders(){
        context().run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourceDayNewsDedupProgress"));assertFalse(c.containsBean("sourceDayNewsDedupBarrier"));});
        for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("sourceDayNewsDedupBarrier"));});
        verifyNoInteractions(source,manager,runs,cohorts);
    }
    @Test void allFlagsReuseOneActualGraphAndCreateUniqueLazyProofProvidersWithPreparationOff(){
        context().withPropertyValues(flags()).withPropertyValues("site.execution.preparation.enabled=false").run(c->{
            assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceAnalysisProofGraph.class).size());
            assertEquals(1,c.getBeansOfType(SourceDayNewsDedupProgressService.class).size());assertEquals(1,c.getBeansOfType(SourceDayNewsDedupBarrierService.class).size());
        });verifyNoInteractions(source,manager,runs,cohorts);
    }
    @Test void missingOriginalRegistrationOrSealProviderCannotEnablePermissiveFallback(){
        new ApplicationContextRunner().withUserConfiguration(SourceDayNewsDedupConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withBean(SourceDayCohortSealService.class,()->cohorts).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        new ApplicationContextRunner().withUserConfiguration(SourceDayNewsDedupConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withBean(SourceDayRunRegistration.Api.class,()->runs).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        verifyNoInteractions(source,manager,runs,cohorts);
    }
    @Test void explicitHelperUsesActualOriginalProofGraphAndValidatesMandatoryInputsLazily(){
        var graph=new SourceAnalysisProofGraph(source,manager,mock(Clock.class),()->false);var config=new SourceDayNewsDedupConfiguration();var clock=mock(Clock.class);
        var progress=config.createProgress(manager,runs,graph);assertNotNull(config.createBarrier(source,manager,cohorts,progress,clock));
        assertThrows(RuntimeException.class,()->config.createProgress(manager,null,graph));assertThrows(RuntimeException.class,()->config.createProgress(null,runs,graph));assertThrows(RuntimeException.class,()->config.createProgress(manager,runs,null));
        assertThrows(RuntimeException.class,()->config.createBarrier(source,manager,null,progress,clock));assertThrows(RuntimeException.class,()->config.createBarrier(source,manager,cohorts,progress,null));
        verifyNoInteractions(source,manager,runs,cohorts,clock);
    }
}
