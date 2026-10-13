package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real filter chains and HTTP; explicit fixture ports keep business persistence out of this test. */
@SpringBootTest(classes = JobsBrowserSecurityTest.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "site.browser.enabled=true")
class JobsBrowserSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({BrowserSecurityConfiguration.class, BootstrapSecurityConfiguration.class, JobsController.class})
    static class Fixture { }

    @LocalServerPort int port;
    @MockitoBean IdentityService identity;
    @MockitoBean JobsService jobs;
    final Actor owner = new Actor("fictional-owner", "owner");

    @BeforeEach void setup() {
        reset(identity, jobs);
        when(identity.authenticate("fictional-owner-session")).thenReturn(owner);
        when(identity.authenticate("fictional-member-session")).thenReturn(new Actor("fictional-member", "member"));
        when(jobs.list(owner, null, null, 1, 20)).thenReturn(new JobsService.JobPage(List.of(), 1, 20, 0));
        when(jobs.list(owner, "disposition", "ops_rule", 1, 20)).thenReturn(new JobsService.AlertPage(List.of(), 1, 20, 0));
        when(jobs.get(owner, "fictional-absent")).thenThrow(new JobsService.JobRejected(JobsService.JobRefusal.NOT_FOUND));
    }

    HttpResponse<String> request(String method, String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) builder.header("Cookie", "lianxibu_session=" + cookie);
        if (method.equals("POST")) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        else builder.GET();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void ownerJobsAndAlertsReachTheirOriginalController() throws Exception {
        assertEquals(200, request("GET", "/admin/jobs", "fictional-owner-session").statusCode());
        assertEquals(200, request("GET", "/admin/jobs?record_kind=disposition&target_type=ops_rule", "fictional-owner-session").statusCode());
        verify(jobs).list(owner, null, null, 1, 20);
        verify(jobs).list(owner, "disposition", "ops_rule", 1, 20);
    }

    @Test void ownerDetailKeepsOriginalNotFoundRefusal() throws Exception {
        assertEquals(404, request("GET", "/admin/jobs/fictional-absent", "fictional-owner-session").statusCode());
        verify(jobs).get(owner, "fictional-absent");
    }

    @Test void anonymousAndMemberCannotReadOwnerJobs() throws Exception {
        assertEquals(401, request("GET", "/admin/jobs", null).statusCode());
        assertEquals(403, request("GET", "/admin/jobs", "fictional-member-session").statusCode());
        verifyNoInteractions(jobs);
    }

    @Test void bothWritePathsStillRequireCsrfBeforeBusinessCalls() throws Exception {
        assertEquals(403, request("POST", "/admin/jobs", "fictional-owner-session").statusCode());
        assertEquals(403, request("POST", "/admin/jobs/fictional-job/actions", "fictional-owner-session").statusCode());
        verifyNoInteractions(jobs);
    }

    @Test void unrelatedAndUnlistedNestedPathsRemainDenied() throws Exception {
        assertEquals(403, request("GET", "/actuator/health", null).statusCode());
        assertEquals(403, request("GET", "/admin/jobs/fictional-job/unlisted", "fictional-owner-session").statusCode());
        verifyNoInteractions(jobs);
    }
}
