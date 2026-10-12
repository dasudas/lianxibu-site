package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.JdbcNewsReportLeaves;
import com.lianxibu.site.repository.jobs.JdbcSourceDayDedupClosures;
import com.lianxibu.site.service.articles.NewsReportLeafEvidenceService;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold opt-in original NEWS day report providers. Does not activate a scheduler or a model worker. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class NewsDayReportProofConfiguration {
    @Bean public SourceValueReportCompletionService sourceValueReportCompletion(SourceAnalysisProofGraph graph,PlatformTransactionManager tx){return new SourceValueReportCompletionService(graph.plans(),graph.valueProofs(),graph.finalization(),graph.finalObserver(),tx);}
    @Bean public NewsSourceReportComponentService newsSourceReportComponent(DataSource source,PlatformTransactionManager tx,SourceValueReportCompletionService completions){return new NewsSourceReportComponentService(completions,new NewsReportLeafEvidenceService(new JdbcNewsReportLeaves(source)),tx);}
    @Bean public NewsDayReportComponentService newsDayReportComponent(DataSource source,PlatformTransactionManager tx,SourceDayCohortSealService cohorts,SourceDayNewsDedupBarrierService barriers,SourceDayRunClaimAdmissionService registrations,SourceAnalysisProofGraph graph,SourceDayNewsValuePermitService permits,SourceValueReportCompletionService completions,NewsSourceReportComponentService components){return new NewsDayReportComponentService(cohorts,barriers,new JdbcSourceDayDedupClosures(source),registrations,graph.plans(),graph.dedupProofs(),graph.dedupObserver(),graph.handoffReceipts(),permits,completions,components,tx);}
}
