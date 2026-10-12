package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.learning.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.learning.*;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.learning.enabled"},havingValue="true")
public class LearningRuntimeConfiguration {
    @Bean LearningRuntimeService learningRuntime(DataSource source,PlatformTransactionManager tx,
            @Value("${SITE_LEARNING_IMPORT_DIRECTORY}") String directory){
        Path path=Path.of(directory);if(!path.isAbsolute())throw new IllegalArgumentException("absolute learning import directory required");
        return new LearningRuntimeService(new LearningReadService(new JdbcLearningReads(source)),new JdbcLearningAnswers(source),
                new JdbcLearningPackages(source),new JdbcLearningImports(source),new FileLearningImportStages(path),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,Clock.systemUTC());
    }
    @Bean OwnerPackageReadService ownerPackages(LearningRuntimeService learning){return new OwnerPackageReadService(learning::ownerPackage);}
    @Bean OwnerAnswerReadService ownerAnswers(LearningRuntimeService learning){return new OwnerAnswerReadService(learning::ownerAnswer);}
    @Bean @Order(3) SecurityFilterChain learningSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/learning/**","/admin/learning/**")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(r->r.anyRequest().permitAll());return http.build();
    }
}
