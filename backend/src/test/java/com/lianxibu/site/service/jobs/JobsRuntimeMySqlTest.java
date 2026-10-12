package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.JobsRuntimeConfiguration;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import com.lianxibu.site.service.identity.IdentityService;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual Spring Jobs API assembly and MySQL root cancellation. Identity is an explicit fixture port. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class JobsRuntimeMySqlTest {
    OwnerJobCancelMySqlTest f;
    @BeforeEach void setup(){f=new OwnerJobCancelMySqlTest();f.setup();}
    ApplicationContextRunner context(){var source=f.f.f.f.f.source;return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source)).withBean(SettingsJdbcStore.class,()->new SettingsJdbcStore(new JdbcSettingsWrites(source),id->false,change->{throw new IllegalStateException("Fictional settings update outside test scope");},Clock.systemUTC())).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true");}
    @Test @SuppressWarnings("unchecked") void actualHttpUsesSpringBoundPermanentCancellationAndSameReadSource(){context().run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertInstanceOf(OwnerJobCommandsService.class,c.getBean(OwnerJobsService.Commands.class));var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(f.f.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.f.owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);var mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,c.getBeanProvider(JobsService.class))).build();var cookie=new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");mvc.perform(get("/admin/jobs/"+f.f.f.root).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.call_count").value(0));for(int i=0;i<2;i++)mvc.perform(post("/admin/jobs/"+f.f.f.root+"/actions").cookie(cookie).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key","fictional-context-cancel!").contentType("application/json").content("{\"expected_revision\":0,\"action\":\"cancel\",\"reason\":\"Fictional context cancellation\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(1));assertEquals(1,f.f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_cancel'",Integer.class));mvc.perform(get("/admin/jobs/"+f.f.f.root).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("cancelled"));});}
    @Test void otherManualProvidersAreNotClaimedCompleteByACommandsBean(){context().run(c->{var api=c.getBean(JobsService.class);var command=new JobsService.JobAction(0,"recover","Fictional unsupported action",null,null,null,null,null,null,null);assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,assertThrows(IdentityService.Rejected.class,()->api.action(f.f.owner,f.f.f.root,command,"fictional-recover","fictional-csrf","https://site.example.invalid")).refusal());assertEquals(0,f.f.f.f.f.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Integer.class,f.f.f.root));});}
}
