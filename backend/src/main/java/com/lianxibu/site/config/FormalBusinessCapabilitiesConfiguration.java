package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.articles.ArticlesService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.learning.LearningService;
import com.lianxibu.site.service.sources.SourcesService;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** Claim only after all formal business modes and their actual providers compose together.
 * Operations, source admission, performance and release qualification remain independent. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.collector.enabled","site.content.enabled","site.content.availability.enabled","site.content.propagation.enabled","site.articles.enabled","site.sources.enabled","site.sources.day-roster.enabled","site.execution.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled","site.execution.report-preparation.enabled","site.execution.report-runtime.enabled","site.execution.ad-hoc.enabled","site.execution.worker.enabled","site.learning.enabled","site.scheduler.enabled"},havingValue="true")
public class FormalBusinessCapabilitiesConfiguration {
    @Bean RuntimeCapabilities formalBusinessCapabilities(PublicReadService home,IdentityService identity,
            ContentService content,ArticlesService articles,LearningService learning,SourcesService sources,
            JobsService jobs,SettingsUpdateService settings,WorkerRuntimeCycle worker,OwnerReportOperations reports,
            DailyReportInputComponentService daily,ReportPeriodInputComponentService period,
            SourceAnalysisProofGraph news,CatalogSourceProofConfiguration.Graphs catalog,
            CatalogDayAnalysisProofConfiguration.Graphs analysis) {
        return new RuntimeCapabilities();
    }
}
