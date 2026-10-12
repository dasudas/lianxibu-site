package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.jobs.OperationalSettingsAudit;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Persistent owner settings and audit participate in the shared write transaction. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.browser.enabled", havingValue = "true")
public class SettingsRuntimeConfiguration {
    @Bean SettingsJdbcStore settingsStore(DataSource source) {
        var learning = new JdbcLearningReads(source);
        return new SettingsJdbcStore(new JdbcSettingsWrites(source),
                id -> learning.packageById(id) != null,
                new OperationalSettingsAudit(new JdbcJobRecords(source)), Clock.systemUTC());
    }

    @Bean SettingsUpdateService settingsService(SettingsJdbcStore store, DataSource source,
                                                PlatformTransactionManager tx) {
        return new SettingsUpdateService(store, new SharedWriteExecutor(new JdbcSharedWriteEntry(source), tx));
    }
}
