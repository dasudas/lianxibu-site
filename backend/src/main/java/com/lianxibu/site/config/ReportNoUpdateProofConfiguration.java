package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Cold native committed no-update proof, independent of the capture/preparation dependency graph. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class ReportNoUpdateProofConfiguration {
 @Bean public ReportNoUpdateProofService reportNoUpdateProofs(DataSource source,PlatformTransactionManager tx){return new ReportNoUpdateProofService(source,new ReportPreparedInputService(source,tx),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,Clock.systemUTC());}
}
