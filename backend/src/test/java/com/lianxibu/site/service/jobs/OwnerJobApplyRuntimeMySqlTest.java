package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.jobs.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual formal Spring comparison/application/reference ledger and native MySQL; identity is fictional. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerJobApplyRuntimeMySqlTest {
    OwnerJobApplyResultMySqlTest t;
    @BeforeEach void setup(){t=new OwnerJobApplyResultMySqlTest();t.setup();t.f.jdbc.execute("ALTER TABLE article_revisions ADD article_id VARCHAR(64)");t.f.jdbc.execute("DROP TABLE IF EXISTS articles");t.f.jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY)");t.f.jdbc.update("INSERT INTO articles VALUES('fictional-article')");t.f.jdbc.update("UPDATE article_revisions SET article_id='fictional-article'");}
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class,JobResultComparisonRuntimeConfiguration.class).withBean(javax.sql.DataSource.class,()->t.f.source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->t.f.tx).withBean(SettingsJdbcStore.class,()->mock(SettingsJdbcStore.class)).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true");}
    @SuppressWarnings("unchecked") MockMvc mvc(ObjectProvider<JobsService> jobs,ObjectProvider<JobResultComparisonReadService> comparison){var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(t.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(t.owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);return MockMvcBuilders.standaloneSetup(new JobsController(auth,jobs),new JobResultComparisonController(auth,comparison)).build();}
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(long expected,String key){return post("/admin/jobs/"+ResultJdbcStoreTest.ROOT+"/actions").cookie(cookie()).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content("{\"expected_revision\":"+expected+",\"action\":\"apply_result\",\"reason\":\"Fictional runtime owner confirmation\",\"expected_content_revision\":1,\"result_hash\":\""+t.hash()+"\"}");}
    void invalidate(){var helper=new OwnerResultComparisonRuntimeMySqlTest();helper.t=new OwnerResultComparisonMySqlTest();helper.t.f=t.f;helper.invalidateCitation();}
    @Test void actualSpringGetAndPostApplyOnceAndReturnOriginalReplyAfterRevisionChange(){context().run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertEquals(1,c.getBeansOfType(OwnerJobApplyResultService.class).size());var api=mvc(c.getBeanProvider(JobsService.class),c.getBeanProvider(JobResultComparisonReadService.class));long before=t.revision();api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(true));var first=api.perform(action(before,"fictional-runtime-apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(before+1)).andReturn();t.f.jdbc.update("UPDATE jobs SET revision=revision+3 WHERE id=?",ResultJdbcStoreTest.ROOT);var replay=api.perform(action(before,"fictional-runtime-apply")).andExpect(status().isOk()).andReturn();var mapper=tools.jackson.databind.json.JsonMapper.shared();assertEquals(mapper.readTree(first.getResponse().getContentAsString()).get("data"),mapper.readTree(replay.getResponse().getContentAsString()).get("data"));api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false));assertEquals("draft",t.f.jdbc.queryForObject("SELECT state FROM contents",String.class));assertEquals(1,t.f.sends);});}
    @Test void earlierReadCannotOverrideActualPermanentReferenceInvalidation(){context().run(c->{var api=mvc(c.getBeanProvider(JobsService.class),c.getBeanProvider(JobResultComparisonReadService.class));long before=t.revision();api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(true));invalidate();api.perform(action(before,"fictional-runtime-invalid")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("invalid_evidence"));assertEquals(before,t.revision());assertEquals(1,t.f.sends);});}
    @Test void nativeFlagWithoutLedgerIsUnavailableAndNeverAuthorizesApplication(){context().run(c->{var api=mvc(c.getBeanProvider(JobsService.class),c.getBeanProvider(JobResultComparisonReadService.class));long before=t.revision();t.f.jdbc.update("UPDATE article_revisions SET effective_valid=false,validity_revision=validity_revision+1");api.perform(action(before,"fictional-runtime-damaged-proof")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"));assertEquals(before,t.revision());assertEquals(1,t.f.sends);});}
}
