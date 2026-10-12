package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.records.RecordReadService;
import javax.sql.DataSource;
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
@ConditionalOnProperty(name={"site.browser.enabled","site.articles.enabled"},havingValue="true")
public class ArticlesRuntimeConfiguration {
    @Bean ArticlesService articlesRuntime(DataSource source,PlatformTransactionManager tx){
        var base=new PrivateArticlesService(new JdbcPrivateArticleReads(source),new JdbcArticleReads(source),new RecordReadService(new JdbcRecordReads(source),tx),tx);
        return new ArticleEntityProjectionService(base,new JdbcArticleEntityReads(source),tx);
    }
    @Bean @Order(4) SecurityFilterChain articlesSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/private/articles","/private/articles/*","/admin/entities","/admin/entities/*")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(r->r.anyRequest().permitAll());
        return http.build();
    }
}
