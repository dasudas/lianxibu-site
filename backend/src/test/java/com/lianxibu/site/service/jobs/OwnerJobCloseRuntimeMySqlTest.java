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

/** Actual Spring closure assembly and original unknown MySQL; identity is a fixture port. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobCloseRuntimeMySqlTest {
    OwnerJobCloseUnresolvedMySqlTest f;
    @BeforeEach void setup()throws Exception{f=new OwnerJobCloseUnresolvedMySqlTest();f.setup();}
    ApplicationContextRunner context(){var source=f.f.db.source;return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source)).withBean(SettingsJdbcStore.class,()->new SettingsJdbcStore(new JdbcSettingsWrites(source),id->false,change->{throw new IllegalStateException("Fictional settings update outside test scope");},Clock.systemUTC())).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true");}
    @Test @SuppressWarnings("unchecked") void uniqueSpringJobsFacadeClosesOriginalUnknownAndReplaysHttpResponse(){context().run(c->{
        assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertEquals(1,c.getBeansOfType(OwnerJobCloseUnresolvedService.class).size());
        var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(f.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);
        ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);
        var mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,c.getBeanProvider(JobsService.class))).build();
        for(int i=0;i<2;i++){mvc.perform(post("/admin/jobs/"+f.f.root+"/actions").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key","fictional-context-close!").contentType("application/json").content("{\"expected_revision\":"+f.revision+",\"action\":\"close_unresolved\",\"attempt_id\":\""+f.identity.attemptId()+"\",\"reason\":\"Fictional context unknown closure\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(f.revision+1));if(i==0)f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.root);}
        assertEquals(1,f.f.db.count("target_type='worker_recovery'"));assertEquals(1,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());assertEquals("blocked",new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).state());
    });}
    @Test void actualSpringClosureCannotFreeANewerSlotOrEnableRecover(){f.f.db.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-new-attempt',holder='fictional-new-worker',slot_revision=slot_revision+1,fencing_token=fencing_token+1");var slot=f.f.state.slot();context().run(c->{var api=c.getBean(JobsService.class);var command=new JobsService.JobAction(f.revision,"close_unresolved","Fictional context unknown closure",f.identity.attemptId(),null,null,null,null,null,null);api.action(f.owner,f.f.root,command,"fictional-context-newer","fictional-csrf","https://site.example.invalid");assertEquals(slot,f.f.state.slot());var recover=new JobsService.JobAction(f.revision+1,"recover","Fictional unsupported recovery",null,null,null,null,null,null,null);assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,assertThrows(IdentityService.Rejected.class,()->api.action(f.owner,f.f.root,recover,"fictional-context-recover","fictional-csrf","https://site.example.invalid")).refusal());assertEquals(1,f.f.db.count("target_type='worker_recovery'"));});}
}
