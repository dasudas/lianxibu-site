package com.lianxibu.site.config;

import com.lianxibu.site.domain.identity.NodeTokens;
import com.lianxibu.site.repository.identity.JdbcNodeStore;
import com.lianxibu.site.service.identity.NodeIdentityService;
import java.time.Clock;
import java.util.Base64;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Install collector identity only after the host supplies an independent secret. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.collector.enabled", havingValue = "true")
public class CollectorIdentityConfiguration {
    @Bean
    NodeIdentityService collectorNodeIdentity(DataSource dataSource,
                                              @Value("${SITE_NODE_TOKEN_SECRET_B64:}") String encoded) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("collector node secret unavailable");
        }
        if (secret.length < 32) throw new IllegalStateException("collector node secret unavailable");
        return new NodeIdentityService(new JdbcNodeStore(dataSource),
                new NodeTokens(secret), Clock.systemUTC());
    }
}
