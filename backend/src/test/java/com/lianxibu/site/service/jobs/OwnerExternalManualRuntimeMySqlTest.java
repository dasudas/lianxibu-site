package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerExternalManualRuntimeMySqlTest {
    ResultJdbcStoreTest f;final IdentityService.Actor owner=new IdentityService.Actor("fictional-owner","owner");
    @BeforeEach void setup(){var fixture=new NonContentManualProofFixture();fixture.fixture();f=fixture;f.jdbc.execute("ALTER TABLE article_revisions ADD validity_revision BIGINT NOT NULL DEFAULT 0");f.jdbc.execute("ALTER TABLE article_revisions ADD article_id VARCHAR(64)");f.jdbc.execute("DROP TABLE IF EXISTS articles");f.jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY)");f.jdbc.update("INSERT INTO articles VALUES('fictional-article')");f.jdbc.update("UPDATE article_revisions SET article_id='fictional-article'");for(String column:java.util.List.of("validity_revision BIGINT","reverses_disposition_id VARCHAR(64)","error_code VARCHAR(64)"))f.jdbc.execute("ALTER TABLE jobs ADD "+column);new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch").release(f.identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,f.action("fictional-runtime-unknown"));}
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(javax.sql.DataSource.class,()->f.source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.tx).withBean(SettingsJdbcStore.class,()->mock(SettingsJdbcStore.class)).withBean(DispatchReadinessService.class,()->mock(DispatchReadinessService.class)).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true","site.articles.enabled=true","site.sources.enabled=true");}
    @SuppressWarnings("unchecked") MockMvc mvc(ObjectProvider<JobsService> jobs){var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);return MockMvcBuilders.standaloneSetup(new JobsController(auth,jobs)).build();}
    long revision(){return new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).revision();}
    String resolve(){var n=JsonMapper.shared().createObjectNode().put("action","resolve_external").put("expected_revision",revision()).put("reason","Fictional runtime original result confirmation").put("attempt_id",f.identity.attemptId()).put("external_state","succeeded");n.putArray("evidence_refs").add("fictional-evidence");n.set("result",CanonicalJson.parse(f.raw()));n.putNull("usage");return JdbcFrozenInputs.canonical(n);}
    String apply(){return "{\"action\":\"apply_result\",\"expected_revision\":"+revision()+",\"reason\":\"Fictional runtime separate application\",\"expected_content_revision\":null,\"result_hash\":\""+CanonicalJson.parse(f.raw()).get("result_hash").textValue()+"\"}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String body,String key){return post("/admin/jobs/"+ResultJdbcStoreTest.ROOT+"/actions").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content(body);}
    @Test void FormalRuntimeResolvesThenSeparatelyAppliesNoContentResultAndReplays() {context().run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertEquals(1,c.getBeansOfType(OwnerJobResolveExternalService.class).size());assertEquals(1,c.getBeansOfType(ResultApplicationFactory.class).size());var api=mvc(c.getBeanProvider(JobsService.class));api.perform(action(resolve(),"fictional-runtime-resolve")).andExpect(status().isOk());assertEquals("EXTERNAL_RESULT_CONFIRMED",new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).application());assertEquals(0,f.count("contents"));String body=apply();long before=revision();api.perform(action(body,"fictional-runtime-apply")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(before+1));assertEquals("APPLIED",new JdbcResultState(f.source).root(ResultJdbcStoreTest.ROOT).application());api.perform(action(body,"fictional-runtime-apply")).andExpect(status().isOk());assertEquals(0,f.count("contents"));assertEquals(1,f.sends);});}
    @Test void FormalRuntimeNativeReferenceInvalidationBlocksSeparateApplication() {context().run(c->{assertNull(c.getStartupFailure());var api=mvc(c.getBeanProvider(JobsService.class));api.perform(action(resolve(),"fictional-runtime-invalid-resolve")).andExpect(status().isOk());String body=apply();long before=revision();var helper=new ConfirmedContentApplicationTest();helper.f=f;helper.invalidateReference();api.perform(action(body,"fictional-runtime-invalid-apply")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("invalid_evidence"));assertEquals(before,revision());assertEquals(0,f.count("contents"));assertEquals(1,f.sends);});}
}
