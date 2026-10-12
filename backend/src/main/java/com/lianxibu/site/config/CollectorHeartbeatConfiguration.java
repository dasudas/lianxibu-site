package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.JdbcCollectorHeartbeats;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.CollectorHeartbeatService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.Clock;import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Every enabled collector gateway requires the actual native heartbeat writer. Construction does no IO. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="site.collector.enabled",havingValue="true")
public class CollectorHeartbeatConfiguration {
    @Bean CollectorHeartbeatService collectorHeartbeats(DataSource source,PlatformTransactionManager tx,NodeIdentityService nodes){return create(source,tx,nodes,Clock.systemUTC());}
    public CollectorHeartbeatService create(DataSource source,PlatformTransactionManager tx,NodeIdentityService nodes,Clock clock){return new CollectorHeartbeatService(new JdbcCollectorHeartbeats(source),nodes,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);}
}
