package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.FrozenGenerationSettings;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Proof providers remain available while catalog preparation and paid dispatch are off. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"},havingValue="true")
public class CatalogSourceProofConfiguration {
    public static final List<String> CATEGORIES=List.of("agent","skill","workflow","model");
    public record Graphs(Map<String,CatalogSourceProofGraph> byCategory) {
        public Graphs {
            Objects.requireNonNull(byCategory);if(!byCategory.keySet().equals(Set.copyOf(CATEGORIES)))throw new IllegalArgumentException("catalog proof categories incomplete");
            for(var entry:byCategory.entrySet())if(entry.getValue()==null||!entry.getKey().equals(entry.getValue().category()))throw new IllegalArgumentException("catalog proof category mismatch");
            byCategory=Map.copyOf(byCategory);
        }
        public CatalogSourceProofGraph graph(String category){var graph=byCategory.get(category);if(graph==null)throw new IllegalArgumentException("unsupported catalog category");return graph;}
        @Override public String toString(){return "CatalogSourceProofGraphs[redacted]";}
    }
    @Bean public Graphs catalogSourceProofGraphs(DataSource source,PlatformTransactionManager tx,SettingsUpdateService settings,ObjectProvider<OperationalInputGate> gates){
        Objects.requireNonNull(settings);Objects.requireNonNull(gates);
        return assemble(source,tx,Clock.systemUTC(),settings,()->{var gate=gates.getIfAvailable();return gate!=null&&gate.preparationAllowed();});
    }
    public static Graphs assemble(DataSource source,PlatformTransactionManager tx,Clock clock,SettingsUpdateService settings,java.util.function.BooleanSupplier allowed){
        Objects.requireNonNull(settings);var graphs=new LinkedHashMap<String,CatalogSourceProofGraph>();
        for(String category:CATEGORIES)graphs.put(category,new CatalogSourceProofGraph(source,tx,clock,()->{var frozen=settings.freezeForNewInput();return new FrozenGenerationSettings(frozen.settingsRevision(),frozen.followKeywords(),frozen.blockedKeywords());},allowed,category));
        return new Graphs(graphs);
    }
}
