package com.lianxibu.site.config;

import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Original day proof assembly only; P2 preparation and sending need their own proof consumers. */
@Configuration(proxyBeanMethods=false)
@Import(SourceAnalysisProofConfiguration.class)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayNewsDedupConfiguration {
    @Bean public SourceDayNewsDedupProgressService sourceDayNewsDedupProgress(PlatformTransactionManager manager,SourceDayRunRegistration.Api runs,SourceAnalysisProofGraph graph){return createProgress(manager,runs,graph);}
    @Bean public SourceDayNewsDedupBarrierService sourceDayNewsDedupBarrier(DataSource source,PlatformTransactionManager manager,SourceDayCohortSealService cohorts,SourceDayNewsDedupProgressService progress){return createBarrier(source,manager,cohorts,progress,Clock.systemUTC());}
    public SourceDayNewsDedupProgressService createProgress(PlatformTransactionManager manager,SourceDayRunRegistration.Api runs,SourceAnalysisProofGraph graph){
        java.util.Objects.requireNonNull(graph);return new SourceDayNewsDedupProgressService(runs,graph.plans(),graph.dedupProofs(),graph.dedupObserver(),manager);
    }
    public SourceDayNewsDedupBarrierService createBarrier(DataSource source,PlatformTransactionManager manager,SourceDayCohortSealService cohorts,SourceDayNewsDedupProgressService progress,Clock clock){
        return new SourceDayNewsDedupBarrierService(cohorts,progress,new JdbcSourceDayDedupClosures(source),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),manager),manager,clock);
    }
}
