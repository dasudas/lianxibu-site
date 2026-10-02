package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.JdbcJobSchedules;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.JobScheduleReadService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Registers a read-only provider; constructing it performs no queries or activation. */
@Configuration(proxyBeanMethods=false)
public class JobScheduleReadConfiguration {
    @Bean JobScheduleReadService jobSchedules(DataSource source,PlatformTransactionManager tx){return new JobScheduleReadService(new JdbcJobSchedules(source),new JdbcFrozenInputs(source),tx,Clock.systemUTC());}
}
