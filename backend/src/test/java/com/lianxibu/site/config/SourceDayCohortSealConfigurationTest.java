package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayCohortSealConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final DataSource source=mock(DataSource.class);private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayCohortSealConfiguration.class,SourceDayRosterRegistrationConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager);}
    @Test void missingOrFalseFlagsDoNotCreateSealOrPerformWork(){
        context().run(c->{assertFalse(c.containsBean("sourceDayCohortSeal"));assertNull(c.getStartupFailure());});
        for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayCohortSeal"));assertNull(c.getStartupFailure());});
        context().withPropertyValues(FLAGS.stream().map(f->f+"=false").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayCohortSeal"));assertNull(c.getStartupFailure());});verifyNoInteractions(source,manager);
    }
    @Test void allFlagsAssembleOneActualSealAndHelperIsLazy(){
        context().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayCohortSealService.class).size());});
        var clock=mock(Clock.class);assertNotNull(new SourceDayCohortSealConfiguration().create(source,manager,clock));verifyNoInteractions(source,manager,clock);
    }
    @Test void missingOriginalRegistryFailsClosed(){
        new ApplicationContextRunner().withUserConfiguration(SourceDayCohortSealConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNotNull(c.getStartupFailure());});verifyNoInteractions(source,manager);
    }
}
