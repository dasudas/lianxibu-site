package com.lianxibu.site.config;

import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Permanent read-only result comparison; construction performs no I/O. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.execution.enabled"},havingValue="true")
public class JobResultComparisonRuntimeConfiguration {
    @Bean JobResultComparisonReadService jobResultComparisonReads(DataSource source,PlatformTransactionManager tx){
        var content=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        var applications=new ResultApplicationFactory(source,tx,Clock.systemUTC());
        return new JobResultComparisonReadService(new OwnerJobResultComparisonStore(source,tx,content,applications.referenceProof()));
    }
}
