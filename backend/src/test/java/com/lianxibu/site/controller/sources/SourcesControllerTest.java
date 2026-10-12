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
        when(identity.authorizeWrite("fixture-session", "fixture-csrf", ORIGIN)).thenReturn(OWNER);
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
                eq("fixture-csrf"), eq(ORIGIN))).thenReturn(new SourcesService.ActionResult("fixture-operation",
                        new SourcesService.ResourceRef("source_run", "fictional-probe", null), 0, 1L,
                        null, null, List.of(), null));
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
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(itemReceipt("{\"expected_revision\":0,\"action\":\"link_existing\",\"reason\":\"fixture\",\"entity_id\":\"fixture-entity\"}",false));
        mvc.perform(write(post("/admin/source-runs/fixture-run/items/fixture-item/actions"),
                        "{\"expected_revision\":0,\"action\":\"link_existing\","
                        + "\"reason\":\"fixture\",\"entity_id\":\"fixture-entity\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result_resource_ref.id").value("fixture-item"));
        verify(service).itemAction(eq(OWNER), eq("fixture-run"), eq("fixture-item"),
                eq(new SourcesService.ItemAction(0, "link_existing", "fixture", "fixture-entity",
                        null, null, null)), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test void sourceActionsRejectMismatchedReceiptsAndAllowProbeRunOrDisabledNoop() throws Exception {
        var runReceipt = new SourcesService.ActionResult("fictional-operation",
                new SourcesService.ResourceRef("source_run", "fictional-probe", null), 0, 1L,
                null, null, List.of(), null);
        stubSourceAction(runReceipt);
        mvc.perform(sourceActionRequest("fixture", "probe", 0)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.result_resource_ref.kind").value("source_run"))
                .andExpect(jsonPath("$.data.result_revision").value(0))
                .andExpect(jsonPath("$.data.generation").value(1));
        stubSourceAction(ACTION);
        mvc.perform(sourceActionRequest("fixture", "probe", 0)).andExpect(status().isServiceUnavailable());
        for (var invalid : List.of(
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fictional-other", null), 1, null, null, null, List.of(), null),
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fixture", "fictional-package"), 1, null, null, null, List.of(), null),
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fixture", null), 0, null, null, null, List.of(), null),
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fixture", null), 1, 1L, null, null, List.of(), null),
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fixture", null), 1, null, null, null, List.of(ACTION.resultResourceRef()), null),
                new SourcesService.ActionResult("fictional-operation", new SourcesService.ResourceRef("source", "fixture", null), 1, null, null, null, List.of(), "fictional-cursor"))) {
            stubSourceAction(invalid);
            mvc.perform(sourceActionRequest("fixture", "start_trial", 0)).andExpect(status().isServiceUnavailable());
        }
        stubSourceAction(new SourcesService.ActionResult("fictional-operation", ACTION.resultResourceRef(), 0, null, null, null, List.of(), null));
        mvc.perform(sourceActionRequest("fixture", "disable", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_revision").value(0));
        stubSourceAction(ACTION);
        mvc.perform(sourceActionRequest("fixture", "disable", 0)).andExpect(status().isOk());
    }

    @Test void sourceActionLongUnicodeIdentityAndSafeCounterAreNotTruncated() throws Exception {
        String sourceId = "\uD83D\uDE42".repeat(80);
        stubSourceAction(new SourcesService.ActionResult("fictional-operation",
                new SourcesService.ResourceRef("source", sourceId, null), 1, null, null, null, List.of(), null));
        mvc.perform(sourceActionRequest(sourceId, "start_trial", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_resource_ref.id").value(sourceId));
        stubSourceAction(new SourcesService.ActionResult("fictional-operation",
                new SourcesService.ResourceRef("source", sourceId, null), 9007199254740992L, null, null, null, List.of(), null));
        mvc.perform(sourceActionRequest(sourceId, "start_trial", 9007199254740991L))
                .andExpect(status().isServiceUnavailable());
        stubSourceAction(new SourcesService.ActionResult("fictional-operation",
                new SourcesService.ResourceRef("source_run", "fictional-probe", null), 0, 0L, null, null, List.of(), null));
        mvc.perform(sourceActionRequest(sourceId, "probe", 0)).andExpect(status().isServiceUnavailable());
        stubSourceAction(new SourcesService.ActionResult("fictional\n", ACTION.resultResourceRef(), 1, null, null, null, List.of(), null));
        mvc.perform(sourceActionRequest("fixture", "start_trial", 0)).andExpect(status().isServiceUnavailable());
    }

    private void stubSourceAction(SourcesService.ActionResult result) {
        when(service.sourceAction(eq(OWNER), any(), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN))).thenReturn(result);
    }
    private MockHttpServletRequestBuilder sourceActionRequest(String id, String action, long revision) {
        return write(post("/admin/sources/{id}/actions", id), "{\"expected_revision\":" + revision
                + ",\"action\":\"" + action + "\",\"reason\":\"fictional reason\"}");
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

    @Test void sixItemCommandsBindFullBodyAndBothResultRoots() throws Exception {
        for(String action:List.of("confirm_new","link_existing","exclude","resolve_projection","revalidate","seal")) {
            String extra=action.equals("link_existing")?",\"entity_id\":\"fictional-entity\"":action.equals("resolve_projection")?",\"decision\":\"promote\",\"expected_article_revision\":4,\"evidence_refs\":[\"fictional-evidence\"]":"";
            String body="{\"expected_revision\":7,\"action\":\""+action+"\",\"reason\":\"Fictional explicit reason\""+extra+"}";
            for(boolean correction:List.of(false,true)) {
                var result=itemReceipt(body,correction);stubItem(result);
                mvc.perform(write(post("/admin/source-runs/fixture-run/items/fixture-item/actions"),body))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.data.command_ref.run_id").value("fixture-run"))
                    .andExpect(jsonPath("$.data.command_ref.item_id").value("fixture-item"))
                    .andExpect(jsonPath("$.data.command_ref.action").value(action))
                    .andExpect(jsonPath("$.data.command_ref.command_hash").value(result.commandRef().commandHash()))
                    .andExpect(jsonPath("$.data.result_resource_ref.kind").value(correction?"source_run":"source_run_item"));
            }
        }
    }
    @Test void borrowedOriginalCommandAndInvalidResultRootsReturnSafeUnavailable() throws Exception {
        String body="{\"expected_revision\":7,\"action\":\"exclude\",\"reason\":\"Fictional explicit reason\"}";var good=itemReceipt(body,false);var c=good.commandRef();
        for(var bad:List.of(new SourcesService.ItemCommandRef(c.sourceId(),"fictional-other",c.itemId(),7,c.action(),c.commandHash()),
                new SourcesService.ItemCommandRef(c.sourceId(),c.runId(),"fictional-other",7,c.action(),c.commandHash()),
                new SourcesService.ItemCommandRef(c.sourceId(),c.runId(),c.itemId(),8,c.action(),c.commandHash()),
                new SourcesService.ItemCommandRef(c.sourceId(),c.runId(),c.itemId(),7,"confirm_new",c.commandHash()),
                new SourcesService.ItemCommandRef(c.sourceId(),c.runId(),c.itemId(),7,c.action(),"0".repeat(64)))) {
            stubItem(new SourcesService.ItemActionResult(good.operationRef(),good.resultResourceRef(),8,null,null,null,List.of(),null,bad));itemUnavailable(body);
        }
        for(var root:List.of(new SourcesService.ResourceRef("source_run_item","fictional-other",null),new SourcesService.ResourceRef("source_run_item","fixture-item","fictional-package"),new SourcesService.ResourceRef("source_run","fixture-run",null),new SourcesService.ResourceRef("source","fixture-item",null))) {
            stubItem(new SourcesService.ItemActionResult(good.operationRef(),root,8,null,null,null,List.of(),null,c));itemUnavailable(body);
        }
        stubItem(new SourcesService.ItemActionResult(good.operationRef(),good.resultResourceRef(),7,null,null,null,List.of(),null,c));itemUnavailable(body);
        stubItem(new SourcesService.ItemActionResult(good.operationRef(),good.resultResourceRef(),8,1L,null,null,List.of(),null,c));itemUnavailable(body);
        stubItem(itemReceipt(body.replace("explicit reason","different reason"),false));itemUnavailable(body);
    }
    private void itemUnavailable(String body) throws Exception {mvc.perform(write(post("/admin/source-runs/fixture-run/items/fixture-item/actions"),body)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable")).andExpect(jsonPath("$.data").doesNotExist());}
    private void stubItem(SourcesService.ItemActionResult result){when(service.itemAction(eq(OWNER),eq("fixture-run"),eq("fixture-item"),any(),eq("fixture-key"),eq("fixture-csrf"),eq(ORIGIN))).thenReturn(result);}
    private static SourcesService.ItemActionResult itemReceipt(String raw,boolean correction){
        var body=com.lianxibu.site.domain.protocol.CanonicalJson.parse(raw);long revision=body.path("expected_revision").asLong();String hash=com.lianxibu.site.domain.protocol.CanonicalJson.hash(com.lianxibu.site.domain.protocol.CanonicalJson.Domain.ACTION,body);
        return new SourcesService.ItemActionResult("fictional-operation",new SourcesService.ResourceRef(correction?"source_run":"source_run_item",correction?"fictional-correction":"fixture-item",null),correction?0:revision+1,correction?2L:null,null,null,List.of(),null,new SourcesService.ItemCommandRef("fixture","fixture-run","fixture-item",revision,body.path("action").asText(),hash));
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
            mvc.perform(write(post("/admin/sources"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(put("/admin/sources/fixture"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(post("/admin/sources/fixture/actions"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(post("/admin/source-runs"), "{}")).andExpect(status().isForbidden());
            mvc.perform(write(post("/admin/source-runs/fixture/items/item/actions"), "{}")).andExpect(status().isForbidden());
            org.mockito.Mockito.verifyNoInteractions(service);
            org.mockito.Mockito.reset(identity);
            when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        }
    }
}
