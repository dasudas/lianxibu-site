package com.lianxibu.site.controller.records;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.records.RecordsService;
import com.lianxibu.site.service.records.PrivateRecordWriteService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordsControllerTest {
    private static final Actor USER = new Actor("fixture-user", "user");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final RecordsService.Record RECORD = new RecordsService.Record(null, "article",
            "fixture-article", 1, "planned", "unassessed", "fixture note", null, null, null,
            null, Instant.parse("2026-09-27T00:00:00Z"), true, false, false);
    private IdentityService identity;
    private RecordsService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        identity = mock(IdentityService.class);
        service = mock(RecordsService.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        ObjectProvider<RecordsService> recordProvider = mock(ObjectProvider.class);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(recordProvider.getIfAvailable()).thenReturn(service);
        when(identity.authenticate("fixture-session")).thenReturn(USER);
        when(identity.authorizeWrite("fixture-session", "fixture-csrf", ORIGIN)).thenReturn(USER);
        mvc = MockMvcBuilders.standaloneSetup(new RecordsController(identityProvider, recordProvider)).build();
    }

    @Test
    void writeBusinessRefusalsHaveSafeStableHttpMeaning() throws Exception {
        String body = "{\"expected_revision\":0,\"status\":\"planned\",\"self_assessment\":\"unassessed\","
                + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\",\"object_kind\":\"node\"}";
        for (var sample : new Object[][] {{"object_missing",404,"object_missing"},
                {"revision_conflict",409,"revision_conflict"}, {"identity_conflict",409,"identity_conflict"},
                {"note_too_long",422,"note_too_long"}, {"answer_too_long",422,"answer_too_long"},
                {"fictional-private-storage-detail",503,"temporarily_unavailable"},
                {null,503,"temporarily_unavailable"}}) {
            when(service.write(eq(USER), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new PrivateRecordWriteService.Rejected((String) sample[0]));
            var result = mvc.perform(write(put("/private/records/node/fixture-node"), body))
                    .andExpect(status().is((Integer) sample[1]))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error.code").value(sample[2]));
            if ((Integer) sample[1] == 503) result.andExpect(header().string("Retry-After", "1"));
        }
    }

    @Test
    void listAndFourWriteBranchesMapToService() throws Exception {
        when(service.list(USER, "fixture-package", "question", 2, 10))
                .thenReturn(new RecordsService.RecordPage(List.of(RECORD), 2, 10, 1));
        mvc.perform(auth(get("/private/records?package_id=fixture-package&kind=question&page=2&page_size=10")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].object_id").value("fixture-article"))
                .andExpect(jsonPath("$.data.items[0].updated_at").value("2026-09-27T00:00:00.000000Z"));
        when(service.write(eq(USER), any(), any(), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(RECORD);
        mvc.perform(write(put("/private/records/article/fixture-article"),
                        "{\"expected_revision\":1,\"status\":\"planned\",\"self_assessment\":\"unassessed\","
                        + "\"note\":\"fixture note\",\"artifact_url\":null,\"package_id\":null,"
                        + "\"object_kind\":\"article\",\"is_read\":true,\"is_favorite\":false,\"is_ignored\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.is_read").value(true));
        verify(service).write(eq(USER), eq("article"), eq("fixture-article"),
                eq(new RecordsService.RecordWrite(1, "planned", "unassessed", "fixture note", null,
                        null, "article", true, false, false, null, null)),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
        mvc.perform(write(put("/private/records/node/fixture-node"),
                        "{\"expected_revision\":0,\"status\":\"learning\",\"self_assessment\":\"partial\","
                        + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\","
                        + "\"object_kind\":\"node\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(put("/private/records/question/fixture-question"),
                        "{\"expected_revision\":0,\"status\":\"learning\",\"self_assessment\":\"partial\","
                        + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\","
                        + "\"object_kind\":\"question\",\"latest_answer_text\":\"fixture answer\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(put("/private/records/project/fixture-project"),
                        "{\"expected_revision\":0,\"status\":\"completed\",\"self_assessment\":\"mastered\","
                        + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\","
                        + "\"object_kind\":\"project\",\"latest_submission_url\":\"https://work.example.test/fixture\"}"))
                .andExpect(status().isOk());
        verify(service).write(eq(USER), eq("project"), eq("fixture-project"),
                eq(new RecordsService.RecordWrite(0, "completed", "mastered", "", null,
                        "fixture-package", "project", null, null, null, null, "https://work.example.test/fixture")),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void rejectsMissingAuthenticationAndWrongRole() throws Exception {
        mvc.perform(get("/private/records")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-collector", "collector"));
        mvc.perform(auth(get("/private/records"))).andExpect(status().isForbidden());
        verify(service, never()).list(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsUnknownFieldsBranchConfusionAndBadIdentityBeforeMutation() throws Exception {
        mvc.perform(auth(get("/private/records?page=1&page=2"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/private/records?kind=unknown"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/private/records/article/" + "x".repeat(65)),
                        "{\"expected_revision\":0}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/private/records/article/fixture-article"),
                        "{\"expected_revision\":0,\"expected_revision\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/private/records/article/fixture-article"),
                        "{\"expected_revision\":0,\"status\":\"planned\",\"self_assessment\":\"unassessed\","
                        + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\","
                        + "\"object_kind\":\"article\",\"is_read\":true,\"is_favorite\":false,\"is_ignored\":false}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/private/records/question/fixture-question"),
                        "{\"expected_revision\":0,\"status\":\"planned\",\"self_assessment\":\"unassessed\","
                        + "\"note\":\"\",\"artifact_url\":null,\"package_id\":\"fixture-package\","
                        + "\"object_kind\":\"question\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(put("/private/records/node/fixture-node").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))).andExpect(status().isForbidden());
        verify(service, never()).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void absentServiceFailsClosedAndRefusalMapsConflict() throws Exception {
        mvc.perform(auth(get("/private/records"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        when(service.list(USER, null, null, 1, 20)).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.REVISION_CONFLICT, null));
        mvc.perform(auth(get("/private/records"))).andExpect(status().isConflict())
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
