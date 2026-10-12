package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Original input recovery HTTP with actual day/native providers and explicit fixture identity. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobRecoverHttpMySqlTest {
    OwnerJobRecoverMySqlTest f;MockMvc mvc;IdentityService auth;
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        f=new OwnerJobRecoverMySqlTest();f.setup();auth=mock(IdentityService.class);when(auth.authenticate("fictional-session")).thenReturn(f.owner);when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);
        ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);
        var cancel=new OwnerJobCancelService(f.f.db.writes,new JdbcOwnerJobCancellation(f.f.db.source),f.f.f.f.clock);var views=new JdbcOwnerJobViews(f.f.db.source);var reads=new OwnerJobsReadService(views,new OwnerJobInputMetadataService(views,new JdbcFrozenInputs(f.f.db.source)),f.f.f.day.manager);
        when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(reads,new OwnerJobCommandsService(cancel,null,f.service)));mvc=MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();
    }
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String root,long revision,String key){return post("/admin/jobs/"+root+"/actions").cookie(cookie()).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content("{\"expected_revision\":"+revision+",\"action\":\"recover\",\"reason\":\"Fictional original input recovery\"}");}
    @Test void originalRecoveryHttpReplaysOneEffectAndPreservesUsedBudget()throws Exception{var result=mvc.perform(action(f.f.root,f.revision,"fictional-http-recovery!")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.result_resource_ref.id").value(f.f.root)).andExpect(jsonPath("$.data.result_revision").value(f.revision+1)).andReturn();f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.root);f.f.loseRegistration();var replay=mvc.perform(action(f.f.root,f.revision,"fictional-http-recovery!")).andExpect(status().isOk()).andReturn();var mapper=tools.jackson.databind.json.JsonMapper.shared();assertEquals(mapper.readTree(result.getResponse().getContentAsString()).get("data"),mapper.readTree(replay.getResponse().getContentAsString()).get("data"));assertEquals(1,f.f.db.count("target_type='job_recover'"));assertEquals(1,new JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());assertEquals(1,f.f.db.count("target_type='worker_send'"));}
    @Test void nativeUnknownExhaustionRevisionAndMissingRootUsePreciseHttpCodes()throws Exception{mvc.perform(action(f.f.root,f.revision+1,"fictional-stale")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("revision_conflict"));mvc.perform(action("fictional-absent",0,"fictional-absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("not_found"));f.f.db.jdbc.update("UPDATE jobs SET call_count=3 WHERE id=?",f.f.root);mvc.perform(action(f.f.root,f.revision,"fictional-exhausted")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("retry_exhausted"));f.f.db.jdbc.update("UPDATE jobs SET call_count=1,state='unknown_external' WHERE id=?",f.f.root);mvc.perform(action(f.f.root,f.revision,"fictional-unknown")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("external_result_unresolved"));assertEquals(0,f.f.db.count("target_type='job_recover'"));}
    @Test void authCsrfBranchFieldsAndSqlFailureRefuseWithFixedErrors()throws Exception{mvc.perform(action(f.f.root,f.revision,"fictional-invalid-fields").content("{\"expected_revision\":"+f.revision+",\"action\":\"recover\",\"reason\":\"Fictional invalid branch\",\"attempt_id\":\"fictional-attempt\"}")).andExpect(status().isBadRequest());f.f.db.jdbc.execute("DROP TABLE jobs");mvc.perform(post("/admin/jobs/fictional-root/actions").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-member","member"));mvc.perform(action(f.f.root,f.revision,"fictional-role")).andExpect(status().isForbidden());when(auth.authenticate("fictional-session")).thenReturn(f.owner);mvc.perform(post("/admin/jobs/"+f.f.root+"/actions").cookie(cookie()).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(action(f.f.root,f.revision,"fictional-sql")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable"));}
}
