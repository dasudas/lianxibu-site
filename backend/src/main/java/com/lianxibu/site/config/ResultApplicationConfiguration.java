package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.AttemptOutcomeService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Enabling result assembly does not claim or send an external request. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"},havingValue="true")
@Import({SourceAnalysisProofConfiguration.class,CatalogDayAnalysisProofConfiguration.class})
public class ResultApplicationConfiguration {
    @Bean ResultApplicationFactory resultApplications(DataSource source,PlatformTransactionManager tx,SourceAnalysisProofGraph graph,ObjectProvider<CatalogDayAnalysisProofConfiguration.Graphs> catalogs,ObjectProvider<com.lianxibu.site.service.jobs.ReportDispatchPermitService> reports,@Qualifier("resultApplicationClock") ObjectProvider<Clock> clocks,Environment flags){var clock=clocks.getIfAvailable(Clock::systemUTC);if(flags.getProperty("site.execution.report-preparation.enabled",Boolean.class,false))return new ResultApplicationFactory(source,tx,clock,graph.dependencies(),graph.dedupPermits(),catalogs.getObject().resultPermissions(),new com.lianxibu.site.repository.jobs.JdbcEntityHeatSnapshots(source),reports.getObject());return flags.getProperty("site.sources.day-roster.enabled",Boolean.class,false)?new ResultApplicationFactory(source,tx,clock,graph.dependencies(),graph.dedupPermits(),catalogs.getObject().resultPermissions(),new com.lianxibu.site.repository.jobs.JdbcEntityHeatSnapshots(source)):new ResultApplicationFactory(source,tx,clock,graph.dependencies(),graph.dedupPermits());}
}
