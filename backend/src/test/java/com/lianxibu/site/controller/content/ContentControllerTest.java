package com.lianxibu.site.controller.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ContentControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final Instant START = Instant.parse("2026-09-27T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-28T00:00:00Z");
    private static final ContentService.Coverage COVERAGE = new ContentService.Coverage("complete",
            List.of(), List.of(), List.of());
    private static final ContentService.ReportBody REPORT_BODY = new ContentService.ReportBody("Fixture",
            List.of(), List.of(), "daily", START, END, "Asia/Shanghai", null);
    private static final ContentService.InterpretationBody INTERPRETATION_BODY =
            new ContentService.InterpretationBody("Fixture", List.of(), List.of(), "agent", "fixture-entity");
    private static final ContentService.ContentView REPORT = new ContentService.ContentView("fixture-report",
            "report", "Fixture", 1, 1, "published", START, null, null, null, COVERAGE,
            false, List.of(), false, null, null, REPORT_BODY, List.of());
    private static final ContentService.ContentView INTERPRETATION = new ContentService.ContentView(
            "fixture-interpretation", "agent", "Fixture", 1, 1, "published", START, null, null,
            null, COVERAGE, false, List.of(), false, null,
            new ContentService.InterpretationProfile("Fixture", null, List.of(), null, null, List.of(), null, null),
            INTERPRETATION_BODY, List.of());
    private static final ContentService.ActionResult ACTION = new ContentService.ActionResult("fixture-operation",
            new ContentService.ResourceRef("content", "fixture-report", null), 2, null, null,
            null, List.of(), null);
    private static final ContentService.Job JOB = new ContentService.Job("fixture-job", "generation_intent", 0,
            null, 1, "pending", null, null, null, null, null, null, null, COVERAGE,
            null, 0, null, 0, false, "free", 0, null, null, null, null, List.of(), List.of());
    private IdentityService identity;
    private ContentService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(ContentService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<ContentService> contentProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(contentProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new ContentController(identityProvider, contentProvider)).build();
    }

    @Test
    void tenOperationsMapToTypedServiceAndExactBranchFields() throws Exception {
        when(service.reports("daily", 2, 10)).thenReturn(new ContentService.ContentPage(List.of(REPORT), 2, 10, 1));
        mvc.perform(get("/reports?type=daily&page=2&page_size=10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].kind").value("report"))
                .andExpect(jsonPath("$.data.items[0].name").doesNotExist());
        when(service.report("fixture-report")).thenReturn(REPORT);
        mvc.perform(get("/reports/fixture-report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body.period_start").value("2026-09-27T00:00:00.000000Z"));
        when(service.interpretations("agent", 1, 20))
                .thenReturn(new ContentService.ContentPage(List.of(INTERPRETATION), 1, 20, 1));
        mvc.perform(get("/interpretations?kind=agent")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("Fixture"));
        when(service.interpretation("fixture-interpretation")).thenReturn(INTERPRETATION);
        mvc.perform(get("/interpretations/fixture-interpretation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body.entity_id").value("fixture-entity"));

        when(service.adminContent(OWNER, "report", "fixture-report")).thenReturn(REPORT);
        mvc.perform(auth(get("/admin/content/report/fixture-report"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Fixture"));
        when(service.write(eq(OWNER), eq("report"), eq("fixture-report"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(REPORT);
        mvc.perform(write(put("/admin/content/report/fixture-report"),
                        "{\"expected_revision\":1,\"title\":\"Fixture\",\"sections\":[],"
                        + "\"citation_ids\":[],\"type\":\"daily\","
                        + "\"period_start\":\"2026-09-27T00:00:00.000000Z\","
                        + "\"period_end\":\"2026-09-28T00:00:00.000000Z\","
                        + "\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.body.type").value("daily"));
        verify(service).write(eq(OWNER), eq("report"), eq("fixture-report"),
                eq(new ContentService.ContentWrite(1, REPORT_BODY)), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN));

        when(service.action(eq(OWNER), eq("report"), eq("fixture-report"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/content/report/fixture-report/actions"),
                        "{\"expected_revision\":1,\"action\":\"publish\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operation_ref").value("fixture-operation"));
        var version = new ContentService.VersionSummary(new ContentService.ContentVersionKey("content_version",
                "report", "fixture-report", 1, "a".repeat(64), null), "body", 0, true);
        when(service.referencedVersions(OWNER, "report", "fixture-report", 1, 20))
                .thenReturn(new ContentService.VersionPage(List.of(version), 1, 20, 1));
        mvc.perform(auth(get("/admin/content/report/fixture-report/referenced-versions")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].target.body_hash")
                        .value("a".repeat(64)));
        when(service.versionAction(eq(OWNER), eq("report"), eq("fixture-report"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/content/report/fixture-report/version-actions"),
                        "{\"expected_validity_revision\":0,\"action\":\"invalidate_conclusion\","
                        + "\"reason\":\"fixture\",\"target\":{\"kind\":\"content_version\","
                        + "\"content_kind\":\"report\",\"content_id\":\"fixture-report\","
                        + "\"revision\":1,\"body_hash\":\"" + "a".repeat(64)
                        + "\",\"input_job_id\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(2));
        when(service.generationIntent(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(JOB);
        mvc.perform(write(post("/admin/reports/generation-intents"),
                        "{\"kind\":\"daily\",\"period_start\":\"2026-09-27T00:00:00.000000Z\","
                        + "\"period_end\":\"2026-09-28T00:00:00.000000Z\","
                        + "\"timezone\":\"Asia/Shanghai\",\"mode\":\"initial\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.record_kind").value("generation_intent"));
    }

    @Test
    void publicReadsAndAdminOwnershipAreDistinct() throws Exception {
        when(service.report("fixture-report")).thenReturn(REPORT);
        mvc.perform(get("/reports/fixture-report")).andExpect(status().isOk());
        mvc.perform(get("/admin/content/report/fixture-report")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(auth(get("/admin/content/report/fixture-report"))).andExpect(status().isForbidden());
        verify(service, never()).adminContent(any(), any(), any());
    }

    @Test
    void alternateCommandBranchesMapWithoutInventingFields() throws Exception {
        when(service.write(eq(OWNER), eq("agent"), eq("fixture-interpretation"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(INTERPRETATION);
        mvc.perform(write(put("/admin/content/agent/fixture-interpretation"),
                        "{\"expected_revision\":1,\"title\":\"Fixture\",\"sections\":[],"
                        + "\"citation_ids\":[],\"category\":\"agent\",\"entity_id\":\"fixture-entity\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.body.category").value("agent"));
        verify(service).write(eq(OWNER), eq("agent"), eq("fixture-interpretation"),
                eq(new ContentService.ContentWrite(1, INTERPRETATION_BODY)), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN));

        when(service.action(eq(OWNER), eq("report"), eq("fixture-report"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/content/report/fixture-report/actions"),
                        "{\"expected_revision\":1,\"action\":\"withdraw\",\"reason\":\"fixture\","
                        + "\"withdrawal_reason\":\"conclusion_error\"}"))
                .andExpect(status().isOk());
        verify(service).action(eq(OWNER), eq("report"), eq("fixture-report"),
                eq(new ContentService.ContentAction(1, "withdraw", "fixture", "conclusion_error")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));

        when(service.versionAction(eq(OWNER), eq("report"), eq("fixture-report"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/content/report/fixture-report/version-actions"),
                        "{\"expected_validity_revision\":1,\"action\":\"reverse_invalidation\","
                        + "\"reason\":\"fixture\",\"invalidation_id\":\"fixture-invalidation\","
                        + "\"target\":{\"kind\":\"content_version\",\"content_kind\":\"report\","
                        + "\"content_id\":\"fixture-report\",\"revision\":1,\"body_hash\":\""
                        + "a".repeat(64) + "\",\"input_job_id\":null}}"))
                .andExpect(status().isOk());
        verify(service).versionAction(eq(OWNER), eq("report"), eq("fixture-report"),
                eq(new ContentService.VersionAction(1, "reverse_invalidation", "fixture",
                        new ContentService.ContentVersionKey("content_version", "report", "fixture-report", 1,
                                "a".repeat(64), null), "fixture-invalidation")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));

        when(service.generationIntent(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(JOB);
        mvc.perform(write(post("/admin/reports/generation-intents"),
                        "{\"kind\":\"daily\",\"period_start\":\"2026-09-27T00:00:00.000000Z\","
                        + "\"period_end\":\"2026-09-28T00:00:00.000000Z\","
                        + "\"timezone\":\"Asia/Shanghai\",\"mode\":\"supplement\","
                        + "\"intent_revision\":2,\"reason\":\"fixture\"}"))
                .andExpect(status().isOk());
        verify(service).generationIntent(eq(OWNER), eq(new ContentService.GenerationIntentWrite(
                "daily", START, END, "Asia/Shanghai", "supplement", 2L, "fixture")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void malformedCommandsDoNotReachBusinessService() throws Exception {
        mvc.perform(auth(get("/admin/content/report/" + "x".repeat(65))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/reports?page=1&page=2")).andExpect(status().isBadRequest());
        mvc.perform(get("/interpretations?kind=report")).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/content/report/fixture-report/actions"),
                        "{\"expected_revision\":1,\"action\":\"withdraw\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/content/report/fixture-report/actions"),
                        "{\"expected_revision\":1,\"action\":\"publish\","
                        + "\"reason\":\"fixture\",\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/content/report/fixture-report/version-actions"),
                        "{\"expected_validity_revision\":0,\"action\":\"reverse_invalidation\","
                        + "\"reason\":\"fixture\",\"target\":{}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/content/report/fixture-report/version-actions"),
                        "{\"expected_validity_revision\":0,\"action\":\"invalidate_conclusion\","
                        + "\"reason\":\"fixture\",\"target\":{\"kind\":\"content_version\","
                        + "\"content_kind\":\"report\",\"content_id\":\"other-report\","
                        + "\"revision\":1,\"body_hash\":\"" + "a".repeat(64)
                        + "\",\"input_job_id\":null}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("target_mismatch"));
        mvc.perform(write(post("/admin/reports/generation-intents"),
                        "{\"kind\":\"daily\",\"kind\":\"weekly\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/admin/reports/generation-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))).andExpect(status().isForbidden());
        verify(service, never()).action(any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).versionAction(any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).generationIntent(any(), any(), any(), any(), any());
    }

    @Test
    void missingServiceFailsClosedAndRefusalMapsStatus() throws Exception {
        mvc.perform(get("/reports/fixture-report")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        when(service.report("fixture-report")).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.NOT_FOUND, null));
        mvc.perform(get("/reports/fixture-report")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"));
    }
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token", "fixture-csrf")
                .header("Origin", ORIGIN).header("Idempotency-Key", "fixture-key").content(body);
    }
}
