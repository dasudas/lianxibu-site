package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JobsExternalManualConfigurationTest extends JobsRuntimeConfigurationTest {
    @Test void FullOwnerRuntimeIncludesActualProofFactoryAndResolverWithZeroIo(){DataSource source=mock(DataSource.class);SettingsJdbcStore settings=mock(SettingsJdbcStore.class);var readiness=mock(DispatchReadinessService.class);context(source,settings).withBean(DispatchReadinessService.class,()->readiness).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true","site.articles.enabled=true","site.sources.enabled=true").run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ResultApplicationFactory.class).size());assertEquals(1,c.getBeansOfType(SourceAnalysisProofGraph.class).size());assertEquals(1,c.getBeansOfType(OwnerJobResolveExternalService.class).size());});verifyNoInteractions(source,settings,readiness);}
}
