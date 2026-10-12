package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayNewsValuePermitConfigurationTest {
    final DataSource source=mock(DataSource.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);
    final SourceDayCollectionAdmission.Api days=mock(SourceDayCollectionAdmission.Api.class);
    final SourceDayCohortSealService cohorts=mock(SourceDayCohortSealService.class);
    static final List<String> FLAGS=SourceDayNewsDedupConfigurationTest.FLAGS;
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayNewsValuePermitConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager);}
    ApplicationContextRunner allPorts(){return context().withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(SourceDayCohortSealService.class,()->cohorts);}
    String[] flags(){return FLAGS.stream().map(f->f+"=true").toArray(String[]::new);}
    @Test void defaultAndEachMissingFlagKeepThePermissionProviderAbsent(){
        allPorts().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceDayNewsValuePermitService.class).isEmpty());});
        for(String absent:FLAGS)allPorts().withPropertyValues(FLAGS.stream().filter(f->!f.equals(absent)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceDayNewsValuePermitService.class).isEmpty());});
        verifyNoInteractions(source,manager,runs,days,cohorts);
    }
    @Test void allFlagsCreateOneRealPermissionFromOneRealGraphWithoutStartingWork(){
        allPorts().withPropertyValues(flags()).withPropertyValues("site.execution.preparation.enabled=false").run(c->{
            assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceAnalysisProofGraph.class).size());
            assertEquals(1,c.getBeansOfType(SourceDayNewsValuePermitService.class).size());assertFalse(mockingDetails(c.getBean(SourceDayNewsValuePermitService.class)).isMock());
            assertEquals(1,c.getBeansOfType(SourceDayNewsDedupBarrierService.class).size());assertEquals(1,c.getBeansOfType(SourceDayRunClaimAdmissionService.class).size());
            assertThrows(RuntimeException.class,()->c.getBean(SourceDayNewsValuePermitService.class).verify(null,null));
        });verifyNoInteractions(source,manager,runs,days,cohorts);
    }
    @Test void missingOriginalCollectionRunOrCohortCannotInstallFallbackPermission(){
        context().withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCohortSealService.class,()->cohorts).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        context().withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(SourceDayCohortSealService.class,()->cohorts).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        context().withBean(SourceDayRunRegistration.Api.class,()->runs).withBean(SourceDayCollectionAdmission.Api.class,()->days).withPropertyValues(flags()).run(c->assertNotNull(c.getStartupFailure()));
        verifyNoInteractions(source,manager,runs,days,cohorts);
    }
}
