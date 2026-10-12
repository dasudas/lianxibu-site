package com.lianxibu.site.config;
import com.lianxibu.site.repository.sources.JdbcSourceContractSnapshots;import com.lianxibu.site.repository.sources.JdbcSourceAdmission;import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.sources.SourceContractSnapshotService;import com.lianxibu.site.service.identity.NodeIdentityService;import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.Clock;import javax.sql.DataSource;import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.context.annotation.*;
/** Explicit native health submission service; construction performs no SQL, parsing, or file work. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.source-contract.enabled","site.browser.enabled","site.collector.enabled","site.sources.enabled"},havingValue="true")
public class SourceContractTelemetryConfiguration {
 @Bean SourceContractSnapshotService sourceContractSnapshotService(DataSource source,PlatformTransactionManager tx,NodeIdentityService nodes){return new SourceContractSnapshotService(new JdbcSourceContractSnapshots(source),new JdbcSourceAdmission(source),nodes,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),Clock.systemUTC());}
}
