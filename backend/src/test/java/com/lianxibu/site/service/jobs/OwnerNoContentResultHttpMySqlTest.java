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

/** Actual native no-content factory/core; only authentication is a fictional port. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerNoContentResultHttpMySqlTest {
    OwnerNoContentResultMySqlTest t;MockMvc mvc;IdentityService auth;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){t=new OwnerNoContentResultMySqlTest();t.setup();auth=mock(IdentityService.class);when(auth.authenticate("fictional-session")).thenReturn(t.owner);when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(t.owner);ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);var cancel=new OwnerJobCancelService(t.f.writes,new JdbcOwnerJobCancellation(t.f.source),t.f.clock);when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(mock(OwnerJobsReadService.class),new OwnerJobCommandsService(cancel,null,null,null,t.ownerService())));mvc=MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();}
    String command(long revision,Long content){return "{\"expected_revision\":"+revision+",\"action\":\"apply_result\",\"reason\":\"Fictional separate native application\",\"expected_content_revision\":"+content+",\"result_hash\":\""+t.hash+"\"}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String body,String key){return post("/admin/jobs/"+ResultJdbcStoreTest.ROOT+"/actions").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content(body);}
    @Test void NullContentHttpAppliesNativeTemporaryAnswerAndReplaysOneOriginalReceipt() throws Exception {long before=t.revision();String body=command(before,null);var first=mvc.perform(action(body,"fictional-null-http")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.result_revision").value(before+1)).andExpect(jsonPath("$.data.affected.length()").value(1)).andExpect(jsonPath("$.data.affected[0].kind").value("job")).andReturn();t.invalidate();var replay=mvc.perform(action(body,"fictional-null-http")).andExpect(status().isOk()).andReturn();var mapper=tools.jackson.databind.json.JsonMapper.shared();assertEquals(mapper.readTree(first.getResponse().getContentAsString()).get("data"),mapper.readTree(replay.getResponse().getContentAsString()).get("data"));assertEquals(0,t.f.count("contents"));assertEquals(1,t.f.sends);}
    @Test void RequiredNullableFieldRejectsMissingOrInventedZeroAndInvalidNativeReference() throws Exception {long before=t.revision();mvc.perform(action(command(before,0L),"fictional-null-zero")).andExpect(status().isUnprocessableEntity());mvc.perform(action(command(before,null).replace("\"expected_content_revision\":null,",""),"fictional-null-missing")).andExpect(status().isUnprocessableEntity());mvc.perform(action(command(before,null).replace("}",",\"attempt_id\":\"fictional-extra\"}"),"fictional-null-extra")).andExpect(status().isBadRequest());t.invalidate();mvc.perform(action(command(before,null),"fictional-null-invalid")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("invalid_evidence"));assertEquals(before,t.revision());}
    @Test void OwnerAuthAndFixedSqlFailureRemainClosed() throws Exception {String body=command(t.revision(),null);t.f.jdbc.execute("DROP TABLE jobs");mvc.perform(post("/admin/jobs/fictional-root/actions").contentType("application/json").content(body)).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-member","member"));mvc.perform(action(body,"fictional-null-role")).andExpect(status().isForbidden());when(auth.authenticate("fictional-session")).thenReturn(t.owner);String response=mvc.perform(action(body,"fictional-null-sql")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("temporarily_unavailable")).andReturn().getResponse().getContentAsString();assertFalse(response.contains("SQL"));}
}
