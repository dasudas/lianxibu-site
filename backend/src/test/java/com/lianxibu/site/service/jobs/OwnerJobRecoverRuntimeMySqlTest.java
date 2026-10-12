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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual Spring recovery, original day/native authority, and the actual next worker reservation. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobRecoverRuntimeMySqlTest {
    OwnerJobRecoverMySqlTest f;
    @BeforeEach void setup()throws Exception{f=new OwnerJobRecoverMySqlTest();f.setup();}
    ApplicationContextRunner context(){var source=f.f.db.source;return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source)).withBean(DispatchReadinessService.class,()->f.f.readiness).withBean(SettingsJdbcStore.class,()->new SettingsJdbcStore(new JdbcSettingsWrites(source),id->false,change->{throw new IllegalStateException("Fictional settings update outside test scope");},Clock.systemUTC())).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true","site.articles.enabled=true","site.sources.enabled=true");}
    JobsService.JobAction command(){return new JobsService.JobAction(f.revision,"recover","Fictional context original recovery",null,null,null,null,null,null,null);}
    @Test @SuppressWarnings("unchecked") void uniqueSpringHttpUsesPermanentRecoveryAndOriginalReceipt(){context().run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(JobsService.class).size());assertEquals(1,c.getBeansOfType(OwnerJobRecoverService.class).size());var identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(f.owner);when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);var mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,c.getBeanProvider(JobsService.class))).build();for(int i=0;i<2;i++){mvc.perform(post("/admin/jobs/"+f.f.root+"/actions").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key","fictional-context-recovery!").contentType("application/json").content("{\"expected_revision\":"+f.revision+",\"action\":\"recover\",\"reason\":\"Fictional context original recovery\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.result_revision").value(f.revision+1));if(i==0){f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.root);f.f.loseRegistration();}}assertEquals(1,f.f.db.count("target_type='job_recover'"));assertEquals(1,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());});}
    @Test void recoveredOriginalRootConsumesOnlyItsSecondBudgetOnActualNextWorkerClaim(){context().run(c->c.getBean(JobsService.class).action(f.owner,f.f.root,command(),"fictional-context-next","fictional-csrf","https://site.example.invalid"));assertEquals(1,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());var nextClock=Clock.fixed(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).atTime(1,20).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),java.time.ZoneOffset.UTC);var nextStore=new WorkerJdbcStore(f.f.state,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source),"fictional-recovery-worker",()->false,f.f.readiness,nextClock,f.f.f.day.manager);var nextWorker=new WorkerAuthorizationService(f.f.db.writes,nextStore,nextClock,n->0,()->true);var claim=nextWorker.claim(new SharedWriteExecutor.ActionContext("fictional-recovered-worker-claim","fictional-next-claim-ref",null));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());assertEquals(f.f.root,claim.permit().identity().rootId());assertNotEquals(f.identity.attemptId(),claim.permit().attemptId());assertEquals(f.identity.frozenRequestJson(),claim.permit().identity().frozenRequestJson());assertEquals(2,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());var sends=new AtomicInteger();assertEquals(WorkerAuthorizationService.State.SENT,nextWorker.dispatch(claim.permit(),request->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(f.identity.frozenRequestJson(),request);sends.incrementAndGet();return "fictional-next-provider-response";}).state());assertEquals(1,sends.get());assertEquals(2,f.f.db.count("target_type='worker_send'"));assertEquals(2,new com.lianxibu.site.repository.jobs.JdbcJobRecords(f.f.db.source).byId(f.f.root).callCount());}
}
