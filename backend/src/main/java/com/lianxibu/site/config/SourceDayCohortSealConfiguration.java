package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.jobs.JdbcSourceDayCohortSeals;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import com.lianxibu.site.service.jobs.SourceDayRosterRegistrationService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayCohortSealConfiguration {
    @Bean public SourceDayCohortSealService sourceDayCohortSeal(DataSource source,PlatformTransactionManager manager,SourceDayRosterRegistrationService registration){return assemble(source,manager,Clock.systemUTC(),registration);}
    public SourceDayCohortSealService create(DataSource source,PlatformTransactionManager manager,Clock clock){return assemble(source,manager,clock,new SourceDayRosterRegistrationConfiguration().create(source,manager,clock));}
    private SourceDayCohortSealService assemble(DataSource source,PlatformTransactionManager manager,Clock clock,SourceDayRosterRegistrationService registration){
        return new SourceDayCohortSealService(registration,new JdbcSourceDayRosters(source),new JdbcSourceDayCohortSeals(source),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),manager),manager,clock);
    }
}
