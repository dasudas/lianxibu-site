package com.lianxibu.site.controller.sources;

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

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.sources.SourcesService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SourcesControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final String CONFIG = "{\"adapter\":\"rss\",\"entry_urls\":[\"https://feed.example.test/rss\"],"
            + "\"identity_rule\":\"guid\",\"window_rule\":\"daily\",\"parser_version\":\"v1\","
            + "\"max_items\":10,\"max_response_bytes\":10000,\"allowed_hosts\":[\"feed.example.test\"],"
            + "\"valid_until\":\"2026-10-01T00:00:00.000000Z\",\"auth_secret_ref\":null}";
    private static final SourcesService.Source SOURCE = new SourcesService.Source("fixture", "Fixture",
            "news_tech", 0, 0, "disabled", null, null, null);
    private static final SourcesService.Coverage COVERAGE = new SourcesService.Coverage("complete",
            List.of(), List.of(), List.of());
    private static final SourcesService.Run RUN = new SourcesService.Run("fixture-run", "fixture",
            "production_qualification", LocalDate.of(2026, 9, 27), 1,
            Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"),
            "bounded", "fixture", 0, 0, "pending", "pending", "pending", COVERAGE,
            null, 0, 0, 0, null, false, null, null);
    private static final SourcesService.RunItem ITEM = new SourcesService.RunItem("fixture-item",
            "fixture-run", "fixture-member", "fixture-source-item", "news_tech", null,
            0, "pending", "pending", null, null, null, null);
    private static final SourcesService.ActionResult ACTION = new SourcesService.ActionResult("fixture-operation",
            new SourcesService.ResourceRef("source", "fixture", null), 1, null, null, null,
            List.of(), null);
    private IdentityService identity;
    private SourcesService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(SourcesService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<SourcesService> sourceProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(sourceProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new SourcesController(identityProvider, sourceProvider)).build();
    }

    @Test
    void nineOperationsMapToTypedServiceAndExactWireFields() throws Exception {
        when(service.listSources(OWNER, 2, 10)).thenReturn(new SourcesService.SourcePage(List.of(SOURCE), 2, 10, 1));
        mvc.perform(auth(get("/admin/sources?page=2&page_size=10"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("fixture"))
                .andExpect(jsonPath("$.data.page_size").value(10));

        when(service.listRuns(OWNER, "fixture", LocalDate.of(2026, 9, 27), 1, 20))
                .thenReturn(new SourcesService.RunPage(List.of(RUN), 1, 20, 1));
        mvc.perform(auth(get("/admin/source-runs?source_id=fixture&business_date=2026-09-27")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].coverage.state").value("complete"));
        when(service.run(OWNER, "fixture-run")).thenReturn(RUN);
        mvc.perform(auth(get("/admin/source-runs/fixture-run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window_start").value("2026-09-27T00:00:00.000000Z"));
        when(service.listItems(OWNER, "fixture-run", 1, 20))
                .thenReturn(new SourcesService.RunItemPage(List.of(ITEM), 1, 20, 1));
        mvc.perform(auth(get("/admin/source-runs/fixture-run/items"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].source_item_id").value("fixture-source-item"));

        when(service.createSource(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(SOURCE);
        mvc.perform(write(post("/admin/sources"), "{\"id\":\"fixture\",\"name\":\"Fixture\","
                        + "\"category\":\"news_tech\",\"config\":" + CONFIG + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activation_stage").value("disabled"));
        verify(service).createSource(eq(OWNER), eq(new SourcesService.SourceCreate("fixture", "Fixture",
                "news_tech", new SourcesService.SourceConfig("rss", List.of("https://feed.example.test/rss"),
                "guid", "daily", "v1", 10, 10000, List.of("feed.example.test"),
                Instant.parse("2026-10-01T00:00:00Z"), null))), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));

        when(service.writeSource(eq(OWNER), eq("fixture"), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(SOURCE);
        mvc.perform(write(put("/admin/sources/fixture"), "{\"expected_revision\":0,\"name\":\"Fixture\","
                        + "\"category\":\"news_tech\",\"config\":" + CONFIG + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("fixture"));
        when(service.sourceAction(eq(OWNER), eq("fixture"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/sources/fixture/actions"),
                        "{\"expected_revision\":0,\"action\":\"probe\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operation_ref").value("fixture-operation"));

        when(service.createRun(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(RUN);
        mvc.perform(write(post("/admin/source-runs"), "{\"source_id\":\"fixture\","
                        + "\"business_date\":\"2026-09-27\",\"window_start\":\"2026-09-27T00:00:00.000000Z\","
                        + "\"window_end\":\"2026-09-28T00:00:00.000000Z\","
                        + "\"run_kind\":\"production_qualification\",\"config_version\":0,"
                        + "\"expected_revision\":0,\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run_id").value("fixture-run"));
        when(service.itemAction(eq(OWNER), eq("fixture-run"), eq("fixture-item"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/source-runs/fixture-run/items/fixture-item/actions"),
                        "{\"expected_revision\":0,\"action\":\"link_existing\","
                        + "\"reason\":\"fixture\",\"entity_id\":\"fixture-entity\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result_resource_ref.id").value("fixture"));
        verify(service).itemAction(eq(OWNER), eq("fixture-run"), eq("fixture-item"),
                eq(new SourcesService.ItemAction(0, "link_existing", "fixture", "fixture-entity",
                        null, null, null)), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void authenticationAuthorizationAndAbsentServiceFailClosed() throws Exception {
        mvc.perform(get("/admin/sources")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(auth(get("/admin/sources"))).andExpect(status().isForbidden());
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        when(service.listSources(OWNER, 1, 20)).thenReturn(null);
        mvc.perform(auth(get("/admin/sources"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        verify(service, never()).createSource(any(), any(), any(), any(), any());
    }

    @Test
    void malformedRequestsNeverReachMutationService() throws Exception {
        mvc.perform(write(post("/admin/sources"), "{\"id\":\"a\",\"id\":\"b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/sources"), "{\"id\":\"fixture\",\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post("/admin/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"fixture\"}"))).andExpect(status().isForbidden());
        mvc.perform(write(post("/admin/sources/fixture/actions"),
                        "{\"expected_revision\":0,\"action\":\"invalid\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/source-runs/fixture-run/items/fixture-item/actions"),
                        "{\"expected_revision\":0,\"action\":\"link_existing\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/source-runs"), "{\"source_id\":\"fixture\","
                        + "\"business_date\":\"2026-09-27\",\"window_start\":\"2026-09-27T00:00:00Z\","
                        + "\"window_end\":\"2026-09-28T00:00:00.000000Z\","
                        + "\"run_kind\":\"production_qualification\",\"config_version\":0,"
                        + "\"expected_revision\":0,\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(service, never()).createSource(any(), any(), any(), any(), any());
        verify(service, never()).sourceAction(any(), any(), any(), any(), any(), any());
        verify(service, never()).createRun(any(), any(), any(), any(), any());
        verify(service, never()).itemAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidQueriesAndIncompleteIdentities() throws Exception {
        mvc.perform(auth(get("/admin/sources?page=1&page=2"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/admin/sources?page_size=51"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(get("/admin/source-runs?business_date=2026-09-99")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(get("/admin/source-runs/" + "x".repeat(65))))
                .andExpect(status().isUnprocessableEntity());
        verify(service, never()).listSources(any(), anyLong(), anyInt());
        verify(service, never()).listRuns(any(), any(), any(), anyLong(), anyInt());
        verify(service, never()).run(any(), any());
    }

    @Test
    void serviceConflictHidesPrivateMessage() throws Exception {
        when(service.run(OWNER, "fixture-run")).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.REVISION_CONFLICT, null));
        mvc.perform(auth(get("/admin/source-runs/fixture-run"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("revision_conflict"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"));
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token", "fixture-csrf")
                .header("Origin", ORIGIN).header("Idempotency-Key", "fixture-key").content(body);
    }
}
