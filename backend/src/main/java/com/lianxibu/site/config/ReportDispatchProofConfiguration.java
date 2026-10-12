package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Cold original report dispatch/result permissions, with mandatory complete native proof readers. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class ReportDispatchProofConfiguration {
 @Bean public ReportDispatchPermitService reportDispatchPermits(DataSource source,ReportPreparedInputService prepared,ReportNoUpdateProofService noUpdates,PlatformTransactionManager tx){return new ReportDispatchPermitService(source,prepared,noUpdates,tx);}
}
