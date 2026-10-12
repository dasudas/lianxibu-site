package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import com.lianxibu.site.repository.sources.JdbcSourceDayRoster;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SourceDayRosterRegistrationService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayRosterRegistrationConfiguration {
    @Bean public SourceDayRosterRegistrationService sourceDayRosterRegistration(DataSource source,PlatformTransactionManager manager){return create(source,manager,Clock.systemUTC());}
    public SourceDayRosterRegistrationService create(DataSource source,PlatformTransactionManager manager,Clock clock){
        return new SourceDayRosterRegistrationService(new JdbcSourceDayRosters(source),new JdbcSourceDayRoster(source,clock),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(source),manager),manager,clock);
    }
}
