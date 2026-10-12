package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.JobsRuntimeConfiguration;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.repository.jobs.*;
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

/** Actual Spring/native discard. Original worker admission, reference proof and identity are fixture ports. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OwnerJobDiscardRuntimeMySqlTest {
    OwnerJobDiscardResultMySqlTest f;
    @BeforeEach void setup(){f=new OwnerJobDiscardResultMySqlTest();f.setup();}
    ApplicationContextRunner context(){var source=f.f.source;return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source)).withBean(SettingsJdbcStore.class,()->new SettingsJdbcStore(new JdbcSettingsWrites(source),id->false,change->{throw new IllegalStateException("Fictional settings update outside test scope");},Clock.systemUTC())).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true");}
    @Test @SuppressWarnings("unchecked") void uniqueSpringHttpUsesActualDiscardAndOriginalResponse(){context().run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertEquals(1,c.getBeansOfType(OwnerJobDiscardResultService.class).size());var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(f.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);var mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,c.getBeanProvider(JobsService.class))).build();for(int i=0;i<2;i++){mvc.perform(post("/admin/jobs/"+ResultJdbcStoreTest.ROOT+"/actions").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key","fictional-context-discard!").contentType("application/json").content("{\"expected_revision\":"+f.revision+",\"action\":\"discard_result\",\"reason\":\"Fictional context result abandonment\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(f.revision+1));if(i==0)f.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",ResultJdbcStoreTest.ROOT);}assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='job_discard_result'",Integer.class));assertEquals(1,new JdbcJobRecords(f.f.source).byId(ResultJdbcStoreTest.ROOT).callCount());});}
    @Test void actualSpringDiscardKeepsOriginalContentAndNewerSlot(){String body=f.f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id='fictional-report'",String.class);f.f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-worker',slot_revision=slot_revision+1,fencing_token=fencing_token+1");var slot=new JdbcWorkerState(f.f.source).slot();context().run(c->{var command=new JobsService.JobAction(f.revision,"discard_result","Fictional context result abandonment",null,null,null,null,null,null,null);c.getBean(JobsService.class).action(f.owner,ResultJdbcStoreTest.ROOT,command,"fictional-context-newer","fictional-csrf","https://site.example.invalid");assertEquals(slot,new JdbcWorkerState(f.f.source).slot());assertEquals(body,f.f.jdbc.queryForObject("SELECT body_json FROM contents WHERE id='fictional-report'",String.class));assertEquals("discarded",new JdbcJobRecords(f.f.source).byId(ResultJdbcStoreTest.ROOT).state());assertNotNull(new JdbcResultState(f.f.source).root(ResultJdbcStoreTest.ROOT).resultHash());assertEquals(1,f.f.sends);});}
}
