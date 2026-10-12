package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold native daily input proof consumer. Report preparation and period inputs require separate runtime wiring. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class DailyReportInputProofConfiguration {
    @Bean public DailyReportInputComponentService dailyReportInputComponent(NewsDayReportComponentService news,CatalogDayReportAggregateService catalog,EntityHeatReportComponentService heat,PlatformTransactionManager tx){return new DailyReportInputComponentService(news,catalog,heat,tx);}
}
