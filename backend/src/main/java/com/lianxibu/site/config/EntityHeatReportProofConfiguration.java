package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold native cutoff and original source proof consumers. Scheduling remains in the observation runtime. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class EntityHeatReportProofConfiguration {
    @Bean public EntityHeatCutoffProofService entityHeatCutoffProof(DataSource source,EntityHeatEventProofService events,EntityHeatEventAvailabilityService first,PlatformTransactionManager tx){return new EntityHeatCutoffProofService(new JdbcEntityHeatCutoffUniverse(source),events,first,tx);}
    @Bean public EntityHeatSourceOriginService entityHeatSourceOrigin(DataSource source,EntityHeatEventProofService events,NewsDayReportComponentService days,SourceDayCohortSealService cohorts,SourceAnalysisProofGraph graph,PlatformTransactionManager tx){return new EntityHeatSourceOriginService(new JdbcEntityHeatOriginHeaders(source),events,days,cohorts,graph.plans(),new SourceDedupResultProofService(new JdbcSourceDedupResults(source),graph.plans(),graph.universes()),tx);}
    @Bean public EntityHeatReportComponentService entityHeatReportComponent(DataSource source,EntityHeatCutoffProofService counts,EntityHeatSourceOriginService sources,PlatformTransactionManager tx){return new EntityHeatReportComponentService(counts,sources,new JdbcEntityHeatOriginHeaders(source),tx);}
}
