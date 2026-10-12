package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class DispatchReadinessConfigurationTest {
 final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class,DispatchReadinessConfiguration.class).withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class));
 @Test void disabledByDefaultAndEveryPrerequisiteRequired(){context.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("dispatchReadiness"));});String[] names={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"};for(String absent:names){var properties=java.util.Arrays.stream(names).map(n->n+"="+!n.equals(absent)).toArray(String[]::new);context.withPropertyValues(properties).run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("dispatchReadiness"));});}}
 @Test void enabledAssemblyProvidesRealServiceWithoutStartingAnyWork(){context.withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true").run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(DispatchReadinessService.class));assertSame(c.getBean(ResultApplicationFactory.class),c.getBean(AttemptOutcomeService.Applications.class));verifyNoInteractions(c.getBean(DataSource.class));assertThrows(RuntimeException.class,()->c.getBean(DispatchReadinessService.class).ready("fictional-root"));});}
}
