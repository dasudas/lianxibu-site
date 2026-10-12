package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Installs database proofs only. Worker lifecycle activation is a separate concern. */
@Configuration(proxyBeanMethods=false)
@Import({SourceAnalysisProofConfiguration.class,SourceDayNewsValuePermitConfiguration.class,CatalogDayAnalysisProofConfiguration.class})
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"},havingValue="true")
public class DispatchReadinessConfiguration {
    @Bean DispatchReadinessService dispatchReadiness(DataSource source,PlatformTransactionManager tx,ResultApplicationFactory results,SourceAnalysisProofGraph graph,ObjectProvider<SourceDayNewsValuePermitService> days,ObjectProvider<CatalogDayAnalysisProofConfiguration.Graphs> catalogs,ObjectProvider<ReportDispatchPermitService> reports,Environment flags){
        var inputs=new JdbcFrozenInputs(source);
        return new DispatchReadinessService(new JdbcDispatchInputs(source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(source)),new GenerationLineageService(new JdbcGenerationLineage(source),Clock.systemUTC(),"analysis-worker",graph.births()::read),new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences()),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)),results.referenceProof(),graph.collections()::read,new SourcePartitionProofService(graph.plans()),true,graph.dependencies(),graph.dedupPermits(),tx,flags.getProperty("site.sources.day-roster.enabled",Boolean.class,false)?days.getObject():null,flags.getProperty("site.sources.day-roster.enabled",Boolean.class,false)?catalogs.getObject().dispatchPermissions():null,flags.getProperty("site.execution.report-preparation.enabled",Boolean.class,false)?reports.getObject():null);
    }
}
