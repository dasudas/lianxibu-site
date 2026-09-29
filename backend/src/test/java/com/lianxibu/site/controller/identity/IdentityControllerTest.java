package com.lianxibu.site.controller.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.CollectorNode;
import com.lianxibu.site.service.identity.IdentityService.CollectorNodePage;
import com.lianxibu.site.service.identity.IdentityService.NodeActionOutcome;
import com.lianxibu.site.service.identity.IdentityService.NodeCredential;
import com.lianxibu.site.service.identity.IdentityService.SessionOutcome;
import com.lianxibu.site.service.identity.IdentityService.SessionState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityControllerTest {
    private IdentityService service;
    private MockMvc mvc;
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final CollectorNode NODE = new CollectorNode("fixture-node", "Fixture", List.of(),
            List.of("run:read"), 0, false, null);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = mock(IdentityService.class);
        ObjectProvider<IdentityService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        mvc = MockMvcBuilders.standaloneSetup(new IdentityController(provider)).build();
    }

    @Test
    void sessionOperationsMapToServiceAndSecureCookies() throws Exception {
        when(service.session(null, null)).thenReturn(new SessionOutcome(
                new SessionState(false, null, null, "fixture-csrf"), null, "fixture-anon", false, false));
        mvc.perform(get("/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(jsonPath("$.data.authenticated").value(false))
                .andExpect(jsonPath("$.data.csrf_token").value("fixture-csrf"))
                .andExpect(jsonPath("$.meta.request_id").isNotEmpty());

        when(service.login(any(), eq(null), eq("fixture-anon"), eq("fixture-csrf"),
                eq("https://site.example.invalid")))
                .thenReturn(new SessionOutcome(new SessionState(true, "fixture-owner", "owner", "new-csrf"),
                        "fixture-session", null, false, true));
        mvc.perform(post("/session").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_anonymous", "fixture-anon"))
                        .header("X-CSRF-Token", "fixture-csrf").header("Origin", "https://site.example.invalid")
                        .content("{\"username\":\"fixture\",\"password\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user_id").value("fixture-owner"))
                .andExpect(jsonPath("$.data.role").value("owner"));
        verify(service).login(eq(new IdentityService.LoginCommand("fixture", "fixture")),
                eq(null), eq("fixture-anon"), eq("fixture-csrf"), eq("https://site.example.invalid"));

        when(service.login(any(), eq("fixture-old-session"), eq(null), eq("fixture-old-csrf"),
                eq("https://site.example.invalid")))
                .thenReturn(new SessionOutcome(new SessionState(true, "fixture-owner", "owner", "rotated-csrf"),
                        "fixture-new-session", null, false, true));
        mvc.perform(post("/session").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-old-session"))
                        .header("X-CSRF-Token", "fixture-old-csrf")
                        .header("Origin", "https://site.example.invalid")
                        .content("{\"username\":\"fixture\",\"password\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.csrf_token").value("rotated-csrf"));
        verify(service).login(eq(new IdentityService.LoginCommand("fixture", "fixture")),
                eq("fixture-old-session"), eq(null), eq("fixture-old-csrf"),
                eq("https://site.example.invalid"));

        when(service.authenticate("fixture-session")).thenReturn(OWNER);
        when(service.logout(OWNER, "fixture-session", "fixture-csrf", "https://site.example.invalid"))
                .thenReturn(new SessionOutcome(new SessionState(false, null, null, "next-csrf"),
                        null, "next-anon", true, false));
        mvc.perform(delete("/session").cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("X-CSRF-Token", "fixture-csrf").header("Origin", "https://site.example.invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.authenticated").value(false));
    }

    @Test
    void nodeOperationsMapExactDtoAndOwnerContext() throws Exception {
        when(service.authenticate("fixture-session")).thenReturn(OWNER);
        when(service.listNodes(OWNER, 2, 10)).thenReturn(new CollectorNodePage(List.of(NODE), 2, 10, 1));
        mvc.perform(get("/admin/collector-nodes?page=2&page_size=10")
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("fixture-node"))
                .andExpect(jsonPath("$.data.items[0].source_ids").isArray())
                .andExpect(jsonPath("$.data.page_size").value(10));

        when(service.createNode(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"),
                eq("https://site.example.invalid"))).thenReturn(new NodeCredential(NODE, "fixture-token"));
        mvc.perform(post("/admin/collector-nodes").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("X-CSRF-Token", "fixture-csrf").header("Origin", "https://site.example.invalid")
                        .header("Idempotency-Key", "fixture-key")
                        .content("{\"name\":\"Fixture\",\"source_ids\":[],\"permissions\":[\"run:read\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.node.id").value("fixture-node"))
                .andExpect(jsonPath("$.data.token").value("fixture-token"));

        when(service.actOnNode(eq(OWNER), eq("fixture-node"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq("https://site.example.invalid")))
                .thenReturn(new NodeActionOutcome(NODE, "rotated-fixture-token"));
        mvc.perform(post("/admin/collector-nodes/fixture-node/actions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("X-CSRF-Token", "fixture-csrf").header("Origin", "https://site.example.invalid")
                        .header("Idempotency-Key", "fixture-key")
                        .content("{\"expected_revision\":0,\"action\":\"rotate_token\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("rotated-fixture-token"));
    }

    @Test
    void rejectsMissingAuthenticationAndWrongRole() throws Exception {
        mvc.perform(get("/admin/collector-nodes")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
        when(service.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(get("/admin/collector-nodes")
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("forbidden"));
    }

    @Test
    void rejectsMalformedUnknownAndIncompleteInputsBeforeServiceMutation() throws Exception {
        when(service.authenticate("fixture-session")).thenReturn(OWNER);
        mvc.perform(post("/session").contentType(MediaType.APPLICATION_JSON)
                        .header("X-CSRF-Token", "fixture").header("Origin", "https://site.example.invalid")
                        .content("{\"username\":\"a\",\"username\":\"b\",\"password\":\"c\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("protocol_error"));
        mvc.perform(post("/admin/collector-nodes").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("X-CSRF-Token", "fixture").header("Origin", "https://site.example.invalid")
                        .header("Idempotency-Key", "fixture-key")
                        .content("{\"name\":\"Fixture\",\"source_ids\":[],\"permissions\":[],\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/collector-nodes/fixture-node/actions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("X-CSRF-Token", "fixture").header("Origin", "https://site.example.invalid")
                        .header("Idempotency-Key", "fixture-key")
                        .content("{\"expected_revision\":0,\"action\":\"set_permissions\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("validation"));
        mvc.perform(post("/admin/collector-nodes").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"))
                        .header("Origin", "https://site.example.invalid").header("Idempotency-Key", "fixture-key")
                        .content("{\"name\":\"Fixture\",\"source_ids\":[],\"permissions\":[]}"))
                .andExpect(status().isForbidden());
        verify(service, never()).createNode(any(), any(), any(), any(), any());
        verify(service, never()).actOnNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidPageAndIncompletePathIdentity() throws Exception {
        when(service.authenticate("fixture-session")).thenReturn(OWNER);
        var cookie = new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session");
        mvc.perform(get("/admin/collector-nodes?page=0").cookie(cookie))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/admin/collector-nodes?page=1&page=2").cookie(cookie))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/collector-nodes/" + "a".repeat(65) + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).cookie(cookie)
                        .header("X-CSRF-Token", "fixture").header("Origin", "https://site.example.invalid")
                        .header("Idempotency-Key", "fixture-key")
                        .content("{\"expected_revision\":0,\"action\":\"revoke\",\"reason\":\"fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(service, never()).listNodes(any(), anyLong(), anyInt());
        verify(service, never()).actOnNode(any(), any(), any(), any(), any(), any());
    }

    @Test
    void serviceRefusalsMapToContractStatusWithoutPrivateMessage() throws Exception {
        when(service.authenticate("fixture-session")).thenReturn(OWNER);
        when(service.listNodes(OWNER, 1, 20)).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.RATE_LIMITED, "5"));
        mvc.perform(get("/admin/collector-nodes")
                        .cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session")))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentServiceFailsClosedWithoutInventingSession() throws Exception {
        ObjectProvider<IdentityService> absent = mock(ObjectProvider.class);
        MockMvc closed = MockMvcBuilders.standaloneSetup(new IdentityController(absent)).build();
        closed.perform(get("/session")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.code").value("temporarily_unavailable"));
        verifyNoInteractions(service);
    }
}
