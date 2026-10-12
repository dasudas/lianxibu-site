package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual owner job metadata persistence and HTTP; authentication is an explicit fixture port. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobsHttpMySqlTest {
    OwnerJobsReadMySqlTest f;MockMvc mvc;IdentityService identity;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){f=new OwnerJobsReadMySqlTest();f.setup();identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(f.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(f.service,null));mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,jobs)).build();}
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    @Test void actualRootAttemptAndPageMetadataExposeNoRawInput()throws Exception{f.attempts();var result=mvc.perform(get("/admin/jobs/"+f.f.root).cookie(cookie())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.call_count").value(2)).andExpect(jsonPath("$.data.remaining_attempts").value(1)).andExpect(jsonPath("$.data.attempts.length()").value(3)).andExpect(jsonPath("$.data.coverage.missing_inputs[0].id").value("fictional-dependency")).andReturn();assertFalse(result.getResponse().getContentAsString().contains("body sentinel"));assertFalse(result.getResponse().getContentAsString().contains("bytes_base64"));mvc.perform(get("/admin/jobs/fictional-attempt-2").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.call_count").value(0)).andExpect(jsonPath("$.data.max_attempts").value(org.hamcrest.Matchers.nullValue()));mvc.perform(get("/admin/jobs?record_kind=logical_task&page_size=1").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));}
    @Test void originalReportCoverageDatesSurviveHttp()throws Exception{f.f.save(f.f.report());mvc.perform(get("/admin/jobs/"+f.f.root).cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.content_id").value("fictional-report-content")).andExpect(jsonPath("$.data.coverage.no_update_dates[0]").value("2025-12-29")).andExpect(jsonPath("$.data.coverage.missing_dates.length()").value(6));}
    @Test void lostArchiveIs503AndMissingJobIs404()throws Exception{mvc.perform(get("/admin/jobs/fictional-absent").cookie(cookie())).andExpect(status().isNotFound());f.f.save(f.f.generic());f.f.f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE record_kind='snapshot_part'",new byte[32]);mvc.perform(get("/admin/jobs/"+f.f.root).cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable"));}
    @Test void ownerAndCsrfRefuseBeforePersistenceAndAbsentWriteProviderCannotSucceed()throws Exception{f.f.f.f.jdbc.execute("DROP TABLE jobs");mvc.perform(get("/admin/jobs")).andExpect(status().isUnauthorized());when(identity.authenticate("fictional-session")).thenReturn(new Actor("fictional-user","member"));mvc.perform(get("/admin/jobs").cookie(cookie())).andExpect(status().isForbidden());when(identity.authenticate("fictional-session")).thenReturn(f.owner);mvc.perform(post("/admin/jobs").cookie(cookie()).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(post("/admin/jobs").cookie(cookie()).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key","fictional-job-command").contentType("application/json").content("{\"task_type\":\"ad_hoc_ai\",\"target\":{\"kind\":\"ad_hoc_request\",\"id\":\"fictional-question\",\"package_id\":null},\"expected_revision\":0,\"reason\":\"Fictional request\",\"request\":{\"prompt\":\"Fictional original question\"}}")).andExpect(status().isServiceUnavailable());}
}
