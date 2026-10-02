package com.lianxibu.site.controller.jobs;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.JobScheduleReadService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobScheduleControllerTest {
    IdentityService auth;JobScheduleReadService service;MockMvc mvc;
    @BeforeEach @SuppressWarnings("unchecked") void fixture(){auth=mock(IdentityService.class);service=mock(JobScheduleReadService.class);ObjectProvider<IdentityService>a=mock(ObjectProvider.class);ObjectProvider<JobScheduleReadService>s=mock(ObjectProvider.class);when(a.getIfAvailable()).thenReturn(auth);when(s.getIfAvailable()).thenReturn(service);when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-owner","owner"));mvc=MockMvcBuilders.standaloneSetup(new JobScheduleController(a,s)).build();}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String suffix){return get("/admin/jobs/fictional-job/schedule"+suffix).cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session"));}
    @Test void readProviderRegistrationDoesNotOpenConnectionOrEnableExecution(){var source=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){context.registerBean(javax.sql.DataSource.class,()->source);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->tx);context.register(com.lianxibu.site.config.JobScheduleReadConfiguration.class);context.refresh();org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(JobScheduleReadService.class));verifyNoInteractions(source,tx);}}
    @Test void ownerGetsOnlyClosedSchedulingFactsAndUnknownRemainsNull()throws Exception{var now=Instant.parse("2026-10-02T21:00:00.000000Z");when(service.read("fictional-job")).thenReturn(Optional.of(new JobScheduleReadService.Schedule("fictional-job",0,null,null,now)));mvc.perform(request("")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.job_revision").value(0)).andExpect(jsonPath("$.data.observed_at").value("2026-10-02T21:00:00.000000Z")).andExpect(jsonPath("$.data.planned_period").isEmpty()).andExpect(jsonPath("$.data.first_queued_at").isEmpty()).andExpect(jsonPath("$.data.input_json").doesNotExist());}
    @Test void authorizationAndBadQueryPrecedeProofReads()throws Exception{mvc.perform(get("/admin/jobs/fictional-job/schedule")).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-user","user"));mvc.perform(request("")).andExpect(status().isForbidden());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-owner","owner"));mvc.perform(request("?unexpected=1")).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void missingIs404CorruptProofIs503AndCannotLeakInternalDetails()throws Exception{when(service.read("fictional-job")).thenReturn(Optional.empty());mvc.perform(request("")).andExpect(status().isNotFound());when(service.read("fictional-job")).thenThrow(new IllegalArgumentException("fictional-private-storage-detail"));mvc.perform(request("")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable")).andExpect(jsonPath("$.error.message").value("temporarily_unavailable"));}
    @Test void wrongJobAndFutureTimeCannotProduceSuccess()throws Exception{var now=Instant.parse("2026-10-02T21:00:00Z");for(var value:java.util.List.of(new JobScheduleReadService.Schedule("fictional-other",1,null,null,now),new JobScheduleReadService.Schedule("fictional-job",1,null,now.plusSeconds(1),now))){when(service.read("fictional-job")).thenReturn(Optional.of(value));mvc.perform(request("")).andExpect(status().isServiceUnavailable());}}
}
