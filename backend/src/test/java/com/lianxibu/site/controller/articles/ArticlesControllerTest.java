package com.lianxibu.site.controller.articles;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.articles.ArticlesService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
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

class ArticlesControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final Actor USER = new Actor("fixture-user", "user");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final Instant TIME = Instant.parse("2026-09-27T00:00:00Z");
    private static final ArticlesService.Article ARTICLE = new ArticlesService.Article("fixture-article",
            "Fixture", null, "fixture-source", "Fixture Source", "https://feed.example.test/item", null,
            TIME, null, null, "ready", 1, null);
    private static final ArticlesService.Entity ENTITY = new ArticlesService.Entity("fixture-entity",
            "news_tech", "Fixture", null, TIME, LocalDate.of(2026, 9, 27), TIME, 1L, 0L,
            "analyzed", 1);
    private static final ArticlesService.ActionResult ACTION = new ArticlesService.ActionResult("fixture-operation",
            new ArticlesService.ResourceRef("entity", "fixture-entity", null), 2, null, null, null,
            List.of(), null);
    private IdentityService identity;
    private ArticlesService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(ArticlesService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<ArticlesService> articleProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(articleProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new ArticlesController(identityProvider, articleProvider)).build();
    }

    @Test
    void fiveOperationsMapToServiceWithTypedFields() throws Exception {
        when(service.listArticles(OWNER, "fixture-source", "fixture", "planned", 2, 10))
                .thenReturn(new ArticlesService.ArticlePage(List.of(ARTICLE), 2, 10, 1));
        mvc.perform(auth(get("/private/articles?source_id=fixture-source&q=fixture&status=planned&page=2&page_size=10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].source_id").value("fixture-source"))
                .andExpect(jsonPath("$.data.page_size").value(10));
        when(service.article(OWNER, "fixture-article")).thenReturn(ARTICLE);
        mvc.perform(auth(get("/private/articles/fixture-article"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.observed_at").value("2026-09-27T00:00:00.000000Z"));

        when(service.listEntities(OWNER, "news_tech", "fixture", 1, 20))
                .thenReturn(new ArticlesService.EntityPage(List.of(ENTITY), 1, 20, 1));
        mvc.perform(auth(get("/admin/entities?category=news_tech&q=fixture"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].first_seen_business_date").value("2026-09-27"));
        when(service.entity(OWNER, "fixture-entity")).thenReturn(ENTITY);
        mvc.perform(auth(get("/admin/entities/fixture-entity"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis_state").value("analyzed"));

        when(service.actOnEntity(eq(OWNER), eq("fixture-entity"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/entities/fixture-entity/actions"),
                        "{\"expected_revision\":1,\"action\":\"correct_assignments\",\"reason\":\"fixture\","
                        + "\"assignments\":[{\"item_id\":\"fixture-item\",\"old_entity_id\":\"fixture-old\","
                        + "\"new_entity_id\":\"fixture-new\",\"expected_item_revision\":0}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result_resource_ref.id")
                        .value("fixture-entity"));
        verify(service).actOnEntity(eq(OWNER), eq("fixture-entity"),
                eq(new ArticlesService.EntityAction(1, "correct_assignments", "fixture",
                        List.of(new ArticlesService.Assignment("fixture-item", "fixture-old", "fixture-new", 0)))),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void privateReadsPermitUserButAdminRequiresOwner() throws Exception {
        when(identity.authenticate("fixture-session")).thenReturn(USER);
        when(service.article(USER, "fixture-article")).thenReturn(ARTICLE);
        mvc.perform(auth(get("/private/articles/fixture-article"))).andExpect(status().isOk());
        mvc.perform(auth(get("/admin/entities"))).andExpect(status().isForbidden());
        mvc.perform(get("/private/articles")).andExpect(status().isUnauthorized());
        verify(service, never()).listEntities(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsUnknownDuplicateAndIncompleteInputsBeforeServiceCall() throws Exception {
        mvc.perform(auth(get("/private/articles?page=1&page=2"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/private/articles?status=unknown"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(get("/admin/entities?unexpected=value"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/admin/entities/" + "x".repeat(65))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/entities/fixture-entity/actions"),
                        "{\"expected_revision\":1,\"expected_revision\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/entities/fixture-entity/actions"),
                        "{\"expected_revision\":1,\"action\":\"correct_assignments\","
                        + "\"reason\":\"fixture\",\"assignments\":[],\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/entities/fixture-entity/actions"),
                        "{\"expected_revision\":1,\"action\":\"correct_assignments\","
                        + "\"reason\":\"fixture\",\"assignments\":[{\"item_id\":\"fixture-item\"}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(post("/admin/entities/fixture-entity/actions").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))).andExpect(status().isForbidden());
        verify(service, never()).actOnEntity(any(), any(), any(), any(), any(), any());
        verify(service, never()).listArticles(any(), any(), any(), any(), anyLong(), anyInt());
        verify(service, never()).entity(any(), any());
    }

    @Test
    void missingBusinessServiceAndConflictsHaveContractErrors() throws Exception {
        when(service.entity(OWNER, "fixture-entity")).thenReturn(null);
        mvc.perform(auth(get("/admin/entities/fixture-entity"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        when(service.entity(OWNER, "fixture-entity")).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.REVISION_CONFLICT, null));
        mvc.perform(auth(get("/admin/entities/fixture-entity"))).andExpect(status().isConflict())
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
