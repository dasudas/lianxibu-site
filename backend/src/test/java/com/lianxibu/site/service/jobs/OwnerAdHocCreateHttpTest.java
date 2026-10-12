package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Real native acceptance and HTTP command consumer; authentication alone is fictional. */
class OwnerAdHocCreateHttpTest {
    OwnerAdHocCreationTest f;MockMvc mvc;IdentityService auth;
    OwnerAdHocCreationTest database(){return new OwnerAdHocCreationTest();}
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        f=database();f.setup();auth=mock(IdentityService.class);when(auth.authenticate("fictional-session")).thenReturn(f.owner);when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);
        ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);
        var cancel=new OwnerJobCancelService(f.f.writes,new JdbcOwnerJobCancellation(f.f.source),f.f.clock);var commands=new OwnerJobCommandsService(cancel,null,null,null,null,null,f.create);
        when(jobs.getIfAvailable()).thenReturn(new OwnerJobsService(mock(OwnerJobsReadService.class),commands));mvc=MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();
    }
    String body(String target,String prompt){var n=JsonMapper.shared().createObjectNode().put("task_type","ad_hoc_ai").put("expected_revision",0).put("reason","Fictional original reason");n.putObject("target").put("kind","ad_hoc_request").put("id",target).putNull("package_id");n.putObject("request").put("prompt",prompt);return JdbcFrozenInputs.canonical(n);}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body,String key){return post("/admin/jobs").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content(body);}
    @Test void ActualInitialIntentReceiptAndOriginalReplayAreIndependentOfLaterState()throws Exception{
        String command=body("fictional-question","Fictional exact original question");var first=mvc.perform(request(command,"fictional-create-http")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.task_type").value("ad_hoc_ai")).andExpect(jsonPath("$.data.record_kind").value("generation_intent")).andExpect(jsonPath("$.data.state").value("waiting_input")).andExpect(jsonPath("$.data.revision").value(0)).andExpect(jsonPath("$.data.call_count").value(0)).andReturn();
        var data=JsonMapper.shared().readTree(first.getResponse().getContentAsString()).get("data");String id=data.get("id").asText();f.f.jdbc.update("UPDATE jobs SET state='queued',revision=1 WHERE id=?",id);f.allowed=false;
        var replay=mvc.perform(request(command,"fictional-create-http")).andExpect(status().isOk()).andReturn();assertEquals(JdbcFrozenInputs.canonical(data),JdbcFrozenInputs.canonical(JsonMapper.shared().readTree(replay.getResponse().getContentAsString()).get("data")));assertEquals(1,f.freezes);assertEquals(0,f.f.count("record_kind='logical_task' OR record_kind='ai_attempt'"));
    }
    @Test void ExactPromptCannotResetBudgetThroughAnotherTargetAndKey()throws Exception{
        String command=body("fictional-question","Fictional exact original question");mvc.perform(request(command,"fictional-create-http")).andExpect(status().isOk());mvc.perform(request(body("fictional-other","Fictional exact original question"),"fictional-other-key")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("input_unchanged"));mvc.perform(request(body("fictional-other","Fictional different question"),"fictional-create-http")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("identity_conflict"));assertEquals(1,f.f.count("record_kind='generation_intent'"));
    }
    @Test void ClosedRequestInvalidPromptAndBadNativeArchiveHaveFixedBoundaries()throws Exception{
        String command=body("fictional-question","Fictional exact original question");mvc.perform(request(command.replace("\"request\":{","\"request\":{\"extra\":true,"),"fictional-extra")).andExpect(status().isBadRequest());mvc.perform(request(body("fictional-question"," \n\t"),"fictional-blank")).andExpect(status().isUnprocessableEntity());mvc.perform(request(body("fictional-question","x".repeat(32769)),"fictional-long")).andExpect(status().isUnprocessableEntity());assertEquals(0,f.f.count("1=1"));
        mvc.perform(request(command,"fictional-create-http")).andExpect(status().isOk());assertEquals(1,f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]));var response=mvc.perform(request(command,"fictional-create-http")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andReturn().getResponse().getContentAsString();assertFalse(response.contains("SQL"));assertFalse(response.contains("Fictional exact original question"));
    }
    @Test void LiteralNewQuestionTargetAndLegacyFourFieldBoundaryRemainClosed()throws Exception{
        String valid=body("fictional-literal","Fictional literal question");
        for(String body:java.util.List.of(valid.replace("\"expected_revision\":0","\"expected_revision\":1"),valid.replace("ad_hoc_request","article"),valid.replace("\"package_id\":null","\"package_id\":\"fictional-package\""))){mvc.perform(request(body,"fictional-invalid-literal")).andExpect(status().isUnprocessableEntity());}
        mvc.perform(request("{}","fictional-missing-task")).andExpect(status().isBadRequest());
        String legacy="{\"task_type\":\"daily_report\",\"target\":{\"kind\":\"report_day\",\"id\":\"fictional-day\",\"package_id\":null},\"expected_revision\":0,\"reason\":\"Fictional legacy reason\"}";
        mvc.perform(request(legacy,"fictional-legacy")).andExpect(status().isServiceUnavailable());
        mvc.perform(request(legacy.substring(0,legacy.length()-1)+",\"request\":{\"prompt\":\"fictional\"}}","fictional-extra-legacy")).andExpect(status().isBadRequest());
        assertEquals(0,f.f.count("1=1"));
    }
    @Test void OwnerAuthorizationPrecedesParsingAndAnyNativeSql()throws Exception{
        f.f.jdbc.execute("DROP TABLE jobs");mvc.perform(post("/admin/jobs").contentType("application/json").content("{" )).andExpect(status().isUnauthorized());when(auth.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-member","member"));mvc.perform(request("{","fictional-denied")).andExpect(status().isForbidden());
    }
}
