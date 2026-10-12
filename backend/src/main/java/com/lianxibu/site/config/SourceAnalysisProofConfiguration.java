package com.lianxibu.site.config;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Proof readers remain installed when separate preparation and worker switches are off. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"},havingValue="true")
public class SourceAnalysisProofConfiguration {
    @Bean SourceAnalysisProofGraph sourceAnalysisProofGraph(DataSource source,PlatformTransactionManager tx,ObjectProvider<OperationalInputGate> gates){
        return new SourceAnalysisProofGraph(source,tx,Clock.systemUTC(),()->{var gate=gates.getIfAvailable();return gate!=null&&gate.preparationAllowed();});
    }
}
