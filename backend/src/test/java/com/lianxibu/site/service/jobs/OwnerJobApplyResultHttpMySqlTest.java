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

/** Actual native factory, reference ledger, result application and downstream; identity is a fictional port. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerJobApplyResultHttpMySqlTest {
    OwnerJobApplyResultMySqlTest t;MockMvc mvc;IdentityService auth;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){t=new OwnerJobApplyResultMySqlTest();t.setup();auth=mock(IdentityService.class);when(auth.authenticate("fictional-session")).thenReturn(t.owner);when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(t.owner);
        ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);
        var cancel=new OwnerJobCancelService(t.f.writes,new JdbcOwnerJobCancellation(t.f.source),t.f.clock);
        when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(mock(OwnerJobsReadService.class),new OwnerJobCommandsService(cancel,null,null,null,t.service())));mvc=MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();
    }
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    String command(long revision,long content,String hash){return "{\"expected_revision\":"+revision+",\"action\":\"apply_result\",\"reason\":\"Fictional reviewed original result\",\"expected_content_revision\":"+content+",\"result_hash\":\""+hash+"\"}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String root,long revision,long content,String hash,String key){return post("/admin/jobs/"+root+"/actions").cookie(cookie()).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content(command(revision,content,hash));}
    @Test void realHttpApplicationReturnsOriginalReceiptAfterLaterProjectionChanges()throws Exception{long original=t.revision();var first=mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,t.hash(),"fictional-apply-http!")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.result_resource_ref.kind").value("job")).andExpect(jsonPath("$.data.result_resource_ref.id").value(ResultJdbcStoreTest.ROOT)).andExpect(jsonPath("$.data.result_revision").value(original+1)).andExpect(jsonPath("$.data.affected.length()").value(2)).andReturn();
        t.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);t.f.jdbc.update("UPDATE contents SET revision=revision+5");var replay=mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,t.hash(),"fictional-apply-http!")).andExpect(status().isOk()).andReturn();var mapper=tools.jackson.databind.json.JsonMapper.shared();assertEquals(mapper.readTree(first.getResponse().getContentAsString()).get("data"),mapper.readTree(replay.getResponse().getContentAsString()).get("data"));assertFalse(first.getResponse().getContentAsString().contains("Fictional text"));assertEquals(1,t.f.sends);assertEquals(1,new JdbcJobRecords(t.f.source).byId(ResultJdbcStoreTest.ROOT).callCount());
    }
    @Test void missingStaleHashAndSupersededHaveFixedBusinessErrors()throws Exception{long original=t.revision();mvc.perform(action("fictional-missing",original,1,t.hash(),"fictional-missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("not_found"));mvc.perform(action(ResultJdbcStoreTest.ROOT,original+1,1,t.hash(),"fictional-stale-root")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("revision_conflict"));mvc.perform(action(ResultJdbcStoreTest.ROOT,original,0,t.hash(),"fictional-stale-content")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("revision_conflict"));mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,"0".repeat(64),"fictional-bad-hash")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("identity_conflict"));
        t.f.writes.execute(t.f.action("fictional-http-generation"),k->null,c->{new JdbcJobRecords(t.f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-http-next-intent",t.f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),t.f.clock.instant());return true;});mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,t.hash(),"fictional-old-generation")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("superseded"));assertEquals(original,t.revision());
    }
    @Test void wrongBranchAndAuthCsrfBeforeSqlDamageKeepFixedBoundaries()throws Exception{long original=t.revision();String hash=t.hash();mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,hash,"fictional-extra-field").content(command(original,1,hash).replace("}",",\"attempt_id\":\"fictional-attempt\"}"))).andExpect(status().isBadRequest());t.f.jdbc.execute("DROP TABLE jobs");
        mvc.perform(post("/admin/jobs/fictional-root/actions").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-reader","user"));mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,hash,"fictional-role")).andExpect(status().isForbidden());when(auth.authenticate("fictional-session")).thenReturn(t.owner);mvc.perform(post("/admin/jobs/fictional-root/actions").cookie(cookie()).contentType("application/json").content("{}")).andExpect(status().isForbidden());String body=mvc.perform(action(ResultJdbcStoreTest.ROOT,original,1,hash,"fictional-storage")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error.code").value("temporarily_unavailable")).andReturn().getResponse().getContentAsString();assertFalse(body.contains("SQL"));assertFalse(body.contains("retained"));
    }
}
