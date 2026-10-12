package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSourceDayDedupClosures;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Assembles original day authority; consumers and worker activation are explicit. */
@Configuration(proxyBeanMethods=false)
@Import({SourceDayNewsDedupConfiguration.class,SourceDayRunClaimAdmissionConfiguration.class})
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayNewsValuePermitConfiguration {
    @Bean public SourceDayNewsValuePermitService sourceDayNewsValuePermits(DataSource source,PlatformTransactionManager manager,SourceDayNewsDedupBarrierService barriers,SourceDayRunClaimAdmissionService registrations,SourceAnalysisProofGraph graph){
        return new SourceDayNewsValuePermitService(barriers,registrations,new JdbcSourceDayDedupClosures(source),graph.dedupProofs(),graph.dedupObserver(),graph.dependencies(),graph.dedupPermits(),manager);
    }
}
