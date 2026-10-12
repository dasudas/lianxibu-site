package com.lianxibu.site.controller.learning;

import static org.mockito.ArgumentMatchers.any;
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
import com.lianxibu.site.service.learning.LearningService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LearningControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final LearningService.PackageSummary PACKAGE = new LearningService.PackageSummary(
            "fixture-package", "Fixture", "", 1, "published");
    private static final LearningService.Route ROUTE = new LearningService.Route("fixture-package", "fixture-route",
            "Route", List.of("fixture-module"), 0, 1, "0");
    private static final LearningService.Module MODULE = new LearningService.Module("fixture-package",
            "fixture-module", "Module", List.of("fixture-route"));
    private static final LearningService.Node NODE = new LearningService.Node("fixture-package", "fixture-node",
            "fixture-module", "Node", "", List.of(), List.of(), List.of(), List.of(), 0);
    private static final LearningService.Question QUESTION = new LearningService.Question("fixture-package",
            "fixture-question", "Question", "What is fixture?", "beginner", "concept", List.of(),
            "draft", "not_generated", 0, null);
    private static final LearningService.Project PROJECT = new LearningService.Project("fixture-package",
            "fixture-project", "Project", "", List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    private static final LearningService.ActionResult ACTION = new LearningService.ActionResult("fixture-operation",
            new LearningService.ResourceRef("question", "fixture-question", "fixture-package"), 1,
            null, null, null, List.of(), null);
    private IdentityService identity;
    private LearningService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(LearningService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<LearningService> learningProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(learningProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        when(identity.authorizeWrite("fixture-session", "fixture-csrf", ORIGIN)).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new LearningController(identityProvider, learningProvider)).build();
    }

    @Test
    void twelvePublicReadsMapExactServiceAndWireFields() throws Exception {
        when(service.packages(1, 20)).thenReturn(new LearningService.Page<>(List.of(PACKAGE), 1, 20, 1));
        mvc.perform(get("/learning/packages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].state").value("published"));
        when(service.packageSummary("fixture-package")).thenReturn(PACKAGE);
        mvc.perform(get("/learning/packages/fixture-package")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Fixture"));
        var filter = new LearningService.Query("fixture-route", "fixture-module", "fixture",
                null, null, null, 1, 20);
        when(service.routes("fixture-package", filter)).thenReturn(new LearningService.Page<>(List.of(ROUTE), 1, 20, 1));
        when(service.modules("fixture-package", filter)).thenReturn(new LearningService.Page<>(List.of(MODULE), 1, 20, 1));
        when(service.nodes("fixture-package", filter)).thenReturn(new LearningService.Page<>(List.of(NODE), 1, 20, 1));
        String base = "/learning/packages/fixture-package/";
        String query = "?route_id=fixture-route&module_id=fixture-module&q=fixture";
        mvc.perform(get(base + "routes" + query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].module_ids[0]").value("fixture-module"));
        mvc.perform(get(base + "modules" + query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].route_ids[0]").value("fixture-route"));
        mvc.perform(get(base + "nodes" + query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].module_id").value("fixture-module"));
        var questionFilter = new LearningService.Query("fixture-route", "fixture-module", "fixture",
                "beginner", "concept", "fixture-node", 1, 20);
        when(service.questions("fixture-package", questionFilter))
                .thenReturn(new LearningService.Page<>(List.of(QUESTION), 1, 20, 1));
        mvc.perform(get(base + "questions" + query + "&difficulty=beginner&kind=concept&node_id=fixture-node"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].answer").doesNotExist());
        var projectFilter = new LearningService.Query("fixture-route", "fixture-module", "fixture",
                null, null, "fixture-node", 1, 20);
        when(service.projects("fixture-package", projectFilter))
                .thenReturn(new LearningService.Page<>(List.of(PROJECT), 1, 20, 1));
        mvc.perform(get(base + "projects" + query + "&node_id=fixture-node"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].acceptance_criteria").isArray());

        when(service.node("fixture-package", "fixture-node")).thenReturn(NODE);
        when(service.question("fixture-package", "fixture-question")).thenReturn(QUESTION);
        when(service.project("fixture-package", "fixture-project")).thenReturn(PROJECT);
        mvc.perform(get(base + "nodes/fixture-node")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("fixture-node"));
        mvc.perform(get(base + "questions/fixture-question")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").doesNotExist());
        mvc.perform(get(base + "projects/fixture-project")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("fixture-project"));
        when(service.graph("fixture-package", "fixture-module", "fixture-cursor"))
                .thenReturn(new LearningService.Graph(List.of(NODE),
                        List.of(new LearningService.GraphEdge("fixture-node", "fixture-question", "related")), null));
        mvc.perform(get(base + "graph?module_id=fixture-module&cursor=fixture-cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.edges[0].from_id").value("fixture-node"));
        when(service.resolve("node", "fixture-node"))
                .thenReturn(new LearningService.ResolvedPackage("fixture-package", "node", "fixture-node"));
        mvc.perform(get("/learning/resolve?kind=node&id=fixture-node")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.package_id").value("fixture-package"));
    }

    @Test
    void fiveOwnerCommandsMapStageCommitAndAnswerOperations() throws Exception {
        var progress = new LearningService.ImportProgress("fixture-import", 0, "a".repeat(64),
                Instant.parse("2026-09-28T00:00:00Z"));
        when(service.createPackage(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(progress);
        mvc.perform(write(post("/admin/learning/packages"), "{\"action\":\"stage\",\"chunk\":{"
                        + "\"import_id\":\"fixture-import\",\"part_no\":0,\"part_count\":1,"
                        + "\"part_hash\":\"" + "a".repeat(64) + "\",\"items\":[{\"from_id\":\"fixture-node\","
                        + "\"to_id\":\"fixture-question\",\"kind\":\"related\"}]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted_part_no").value(0));
        when(service.writePackage(eq(OWNER), eq("fixture-package"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(new LearningService.PackageSummaryResult(PACKAGE));
        mvc.perform(write(put("/admin/learning/packages/fixture-package"),
                        "{\"expected_revision\":1,\"action\":\"commit\",\"manifest\":{"
                        + "\"import_id\":\"fixture-import\",\"title\":\"Fixture\",\"description\":\"\","
                        + "\"part_count\":1,\"manifest_hash\":\"" + "b".repeat(64) + "\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("fixture-package"));
        when(service.packageAction(eq(OWNER), eq("fixture-package"), any(), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/learning/packages/fixture-package/actions"),
                        "{\"expected_revision\":1,\"action\":\"publish\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.operation_ref").value("fixture-operation"));
        when(service.writeAnswer(eq(OWNER), eq("fixture-package"), eq("fixture-question"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(put("/admin/learning/packages/fixture-package/questions/fixture-question/answer"),
                        "{\"expected_revision\":0,\"answer\":\"Fixture answer\"}"))
                .andExpect(status().isOk());
        verify(service).writeAnswer(eq(OWNER), eq("fixture-package"), eq("fixture-question"),
                eq(new LearningService.AnswerWrite(0, "Fixture answer")), eq("fixture-key"),
                eq("fixture-csrf"), eq(ORIGIN));
        when(service.answerAction(eq(OWNER), eq("fixture-package"), eq("fixture-question"), any(),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(ACTION);
        mvc.perform(write(post("/admin/learning/packages/fixture-package/questions/fixture-question/answer/actions"),
                        "{\"expected_revision\":0,\"action\":\"approve\",\"reason\":\"fixture\"}"))
                .andExpect(status().isOk());
        verify(service).answerAction(eq(OWNER), eq("fixture-package"), eq("fixture-question"),
                eq(new LearningService.AnswerAction(0, "approve", "fixture")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void rejectsUnauthenticatedWrongRoleAndMalformedInputs() throws Exception {
        mvc.perform(post("/admin/learning/packages").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(write(post("/admin/learning/packages"), "{}"))
                .andExpect(status().isForbidden());
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc.perform(get("/learning/packages?page=1&page=2")).andExpect(status().isBadRequest());
        mvc.perform(get("/learning/resolve?kind=node")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/learning/packages/" + "x".repeat(65))).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/learning/packages"),
                        "{\"action\":\"stage\",\"action\":\"commit\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/learning/packages"),
                        "{\"action\":\"stage\",\"chunk\":{\"import_id\":\"fixture-import\"}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/admin/learning/packages"), "{\"action\":\"stage\",\"chunk\":{"
                        + "\"import_id\":\"fixture-import\",\"part_no\":0,\"part_count\":1,"
                        + "\"part_hash\":\"" + "a".repeat(64) + "\",\"items\":[{\"from_id\":\"fixture-node\","
                        + "\"to_id\":\"fixture-question\",\"kind\":\"related\",\"unknown\":1}]}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/admin/learning/packages/fixture-package/actions"),
                        "{\"expected_revision\":0,\"action\":\"publish\",\"reason\":\"fixture\","
                        + "\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/admin/learning/packages/fixture-package/questions/fixture-question/answer")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
        verify(service, never()).createPackage(any(), any(), any(), any(), any());
        verify(service, never()).packageAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unpublishedAnswerCannotLeakAndAbsentServiceFailsClosed() throws Exception {
        when(service.question("fixture-package", "fixture-question"))
                .thenReturn(new LearningService.Question("fixture-package", "fixture-question", "Question",
                        "What?", "beginner", "concept", List.of(), "draft", "not_generated", 0,
                        "private draft answer"));
        mvc.perform(get("/learning/packages/fixture-package/questions/fixture-question"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/learning/packages/fixture-package/projects/fixture-project"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        when(service.question("fixture-package", "fixture-question"))
                .thenReturn(new LearningService.Question("fixture-package", "fixture-question", "Question",
                        "What?", "beginner", "concept", List.of(), "published", "reviewed", 1,
                        "Published fixture answer"));
        mvc.perform(get("/learning/packages/fixture-package/questions/fixture-question"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("Published fixture answer"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"));
    }
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token", "fixture-csrf")
                .header("Origin", ORIGIN).header("Idempotency-Key", "fixture-key").content(body);
    }

    @Test
    void everyWriteRejectsInvalidCsrfAndChangedActorBeforeBusinessCall() throws Exception {
        for (boolean changedActor : new boolean[]{false, true}) {
            if (changedActor) when(identity.authorizeWrite("fixture-session", "fixture-csrf", ORIGIN))
                    .thenReturn(new Actor("another-fixture-owner", "owner"));
            else when(identity.authorizeWrite("fixture-session", "fixture-csrf", ORIGIN))
                    .thenThrow(new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN, null));
            mvc.perform(write(post("/admin/learning/packages"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(put("/admin/learning/packages/fixture"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(post("/admin/learning/packages/fixture/actions"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(put("/admin/learning/packages/fixture/questions/question/answer"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(post("/admin/learning/packages/fixture/questions/question/answer/actions"), "{}")).andExpect(status().isForbidden());
            org.mockito.Mockito.verifyNoInteractions(service);
            org.mockito.Mockito.reset(identity);
            when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        }
    }
}
