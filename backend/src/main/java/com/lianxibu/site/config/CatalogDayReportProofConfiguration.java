package com.lianxibu.site.config;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import com.lianxibu.site.service.articles.EvidenceValidityService;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold opt-in routing. Native day graphs are mandatory; no empty or current-only fallback. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class CatalogDayReportProofConfiguration {
    public static final class Components {
        private final Map<String,CatalogDayReportComponentService> components;
        private Components(Map<String,CatalogDayReportComponentService> components){
            if(!components.keySet().equals(Set.copyOf(CatalogSourceProofConfiguration.CATEGORIES))||components.values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("catalog report components incomplete");this.components=Map.copyOf(components);
        }
        public CatalogDayReportComponentService component(String category){var value=components.get(category);if(value==null)throw new IllegalArgumentException("unsupported catalog report category");return value;}
        public Map<String,CatalogDayReportComponentService> byCategory(){return components;}
        @Override public String toString(){return "CatalogDayReportComponents[redacted]";}
    }
    @Bean public Components catalogDayReportComponents(DataSource source,PlatformTransactionManager tx,CatalogDayAnalysisProofConfiguration.Graphs graphs){return assemble(source,tx,Clock.systemUTC(),graphs);}
    @Bean public CatalogDayReportAggregateService catalogDayReportAggregate(Components components,PlatformTransactionManager tx){return new CatalogDayReportAggregateService(components.byCategory(),tx);}
    public static Components assemble(DataSource source,PlatformTransactionManager tx,Clock clock,CatalogDayAnalysisProofConfiguration.Graphs graphs){
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);Objects.requireNonNull(graphs);
        String reader="catalog-report-reader";var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var contents=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        var writer=new ContentReviewWriteService(new JdbcReviewPublication(source),new GeneratedContentService(new JdbcGeneratedContents(source),clock),clock);
        var ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,reader);
        var summary=new ContentValiditySummaryService(versions,ledger);
        CatalogDayReportComponentService.VersionEligibility eligible=version->{
            var validity=summary.read(version);var current=contents.administrativeVersion(version.contentKind(),version.contentId()).orElseThrow(()->new IllegalStateException("catalog report content state unavailable"));
            return new CatalogDayReportComponentService.Validity(validity.effectiveValid(),!Set.of("draft","published").contains(current.state()));
        };
        var components=new LinkedHashMap<String,CatalogDayReportComponentService>();
        for(String category:CatalogSourceProofConfiguration.CATEGORIES){
            var graph=graphs.graph(category);
            components.put(category,new CatalogDayReportComponentService(graph.evidence(),graph.plans(),new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(source),category),graph.reservations(),new CatalogDayAnalysisCompletionService(source,tx,clock,category),eligible,tx,category));
        }
        return new Components(components);
    }
}
