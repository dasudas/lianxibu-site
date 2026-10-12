package com.lianxibu.site.config;

import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceDayRunRegistrations;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayRunClaimAdmissionConfiguration {
    @Bean public SourceDayRunClaimAdmissionService sourceDayRunClaimAdmission(DataSource source,PlatformTransactionManager manager,SourceDayCollectionAdmission.Api days){return create(source,manager,days);}
    public SourceDayRunClaimAdmissionService create(DataSource source,PlatformTransactionManager manager,SourceDayCollectionAdmission.Api days){return new SourceDayRunClaimAdmissionService(days,new JdbcSourceDayRunRegistrations(source),manager);}
    /** Compatibility helper with no read scope; preparation fails closed. */
    public SourceDayRunClaimAdmissionService create(DataSource source,SourceDayCollectionAdmission.Api days){return new SourceDayRunClaimAdmissionService(days,new JdbcSourceDayRunRegistrations(source));}
}
