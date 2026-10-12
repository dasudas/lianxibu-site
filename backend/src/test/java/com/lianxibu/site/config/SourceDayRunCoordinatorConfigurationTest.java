package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import com.lianxibu.site.service.jobs.SourceDayRunCoordinatorService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SourceDayRunCoordinatorConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final SourceDayCohortSealService seals=mock(SourceDayCohortSealService.class);private final SourceDayRunRegistration.Api runs=mock(SourceDayRunRegistration.Api.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayRunCoordinatorConfiguration.class).withBean(SourceDayCohortSealService.class,()->seals).withBean(SourceDayRunRegistration.Api.class,()->runs);}
    @Test void everyFlagIsRequiredAndNothingRunsDuringAssembly(){
        context().run(c->{assertFalse(c.containsBean("sourceDayRunCoordinator"));assertNull(c.getStartupFailure());});
        for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayRunCoordinator"));assertNull(c.getStartupFailure());});
        context().withPropertyValues(FLAGS.stream().map(f->f+"=false").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayRunCoordinator"));assertNull(c.getStartupFailure());});verifyNoInteractions(seals,runs);
    }
    @Test void fullFlagsCreateOneActualCoordinatorAndHelperDoesNoWork(){
        context().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayRunCoordinatorService.class).size());});assertNotNull(new SourceDayRunCoordinatorConfiguration().create(seals,runs));verifyNoInteractions(seals,runs);
    }
    @Test void eitherMissingDependencyFailsClosed(){
        var context=new ApplicationContextRunner().withUserConfiguration(SourceDayRunCoordinatorConfiguration.class).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new));
        context.withBean(SourceDayCohortSealService.class,()->seals).run(c->{assertNotNull(c.getStartupFailure());});context.withBean(SourceDayRunRegistration.Api.class,()->runs).run(c->{assertNotNull(c.getStartupFailure());});
        assertThrows(RuntimeException.class,()->new SourceDayRunCoordinatorConfiguration().create(seals,null));verifyNoInteractions(seals,runs);
    }
}
