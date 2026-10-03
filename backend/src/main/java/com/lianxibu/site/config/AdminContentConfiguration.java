package com.lianxibu.site.config;

import com.lianxibu.site.repository.content.JdbcAdminContentReads;
import com.lianxibu.site.service.content.AdminContentReadService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Owner directory retains legacy metadata without conferring public publication eligibility. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.browser.enabled", havingValue = "true")
public class AdminContentConfiguration {
    @Bean
    AdminContentReadService adminContentReads(DataSource source, PlatformTransactionManager transactions) {
        return new AdminContentReadService(new JdbcAdminContentReads(source), transactions);
    }
}
