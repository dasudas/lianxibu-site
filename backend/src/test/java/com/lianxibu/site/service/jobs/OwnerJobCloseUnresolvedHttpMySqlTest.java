package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobCancellation;
import com.lianxibu.site.service.identity.IdentityService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual durable unknown closure HTTP; authentication is an explicit fixture port. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobCloseUnresolvedHttpMySqlTest {
    OwnerJobCloseUnresolvedMySqlTest f;MockMvc mvc;IdentityService auth;
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
        f=new OwnerJobCloseUnresolvedMySqlTest();f.setup();auth=mock(IdentityService.class);
        when(auth.authenticate("fictional-session")).thenReturn(f.owner);
        when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);
        ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);
        var cancel=new OwnerJobCancelService(f.f.db.writes,new JdbcOwnerJobCancellation(f.f.db.source),f.f.f.f.clock);
        var views=new com.lianxibu.site.repository.jobs.JdbcOwnerJobViews(f.f.db.source);
        var reads=new OwnerJobsReadService(views,new OwnerJobInputMetadataService(views,new com.lianxibu.site.repository.jobs.JdbcFrozenInputs(f.f.db.source)),new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.db.source));
        ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(reads,new OwnerJobCommandsService(cancel,f.service)));
        mvc=MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();
    }
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String root,String attempt,long revision,String key){return post("/admin/jobs/"+root+"/actions").cookie(cookie()).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content("{\"expected_revision\":"+revision+",\"action\":\"close_unresolved\",\"reason\":\"Fictional unresolved closure\",\"attempt_id\":\""+attempt+"\"}");}
    @Test void actualOriginalClosureResponseReplaysAfterNativeRevisionChanges()throws Exception{
        var first=mvc.perform(action(f.f.root,f.identity.attemptId(),f.revision,"fictional-http-close!")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.result_resource_ref.id").value(f.f.root)).andExpect(jsonPath("$.data.result_revision").value(f.revision+1)).andReturn();
        f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.root);
        var replay=mvc.perform(action(f.f.root,f.identity.attemptId(),f.revision,"fictional-http-close!")).andExpect(status().isOk()).andReturn();
        var mapper=tools.jackson.databind.json.JsonMapper.shared();assertEquals(mapper.readTree(first.getResponse().getContentAsString()).get("data"),mapper.readTree(replay.getResponse().getContentAsString()).get("data"));
        assertEquals(1,f.f.db.count("target_type='worker_recovery'"));assertEquals(1,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());
        assertEquals("unknown_external",new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.identity.attemptId()).state());
    }
    @Test void actualMissingRootStaleRevisionAndWrongAttemptHaveFixedRefusals()throws Exception{
        mvc.perform(action("fictional-absent",f.identity.attemptId(),f.revision,"fictional-missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(action(f.f.root,f.identity.attemptId(),f.revision+1,"fictional-stale")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("revision_conflict"));
        mvc.perform(action(f.f.root,"fictional-wrong-attempt",f.revision,"fictional-wrong")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("run_frozen"));assertEquals(0,f.f.db.count("target_type='worker_recovery'"));
    }
    @Test void authAndCsrfPrecedeSqlAndPersistenceFailureIsRedacted()throws Exception{
        f.f.db.jdbc.execute("DROP TABLE jobs");
        mvc.perform(post("/admin/jobs/fictional-root/actions").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-member","member"));mvc.perform(action(f.f.root,f.identity.attemptId(),f.revision,"fictional-role")).andExpect(status().isForbidden());
        when(auth.authenticate("fictional-session")).thenReturn(f.owner);mvc.perform(post("/admin/jobs/"+f.f.root+"/actions").cookie(cookie()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var response=mvc.perform(action(f.f.root,f.identity.attemptId(),f.revision,"fictional-sql")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable")).andReturn().getResponse().getContentAsString();assertFalse(response.contains("SELECT"));assertFalse(response.contains("SQLException"));
    }
}
