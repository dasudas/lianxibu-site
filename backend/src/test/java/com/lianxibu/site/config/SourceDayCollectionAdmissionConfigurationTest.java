package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import com.lianxibu.site.service.jobs.SourceDayCollectionAdmissionService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SourceDayCollectionAdmissionConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final SourceDayCohortSealService seals=mock(SourceDayCohortSealService.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayCollectionAdmissionConfiguration.class).withBean(SourceDayCohortSealService.class,()->seals);}
    @Test void allFlagsAreRequiredAndAssemblyDoesNotReadSeal(){
        context().run(c->{assertFalse(c.containsBean("sourceDayCollectionAdmission"));assertNull(c.getStartupFailure());});
        for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayCollectionAdmission"));assertNull(c.getStartupFailure());});verifyNoInteractions(seals);
    }
    @Test void fullFlagsCreateOneActualApiAndHelperDoesNoWork(){
        context().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayCollectionAdmission.Api.class).size());assertInstanceOf(SourceDayCollectionAdmissionService.class,c.getBean(SourceDayCollectionAdmission.Api.class));});assertNotNull(new SourceDayCollectionAdmissionConfiguration().create(seals));verifyNoInteractions(seals);
    }
    @Test void missingOriginalSealCannotCreateAnAllowProvider(){
        new ApplicationContextRunner().withUserConfiguration(SourceDayCollectionAdmissionConfiguration.class).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNotNull(c.getStartupFailure());});assertThrows(RuntimeException.class,()->new SourceDayCollectionAdmissionConfiguration().create(null));
    }
}
