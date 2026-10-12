package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import javax.sql.DataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OwnerJobApplicationConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.execution.enabled"};
    ApplicationContextRunner context(DataSource source,SettingsJdbcStore settings){return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).withBean(SettingsJdbcStore.class,()->settings);}
    @Test void allFlagsConstructUniqueActualApplicationAndCommandsWithoutIo(){var source=mock(DataSource.class);var settings=mock(SettingsJdbcStore.class);context(source,settings).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(OwnerJobApplyResultService.class).size());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertInstanceOf(OwnerJobCommandsService.class,c.getBean(OwnerJobsService.Commands.class));});verifyNoInteractions(source,settings);}
    @Test void defaultAndEachMissingFlagNeverAssembleOrRead(){var source=mock(DataSource.class);var settings=mock(SettingsJdbcStore.class);context(source,settings).run(c->assertTrue(c.getBeansOfType(OwnerJobApplyResultService.class).isEmpty()));for(String missing:FLAGS)context(source,settings).withPropertyValues(Arrays.stream(FLAGS).filter(f->!missing.equals(f)).map(f->f+"=true").toArray(String[]::new)).run(c->assertTrue(c.getBeansOfType(OwnerJobApplyResultService.class).isEmpty()));verifyNoInteractions(source,settings);}
    @Test void enabledMissingActualDatabaseFailsClosed(){new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new)).run(c->assertNotNull(c.getStartupFailure()));}
}
