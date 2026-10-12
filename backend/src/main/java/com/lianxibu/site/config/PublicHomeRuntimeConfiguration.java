package com.lianxibu.site.config;

import com.lianxibu.site.repository.settings.JdbcSettingsReads;
import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.jobs.PublicHomeReadService;
import com.lianxibu.site.service.jobs.PublicReadService;
import com.lianxibu.site.service.learning.LearningService;
import com.lianxibu.site.service.settings.SettingsReadService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.learning.enabled"},havingValue="true")
public class PublicHomeRuntimeConfiguration {
    @Bean PublicReadService publicHome(ContentService contents,LearningService learning,DataSource source,PlatformTransactionManager tx) {
        return new PublicHomeReadService(contents,learning,new SettingsReadService(new JdbcSettingsReads(source),tx),tx);
    }
    @Bean @Order(7) SecurityFilterChain publicHomeSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/home","/capabilities")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(r->r.requestMatchers(HttpMethod.GET,"/home","/capabilities").permitAll().anyRequest().denyAll());
        return http.build();
    }
}
