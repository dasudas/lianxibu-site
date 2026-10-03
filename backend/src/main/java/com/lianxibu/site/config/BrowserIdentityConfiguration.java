package com.lianxibu.site.config;

import com.lianxibu.site.repository.identity.JdbcBrowserSessionStore;
import com.lianxibu.site.service.identity.Argon2Passwords;
import com.lianxibu.site.service.identity.BrowserIdentityAdapter;
import com.lianxibu.site.service.identity.BrowserSessionService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit host identity mapping; no role is inferred from a username or a request. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.browser.enabled", havingValue = "true")
public class BrowserIdentityConfiguration {
    @Bean
    BrowserSessionService browserSessions(DataSource source,
            @Value("${SITE_BROWSER_ORIGIN:}") String origin,
            @Value("${SITE_OWNER_IDS:}") String owners,
            @Value("${SITE_USER_IDS:}") String users) {
        String trustedOrigin = origin(origin);
        Set<String> ownerIds = ids(owners), userIds = ids(users);
        if (ownerIds.isEmpty() || ownerIds.stream().anyMatch(userIds::contains))
            throw new IllegalStateException("explicit non-overlapping browser roles required");
        return new BrowserSessionService(new JdbcBrowserSessionStore(new JdbcTemplate(source)),
                new Argon2Passwords(), id -> ownerIds.contains(id) ? "owner" : userIds.contains(id) ? "user" : null,
                Set.of(trustedOrigin), Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    IdentityService browserIdentity(BrowserSessionService browser, ObjectProvider<NodeIdentityService> nodes) {
        return new BrowserIdentityAdapter(browser, nodes.getIfAvailable());
    }

    static String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || !value.equals(uri.toASCIIString()))
                throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("canonical HTTPS browser origin required");
        }
    }

    static Set<String> ids(String value) {
        if (value.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String id : value.split(",", -1)) {
            if (!id.matches("[A-Za-z0-9._:-]{1,64}") || !result.add(id))
                throw new IllegalStateException("invalid browser role mapping");
        }
        return Set.copyOf(result);
    }
}
