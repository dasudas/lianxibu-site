package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceDayRunRegistrations;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.SourceDayRunRegistrationService;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayRunRegistrationConfiguration {
    @Bean public SourceDayRunRegistrationService sourceDayRunRegistration(DataSource source,PlatformTransactionManager manager,OperationalInputGate gate){return create(source,manager,gate,Clock.systemUTC());}
    public SourceDayRunRegistrationService create(DataSource source,PlatformTransactionManager manager,OperationalInputGate gate,Clock clock){
        Objects.requireNonNull(gate);
        return new SourceDayRunRegistrationService(new JdbcSourceDayRunRegistrations(source),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),manager),manager,clock,gate::newRunAllowed);
    }
}
