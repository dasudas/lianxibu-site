package com.lianxibu.site.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** The collector controller performs current node authorization on every operation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.collector.enabled", havingValue = "true")
public class CollectorSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain collectorSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/collector/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
