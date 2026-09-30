package com.lianxibu.site.controller.settings;

import static org.mockito.ArgumentMatchers.any;
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
import com.lianxibu.site.service.settings.SettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SettingsControllerTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String ORIGIN = "https://site.example.invalid";
    private static final SettingsService.OperationalRule RULE = new SettingsService.OperationalRule(
            "fixture-rule", "2", "5", "1", null, false);
    private static final SettingsService.Preferences PREFS = new SettingsService.Preferences(1,
            List.of("fixture"), List.of(), true, "fixture-package", List.of(RULE));
    private SettingsService service;
    private IdentityService identity;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = mock(SettingsService.class);
        identity = mock(IdentityService.class);
        ObjectProvider<SettingsService> settingsProvider = mock(ObjectProvider.class);
        ObjectProvider<IdentityService> identityProvider = mock(ObjectProvider.class);
        when(settingsProvider.getIfAvailable()).thenReturn(service);
        when(identityProvider.getIfAvailable()).thenReturn(identity);
        when(identity.authenticate("fixture-session")).thenReturn(OWNER);
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(identityProvider, settingsProvider)).build();
    }

    @Test
    void readAndWriteMapExactFieldsAndFixedSchedule() throws Exception {
        when(service.get(OWNER)).thenReturn(PREFS);
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.follow_keywords[0]").value("fixture"))
                .andExpect(jsonPath("$.data.operational_limits[0].rule_id").value("fixture-rule"))
                .andExpect(jsonPath("$.data.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.collection_time").value("00:10"));
        when(service.write(eq(OWNER), any(), eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN)))
                .thenReturn(PREFS);
        mvc.perform(write(put("/admin/preferences"), "{\"expected_revision\":1,"
                        + "\"follow_keywords\":[\"fixture\"],\"blocked_keywords\":[],"
                        + "\"ai_paused\":true,\"default_package_id\":\"fixture-package\","
                        + "\"operational_limits\":[{\"rule_id\":\"fixture-rule\","
                        + "\"warning_threshold\":\"2\",\"critical_threshold\":\"5\","
                        + "\"recovery_threshold\":\"1\",\"notification_secret_ref\":null,"
                        + "\"notification_configured\":false}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        verify(service).write(eq(OWNER), eq(new SettingsService.PreferencesWrite(1, List.of("fixture"),
                List.of(), true, "fixture-package", List.of(RULE))),
                eq("fixture-key"), eq("fixture-csrf"), eq(ORIGIN));
    }

    @Test
    void rejectsMissingAuthenticationAndWrongRole() throws Exception {
        mvc.perform(get("/admin/preferences")).andExpect(status().isUnauthorized());
        when(identity.authenticate("fixture-session")).thenReturn(new Actor("fixture-user", "user"));
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isForbidden());
        verify(service, never()).get(any());
    }

    @Test
    void unknownFixedFieldsAndMalformedRulesNeverReachService() throws Exception {
        mvc.perform(write(put("/admin/preferences"),
                        "{\"expected_revision\":1,\"expected_revision\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/admin/preferences"),
                        "{\"expected_revision\":1,\"follow_keywords\":[],\"blocked_keywords\":[],"
                        + "\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[],"
                        + "\"model\":\"other\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put("/admin/preferences"),
                        "{\"expected_revision\":1,\"follow_keywords\":[],\"blocked_keywords\":[],"
                        + "\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[{"
                        + "\"rule_id\":\"fixture-rule\",\"warning_threshold\":\"not-number\","
                        + "\"critical_threshold\":\"5\",\"recovery_threshold\":\"1\","
                        + "\"notification_secret_ref\":null,\"notification_configured\":false}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(put("/admin/preferences").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden());
        verify(service, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void absentServiceAndConflictHaveContractStatus() throws Exception {
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        when(service.get(OWNER)).thenThrow(new IdentityService.Rejected(
                IdentityService.Refusal.REVISION_CONFLICT, null));
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("revision_conflict"));
    }

    @Test
    void optionalOperationalFieldsRoundTripWithoutCoercion() throws Exception {
        var rule = new SettingsService.OperationalRule("OPS-04", "15", "10", "15", null, false,
                java.util.Map.of("protect_free_percent", "5"));
        var prefs = new SettingsService.Preferences(2, List.of(), List.of(), true, null, List.of(rule));
        when(service.write(eq(OWNER), any(), any(), any(), any())).thenReturn(prefs);
        mvc.perform(write(put("/admin/preferences"), operationalBody("{\"protect_free_percent\":\"5\"}",
                        ",\"operational_change_reason\":\"Fictional change\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operational_limits[0].rule_parameters.protect_free_percent").value("5"));
        verify(service).write(eq(OWNER), eq(new SettingsService.PreferencesWrite(1, List.of(), List.of(),
                true, null, List.of(rule), "Fictional change")), any(), any(), any());
        when(service.get(OWNER)).thenReturn(PREFS);
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operational_limits[0].rule_parameters").doesNotExist());
    }

    @Test
    void rejectsMalformedParameterMapsAndReasonBeforeService() throws Exception {
        for (String parameters : List.of("null", "[]", "{\"bad-key\":\"5\"}",
                "{\"protect_free_percent\":5}", "{\"protect_free_percent\":true}",
                "{\"protect_free_percent\":\"-1\"}", "{\"protect_free_percent\":\"1e2\"}",
                "{\"protect_free_percent\":\"15 / 3\"}", "{\"protect_free_percent\":null}")) {
            mvc.perform(write(put("/admin/preferences"), operationalBody(parameters, "")))
                    .andExpect(status().isUnprocessableEntity());
        }
        String excessive = java.util.stream.IntStream.range(0, 33)
                .mapToObj(i -> "\"key_" + i + "\":\"1\"").collect(java.util.stream.Collectors.joining(",", "{", "}"));
        mvc.perform(write(put("/admin/preferences"), operationalBody(excessive, "")))
                .andExpect(status().isUnprocessableEntity());
        for (String reason : List.of("null", "0", "\"\"", "\"" + "x".repeat(2049) + "\"")) {
            mvc.perform(write(put("/admin/preferences"), operationalBody("{}", ",\"operational_change_reason\":" + reason)))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(write(put("/admin/preferences"), operationalBody(
                "{\"key\":\"1\",\"key\":\"2\"}", ""))).andExpect(status().isBadRequest());
        verify(service, never()).write(any(), any(), any(), any(), any());
    }

    private static String operationalBody(String parameters, String reason) {
        return "{\"expected_revision\":1,\"follow_keywords\":[],\"blocked_keywords\":[],"
                + "\"ai_paused\":true,\"default_package_id\":null,\"operational_limits\":[{"
                + "\"rule_id\":\"OPS-04\",\"warning_threshold\":\"15\",\"critical_threshold\":\"10\","
                + "\"recovery_threshold\":\"15\",\"notification_secret_ref\":null,"
                + "\"notification_configured\":false,\"rule_parameters\":" + parameters + "}]" + reason + "}";
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session", "fixture-session"));
    }
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, String body) {
        return auth(request).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token", "fixture-csrf")
                .header("Origin", ORIGIN).header("Idempotency-Key", "fixture-key").content(body);
    }
}
