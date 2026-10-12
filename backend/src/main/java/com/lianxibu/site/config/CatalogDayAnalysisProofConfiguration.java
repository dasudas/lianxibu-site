package com.lianxibu.site.config;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Original day proof routing is available independently of background preparation and paid dispatch. */
@Configuration(proxyBeanMethods=false)
@Import(CatalogSourceProofConfiguration.class)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class CatalogDayAnalysisProofConfiguration {
    public record Graphs(Map<String,CatalogDayAnalysisProofGraph> byCategory) {
        public Graphs {
            Objects.requireNonNull(byCategory);
            if(!byCategory.keySet().equals(Set.copyOf(CatalogSourceProofConfiguration.CATEGORIES)))throw new IllegalArgumentException("catalog day proof categories incomplete");
            for(var entry:byCategory.entrySet())if(entry.getValue()==null||!entry.getKey().equals(entry.getValue().category()))throw new IllegalArgumentException("catalog day proof category mismatch");
            byCategory=Map.copyOf(byCategory);
        }
        public CatalogDayAnalysisProofGraph graph(String category){var graph=byCategory.get(category);if(graph==null)throw new IllegalArgumentException("unsupported catalog category");return graph;}
        public Map<String,CatalogDayAnalysisDispatchPermitService> dispatchPermissions(){var result=new LinkedHashMap<String,CatalogDayAnalysisDispatchPermitService>();byCategory.forEach((category,graph)->result.put(category,graph.dispatch()));return Map.copyOf(result);}
        public Map<String,CatalogDayAnalysisResultPermitService> resultPermissions(){var result=new LinkedHashMap<String,CatalogDayAnalysisResultPermitService>();byCategory.forEach((category,graph)->result.put(category,graph.result()));return Map.copyOf(result);}
        @Override public String toString(){return "CatalogDayAnalysisProofGraphs[redacted]";}
    }
    @Bean public Graphs catalogDayAnalysisProofGraphs(DataSource source,PlatformTransactionManager tx,
            SourceDayCohortSealService cohorts,SourceDayRunRegistration.Api runs,CatalogSourceProofConfiguration.Graphs sources,
            SettingsUpdateService settings,ObjectProvider<OperationalInputGate> gates){
        Objects.requireNonNull(gates);
        return assemble(source,tx,Clock.systemUTC(),cohorts,runs,sources,settings,()->{var gate=gates.getIfAvailable();return gate!=null&&gate.preparationAllowed();});
    }
    public static Graphs assemble(DataSource source,PlatformTransactionManager tx,Clock clock,
            SourceDayCohortSealService cohorts,SourceDayRunRegistration.Api runs,CatalogSourceProofConfiguration.Graphs sources,
            SettingsUpdateService settings,BooleanSupplier allowed){
        Objects.requireNonNull(sources);Objects.requireNonNull(settings);var graphs=new LinkedHashMap<String,CatalogDayAnalysisProofGraph>();
        for(String category:CatalogSourceProofConfiguration.CATEGORIES)graphs.put(category,new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,runs,sources.graph(category),()->{var frozen=settings.freezeForNewInput();return new FrozenGenerationSettings(frozen.settingsRevision(),frozen.followKeywords(),frozen.blockedKeywords());},allowed,category));
        return new Graphs(graphs);
    }
}
