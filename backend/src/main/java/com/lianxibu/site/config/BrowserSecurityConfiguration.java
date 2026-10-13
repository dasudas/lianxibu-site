package com.lianxibu.site.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** IdentityController delegates cookie, origin, CSRF and role validation to the identity service. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.browser.enabled", havingValue = "true")
public class BrowserSecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain browserIdentitySecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/session", "/admin/content", "/admin/preferences", "/admin/collector-nodes", "/admin/collector-nodes/*/actions", "/admin/jobs", "/admin/jobs/*", "/admin/jobs/*/actions", "/admin/jobs/*/ad-hoc", "/admin/jobs/*/schedule", "/admin/jobs/*/result-comparison", "/private/article-records", "/private/records", "/private/records/*/*")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(request -> request.anyRequest().permitAll());
        return http.build();
    }
}
