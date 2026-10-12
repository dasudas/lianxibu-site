package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.JdbcCollectorHostMemory;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.CollectorHostMemoryService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.Clock;import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Opt-in independent host-memory writer, using actual native node identity and the original sole writer. Construction does no IO. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.collector-memory.enabled","site.browser.enabled","site.collector.enabled"},havingValue="true")
public class CollectorHostMemoryConfiguration {
    @Bean CollectorHostMemoryService collectorHostMemory(DataSource source,PlatformTransactionManager tx,NodeIdentityService nodes){return create(source,tx,nodes,Clock.systemUTC());}
    public CollectorHostMemoryService create(DataSource source,PlatformTransactionManager tx,NodeIdentityService nodes,Clock clock){return new CollectorHostMemoryService(new JdbcCollectorHostMemory(source),nodes,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);}
}
