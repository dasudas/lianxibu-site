package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayRunClaimAdmissionConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final DataSource source=mock(DataSource.class);private final SourceDayCollectionAdmission.Api days=mock(SourceDayCollectionAdmission.Api.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayRunClaimAdmissionConfiguration.class).withBean(DataSource.class,()->source).withBean(SourceDayCollectionAdmission.Api.class,()->days).withBean(PlatformTransactionManager.class,()->manager);}
    @Test void everyFlagIsRequiredAndAssemblyHasNoWork(){
        context().run(c->{assertFalse(c.containsBean("sourceDayRunClaimAdmission"));assertNull(c.getStartupFailure());});for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayRunClaimAdmission"));assertNull(c.getStartupFailure());});verifyNoInteractions(source,days);
    }
    @Test void allFlagsCreateOneActualConsumerAndHelperIsLazy(){
        context().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayRunClaimAdmissionService.class).size());});assertNotNull(new SourceDayRunClaimAdmissionConfiguration().create(source,manager,days));verifyNoInteractions(source,days,manager);
    }
    @Test void missingDayProviderFailsClosed(){
        new ApplicationContextRunner().withUserConfiguration(SourceDayRunClaimAdmissionConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNotNull(c.getStartupFailure());});assertThrows(RuntimeException.class,()->new SourceDayRunClaimAdmissionConfiguration().create(source,null));verifyNoInteractions(source,days,manager);
    }
    @Test void missingReadScopeCannotConstructProductionConsumer(){
        new ApplicationContextRunner().withUserConfiguration(SourceDayRunClaimAdmissionConfiguration.class).withBean(DataSource.class,()->source).withBean(SourceDayCollectionAdmission.Api.class,()->days).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNotNull(c.getStartupFailure());});assertThrows(RuntimeException.class,()->new SourceDayRunClaimAdmissionConfiguration().create(source,null,days));verifyNoInteractions(source,days,manager);
    }
}
