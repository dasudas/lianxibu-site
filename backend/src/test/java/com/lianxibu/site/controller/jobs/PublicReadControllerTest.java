package com.lianxibu.site.controller.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.jobs.PublicReadService;
import com.lianxibu.site.service.learning.LearningService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicReadControllerTest {
    @Test @SuppressWarnings("unchecked")
    void FormalConfiguredGraphIsPublicMetadataWithoutChangingTheFrozenProtocol() throws Exception {
        ObjectProvider<PublicReadService> reads=mock(ObjectProvider.class);
        ObjectProvider<com.lianxibu.site.service.jobs.RuntimeCapabilities> features=mock(ObjectProvider.class);
        when(features.getIfAvailable()).thenReturn(new com.lianxibu.site.service.jobs.RuntimeCapabilities());
        var mvc=MockMvcBuilders.standaloneSetup(new PublicReadController(reads,features)).build();
        mvc.perform(get("/capabilities")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.protocol_revision").value("0.12"))
            .andExpect(jsonPath("$.data.contract_version").value("0.15"))
            .andExpect(jsonPath("$.data.supported_features[0]").value("formal-business-v1"));
        org.mockito.Mockito.verifyNoInteractions(reads);
    }
    @Test
    void capabilitiesDeclareOnlyCurrentProtocolAndNoUnimplementedFeatures() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(get("/capabilities")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.protocol_revision").value("0.12"))
                .andExpect(jsonPath("$.data.contract_version").value("0.15"))
                .andExpect(jsonPath("$.data.supported_features").isEmpty())
                .andExpect(jsonPath("$.data.max_page_size").value(50))
                .andExpect(jsonPath("$.data.max_observations").value(10));
        mvc.perform(get("/capabilities?unexpected=1")).andExpect(status().isBadRequest());
    }

    @Test
    void homeRequiresServiceAndRejectsHiddenRows() throws Exception {
        mvc(null).perform(get("/home")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
        PublicReadService service = mock(PublicReadService.class);
        var coverage = new ContentService.Coverage("complete", List.of(), List.of(), List.of());
        var report = new ContentService.ContentView("fixture-report", "report", "Fixture", 1, 1,
                "published", Instant.parse("2026-09-28T00:00:00Z"), null, null, null, coverage,
                false, List.of(), false, null, null,
                new ContentService.ReportBody("Fixture", List.of(), List.of(), "daily",
                        Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"),
                        "Asia/Shanghai", null), List.of());
        when(service.home()).thenReturn(new PublicReadService.HomeSnapshot(List.of(report),
                List.of(new LearningService.PackageSummary("fixture-package", "Fixture", "", 1,
                        "published")), "fixture-package"));
        mvc(service).perform(get("/home")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reports[0].id").value("fixture-report"))
                .andExpect(jsonPath("$.data.learning_packages[0].id").value("fixture-package"))
                .andExpect(jsonPath("$.data.default_package_id").value("fixture-package"));
        when(service.home()).thenReturn(new PublicReadService.HomeSnapshot(List.of(report),
                List.of(new LearningService.PackageSummary("fixture-package", "Fixture", "", 1,
                        "draft")), null));
        mvc(service).perform(get("/home")).andExpect(status().isServiceUnavailable());
        when(service.home()).thenReturn(new PublicReadService.HomeSnapshot(List.of(report),
                List.of(), "fixture-package"));
        mvc(service).perform(get("/home")).andExpect(status().isServiceUnavailable());
        mvc(service).perform(get("/home?unexpected=1")).andExpect(status().isBadRequest());
    }

    @SuppressWarnings("unchecked")
    private static MockMvc mvc(PublicReadService service) {
        ObjectProvider<PublicReadService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return MockMvcBuilders.standaloneSetup(new PublicReadController(provider)).build();
    }
}
