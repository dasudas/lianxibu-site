package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold complete report input reader. Writable preparation needs separate native admission. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class ReportInputCaptureConfiguration {
    @Bean public ReportInputCaptureService reportInputCapture(DailyReportInputComponentService daily,ReportPeriodInputComponentService period,PlatformTransactionManager tx){return new ReportInputCaptureService(daily,period,tx);}
}
