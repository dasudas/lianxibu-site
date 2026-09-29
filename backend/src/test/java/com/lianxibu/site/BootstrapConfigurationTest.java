package com.lianxibu.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SITE_DB_URL=jdbc:mysql://127.0.0.1:9/lianxibu",
        "SITE_DB_USER=fixture",
        "SITE_DB_PASSWORD=fixture"
})
class BootstrapConfigurationTest {
    @Autowired HikariDataSource dataSource;
    @Autowired Environment environment;
    @Autowired ThreadPoolTaskExecutor aiExecutor;
    @Autowired ThreadPoolTaskScheduler scheduleExecutor;
    @LocalServerPort int port;

    @Test
    void runtimeLimitsAndDisabledModesAreFixed() {
        assertEquals(5, dataSource.getMaximumPoolSize());
        assertEquals(0, dataSource.getMinimumIdle());
        assertEquals(24, environment.getProperty("server.tomcat.threads.max", Integer.class));
        assertEquals(1, aiExecutor.getCorePoolSize());
        assertEquals(1, aiExecutor.getMaxPoolSize());
        assertEquals(1, scheduleExecutor.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertFalse(environment.getProperty("site.execution.enabled", Boolean.class));
        assertFalse(environment.getProperty("site.scheduler.enabled", Boolean.class));
    }

    @Test
    void bootstrapRejectsAllHttpRequests() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(403, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
