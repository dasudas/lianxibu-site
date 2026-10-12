package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.SourceDayRosterRegistrationService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayRosterRegistrationConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final DataSource source=mock(DataSource.class);private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayRosterRegistrationConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager);}
    @Test void missingOrFalseFlagsNeverCreateRegistryOrPerformWork(){
        context().run(c->{assertFalse(c.containsBean("sourceDayRosterRegistration"));assertNull(c.getStartupFailure());});
        for(var omitted:FLAGS){var values=FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new);context().withPropertyValues(values).run(c->{assertFalse(c.containsBean("sourceDayRosterRegistration"));assertNull(c.getStartupFailure());});}
        context().withPropertyValues(FLAGS.stream().map(f->f+"=false").toArray(String[]::new)).run(c->{assertFalse(c.containsBean("sourceDayRosterRegistration"));assertNull(c.getStartupFailure());});verifyNoInteractions(source,manager);
    }
    @Test void allFlagsCreateOneActualLazyRegistry(){
        context().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayRosterRegistrationService.class).size());});verifyNoInteractions(source,manager);
    }
    @Test void actualHelperHasNoSqlClockOrTransactionWorkDuringAssembly(){
        Clock clock=mock(Clock.class);assertNotNull(new SourceDayRosterRegistrationConfiguration().create(source,manager,clock));verifyNoInteractions(source,manager,clock);
    }
}
