package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SITE_DB_URL=jdbc:mysql://127.0.0.1:9/lianxibu",
        "SITE_DB_USER=fixture",
        "SITE_DB_PASSWORD=fixture",
        "SITE_NODE_TOKEN_SECRET_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "site.collector.enabled=true"
})
class CollectorSecurityConfigurationTest {
    @LocalServerPort int port;

    @Test
    void enabledCollectorRequiresBearerWhileOtherRoutesStayDenied() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String root = "http://127.0.0.1:" + port;
            var collector = HttpRequest.newBuilder(URI.create(root + "/api/collector/v1/config")).GET().build();
            var other = HttpRequest.newBuilder(URI.create(root + "/actuator/health")).GET().build();
            assertEquals(401, client.send(collector, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(403, client.send(other, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void missingOrShortNodeSecretFailsClosed() {
        var source = new JdbcDataSource();
        var configuration = new CollectorIdentityConfiguration();
        assertThrows(IllegalStateException.class, () -> configuration.collectorNodeIdentity(source, ""));
        assertThrows(IllegalStateException.class, () -> configuration.collectorNodeIdentity(source, "%%%"));
        assertThrows(IllegalStateException.class, () -> configuration.collectorNodeIdentity(source, "eHg="));
    }
}
