package com.lianxibu.site.controller.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobsControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final JobsService.Coverage COVERAGE = new JobsService.Coverage("complete",
            List.of(), List.of(), List.of());
    private static final JobsService.Job JOB = new JobsService.Job("fixture-job", "logical_task", 1,
            "analysis", 2, "pending", null, null, null, null, null, null, null, COVERAGE,
            null, 0, null, 0, false, "free", 0, null, null, null, null, List.of(), List.of());
    private static final JobsService.Alert ALERT = new JobsService.Alert("fixture-alert", "fixture-rule",
            "fixture-resource", 0, "warning", "active", Instant.parse("2026-09-27T00:00:00Z"),
            Instant.parse("2026-09-27T00:00:00Z"), "1", "2", false, null);
    private static final JobsService.ActionResult ACTION = new JobsService.ActionResult("fixture-operation",
            new JobsService.ResourceRef("job", "fixture-job", null), 2, null, null, null, List.of(), null);
    private IdentityService identity;
    private JobsService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(JobsService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<JobsService> jobsProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(jobsProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        when(identity.authorizeWrite("fixture-session","fixture-csrf",ORIGIN)).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new JobsController(identityProvider, jobsProvider)).build();
    }

    @Test
    void incorrectWriteAuthorizationCannotCreateOrDisposeJobs() throws Exception {
        when(identity.authorizeWrite("fixture-session","fixture-csrf",ORIGIN))
                .thenThrow(new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null));
        mvc.perform(write(post("/admin/jobs"),"{}" )).andExpect(status().isForbidden());
        mvc.perform(write(post("/admin/jobs/fixture/actions"),"{}" )).andExpect(status().isForbidden());
        org.mockito.Mockito.doReturn(new Actor("fixture-user","user")).when(identity).authorizeWrite("fixture-session","fixture-csrf",ORIGIN);
        mvc.perform(write(post("/admin/jobs/fixture/actions"),"{}" )).andExpect(status().isForbidden());
        verify(service,never()).create(any(),any(),any(),any(),any());verify(service,never()).action(any(),any(),any(),any(),any(),any());
    }

    @Test
    void fourOperationsAndAlertBranchMapToService() throws Exception {
        when(service.list(OWNER, "logical_task", "analysis", 2, 10))
                .thenReturn(new JobsService.JobPage(List.of(JOB), 2, 10, 1));
        mvc.perform(auth(get("/admin/jobs?record_kind=logical_task&target_type=analysis&page=2&page_size=10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].record_kind").value("logical_task"))
                .andExpect(jsonPath("$.data.items[0].coverage.state").value("complete"));
        when(service.list(OWNER, "disposition", "ops_rule", 1, 20))
                .thenReturn(new JobsService.AlertPage(List.of(ALERT), 1, 20, 1));
        mvc.perform(auth(get("/admin/jobs?record_kind=disposition&target_type=ops_rule")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].alert_id").value("fixture-alert"))
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist());
        when(service.get(OWNER, "fixture-job")).thenReturn(JOB);
        mvc.perform(auth(get("/admin/jobs/fixture-job"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot_state").value("free"));

        when(service.create(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(JOB);
        mvc.perform(write(post("/admin/jobs"), "{\"task_type\":\"analysis\",\"target\":{"
                        + "\"kind\":\"entity\",\"id\":\"fixture-entity\",\"package_id\":null},"
                        + "\"expected_revision\":0,\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("fixture-job"));
        verify(service).create(eq(OWNER), eq(new JobsService.JobCreate("analysis",
                        new JobsService.ResourceRef("entity", "fixture-entity", null), 0, "fixture")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
        when(service.action(eq(OWNER), eq("fixture-job"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"cancel\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operation_ref").value("fixture-operation"));
    }

    @Test
    void actionBranchesRequireTheirDistinctFields() throws Exception {
        when(service.action(eq(OWNER), eq("fixture-job"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"close_unresolved\","
                        + "\"reason\":\"fixture\",\"attempt_id\":\"fixture-attempt\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"apply_result\","
                        + "\"reason\":\"fixture\",\"expected_content_revision\":2,"
                        + "\"result_hash\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"resolve_external\","
                        + "\"reason\":\"fixture\",\"attempt_id\":\"fixture-attempt\","
                        + "\"external_state\":\"failed\",\"evidence_refs\":[\"fixture-evidence\"],"
                        + "\"result\":null,\"usage\":null}"))
                .andExpect(status().isOk());
        verify(service).action(eq(OWNER), eq("fixture-job"),
                eq(new JobsService.JobAction(1, "resolve_external", "fixture", "fixture-attempt",
                        null, null, "failed", List.of("fixture-evidence"), null, null)),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void rejectsUnauthenticatedWrongRoleMalformedAndIncompleteRequests() throws Exception {
        mvc.perform(get("/admin/jobs")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(auth(get("/admin/jobs"))).andExpect(status().isForbidden());
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc.perform(auth(get("/admin/jobs?page=1&page=2"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/admin/jobs?record_kind=unknown"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(get("/admin/jobs/" + "x".repeat(65)))).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/jobs"), "{\"task_type\":\"a\",\"task_type\":\"b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"apply_result\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/jobs/fixture-job/actions"),
                        "{\"expected_revision\":1,\"action\":\"resolve_external\","
                        + "\"reason\":\"fixture\",\"attempt_id\":\"fixture-attempt\","
                        + "\"external_state\":\"succeeded\",\"evidence_refs\":[\"fixture-evidence\"],"
                        + "\"result\":null,\"usage\":null}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(post("/admin/jobs").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any(), any(), any(), any(), any());
        verify(service, never()).action(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mismatchedPageBranchAndAbsentServiceFailClosed() throws Exception {
        when(service.list(OWNER, "disposition", "ops_rule", 1, 20))
                .thenReturn(new JobsService.JobPage(List.of(JOB), 1, 20, 1));
        mvc.perform(auth(get("/admin/jobs?record_kind=disposition&target_type=ops_rule")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        mvc.perform(auth(get("/admin/jobs/fixture-job"))).andExpect(status().isServiceUnavailable());
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"));
    }
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token", "fixture-csrf")
                .header("Origin", ORIGIN).header("Idempotency-Key", "fixture-key").content(body);
    }
}
