package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.JobResultComparisonReadService;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class JobResultComparisonRuntimeConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.execution.enabled"};
    ApplicationContextRunner context(DataSource source,PlatformTransactionManager tx){return new ApplicationContextRunner().withUserConfiguration(JobResultComparisonRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx);}
    @Test void exactCompleteFlagsConstructOnePermanentComparisonWithoutIo(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);
        context(source,tx).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobResultComparisonReadService.class).size());});verifyNoInteractions(source,tx);
    }
    @Test void defaultAndEveryMissingFlagStayDisabledWithoutIo(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);context(source,tx).run(c->assertTrue(c.getBeansOfType(JobResultComparisonReadService.class).isEmpty()));
        for(String omitted:FLAGS)context(source,tx).withPropertyValues(Arrays.stream(FLAGS).filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->assertTrue(c.getBeansOfType(JobResultComparisonReadService.class).isEmpty()));verifyNoInteractions(source,tx);
    }
    @Test void completeFlagsCannotSubstituteMissingPermanentStorage(){new ApplicationContextRunner().withUserConfiguration(JobResultComparisonRuntimeConfiguration.class).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new)).run(c->assertNotNull(c.getStartupFailure()));}
}
