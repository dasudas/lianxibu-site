package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.JobResultComparisonRuntimeConfiguration;
import com.lianxibu.site.controller.jobs.JobResultComparisonController;
import com.lianxibu.site.service.identity.IdentityService;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerResultComparisonRuntimeMySqlTest {
    OwnerResultComparisonMySqlTest t;
    @BeforeEach void setup(){t=new OwnerResultComparisonMySqlTest();t.setup();t.f.jdbc.execute("ALTER TABLE article_revisions ADD COLUMN validity_revision BIGINT NOT NULL DEFAULT 0,ADD COLUMN article_id VARCHAR(64)");t.f.jdbc.execute("ALTER TABLE jobs ADD COLUMN validity_revision BIGINT,ADD COLUMN reverses_disposition_id VARCHAR(64)");t.f.jdbc.execute("DROP TABLE IF EXISTS articles");t.f.jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY)");t.f.jdbc.update("INSERT INTO articles VALUES('fictional-article')");t.f.jdbc.update("UPDATE article_revisions SET article_id='fictional-article'");}
    void invalidateCitation(){
        var source=t.f.source;var clock=t.f.clock;
        var generated=new com.lianxibu.site.service.content.GeneratedContentService(new com.lianxibu.site.repository.content.JdbcGeneratedContents(source),clock);
        var ledger=new VersionValidityLedgerService(new com.lianxibu.site.repository.jobs.JdbcVersionValidity(source),new FrozenContentVersionService(new com.lianxibu.site.repository.jobs.JdbcFrozenContentVersions(source)),new com.lianxibu.site.service.articles.EvidenceValidityService(new com.lianxibu.site.repository.articles.JdbcEvidenceValidity(source)),new com.lianxibu.site.service.content.ContentReviewWriteService(new com.lianxibu.site.repository.content.JdbcReviewPublication(source),generated,clock),clock,"fictional-owner");
        var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","article_revision").put("id","fictional-citation");
        var command=new VersionValidityService.Command(target,0,VersionValidityService.Action.INVALIDATE,"Fictional original reference invalidation",null);
        t.f.writes.execute(t.f.action("fictional-native-reference-invalidation"),key->null,c->{ledger.lockStableIdentity(target);String id=ledger.append(command,VersionValidityService.commandHash(command,VersionValidityService.canonicalTarget(target)),1,c.actionKey());assertFalse(ledger.validity(target).effectiveValid());return id;});
    }
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(JobResultComparisonRuntimeConfiguration.class).withBean(DataSource.class,()->t.f.source).withBean(PlatformTransactionManager.class,()->t.f.tx).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true");}
    @SuppressWarnings("unchecked") MockMvc mvc(ObjectProvider<JobResultComparisonReadService> reads,String role){
        var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(new IdentityService.Actor("fictional-owner",role));ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);
        return MockMvcBuilders.standaloneSetup(new JobResultComparisonController(auth,reads)).build();
    }
    jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    @Test void permanentSpringHttpUsesActualVersionValidityAndExposesOnlyComparison()throws Exception{context().run(c->{assertNull(c.getStartupFailure());var api=mvc(c.getBeanProvider(JobResultComparisonReadService.class),"owner");String body=api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.state").value("comparison")).andExpect(jsonPath("$.data.can_apply").value(true)).andReturn().getResponse().getContentAsString();assertFalse(body.contains("bytes_base64"));assertFalse(body.contains("request_json"));assertFalse(body.contains("dispatch_token"));invalidateCitation();api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_apply").value(false)).andExpect(jsonPath("$.data.blocking_reasons[0]").value("invalid_evidence"));assertEquals(1,t.f.sends);});}
    @Test void missingNativeBodyProofAndCorruptedOriginalStorageRemainDistinct(){context().run(c->{var api=mvc(c.getBeanProvider(JobResultComparisonReadService.class),"owner");t.f.jdbc.update("UPDATE contents SET body_revision=NULL,content_key_json=NULL");api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("unproven_current_version"));t.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);String body=api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1")).andReturn().getResponse().getContentAsString();assertFalse(body.contains("retained"));assertFalse(body.contains("SQL"));});}
    @Test void missingSessionRoleQueryAndUnknownTaskKeepOriginalHttpBoundaries(){context().run(c->{var api=mvc(c.getBeanProvider(JobResultComparisonReadService.class),"owner");api.perform(get("/admin/jobs/fictional-root/result-comparison")).andExpect(status().isUnauthorized());mvc(c.getBeanProvider(JobResultComparisonReadService.class),"user").perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isForbidden());api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie()).param("fictional","unexpected")).andExpect(status().isBadRequest());api.perform(get("/admin/jobs/fictional-missing/result-comparison").cookie(cookie())).andExpect(status().isNotFound());});}
    @Test void nativeValidityFlagWithoutItsPermanentLedgerCannotBeReportedAsAQualifiedInvalidation(){context().run(c->{var api=mvc(c.getBeanProvider(JobResultComparisonReadService.class),"owner");api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isOk());t.f.jdbc.update("UPDATE article_revisions SET effective_valid=false,validity_revision=validity_revision+1");api.perform(get("/admin/jobs/fictional-root/result-comparison").cookie(cookie())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"));});}
}
