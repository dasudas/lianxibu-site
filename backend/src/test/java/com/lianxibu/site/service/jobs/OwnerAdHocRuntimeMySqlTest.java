package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.settings.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerAdHocRuntimeMySqlTest {
    OwnerAdHocCreationMySqlTest f;SettingsUpdateService settings;OperationalInputGate gate;
    @BeforeEach void setup(){f=new OwnerAdHocCreationMySqlTest();f.setup();settings=mock(SettingsUpdateService.class);when(settings.freezeForNewInput()).thenReturn(new SettingsUpdateService.Frozen(7,List.of("fictional-runtime-focus"),List.of(),null));gate=mock(OperationalInputGate.class);when(gate.preparationAllowed()).thenAnswer(a->f.allowed);}
    @SuppressWarnings("unchecked") ApplicationContextRunner context(){var executor=mock(ScheduledExecutorService.class);when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenReturn(mock(ScheduledFuture.class));return new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class,AdHocPreparationRuntimeConfiguration.class).withBean(javax.sql.DataSource.class,()->f.f.source).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(f.f.source)).withBean(SettingsJdbcStore.class,()->mock(SettingsJdbcStore.class)).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withBean("adHocPreparationExecutor",ScheduledExecutorService.class,()->executor).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.execution.enabled=true","site.execution.ad-hoc.enabled=true");}
    @SuppressWarnings("unchecked") MockMvc mvc(ObjectProvider<JobsService> jobs){var auth=mock(IdentityService.class);when(auth.authenticate("fictional-session")).thenReturn(f.owner);when(auth.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(f.owner);ObjectProvider<IdentityService> identity=mock(ObjectProvider.class);when(identity.getIfAvailable()).thenReturn(auth);return MockMvcBuilders.standaloneSetup(new JobsController(identity,jobs)).build();}
    String body(String id){return "{\"task_type\":\"ad_hoc_ai\",\"target\":{\"kind\":\"ad_hoc_request\",\"id\":\""+id+"\",\"package_id\":null},\"expected_revision\":0,\"reason\":\"Fictional runtime reason\",\"request\":{\"prompt\":\"Fictional runtime original "+id+"\"}}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String id,String key){return post("/admin/jobs").cookie(new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session")).header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key).contentType("application/json").content(body(id));}
    @Test void ActualSpringHttpAcceptsAndTheDurableCyclePreparesOnlyOriginalInput(){context().run(c->{assertNull(c.getStartupFailure());verifyNoInteractions(settings,gate);assertEquals(1,c.getBeansOfType(OwnerAdHocCreateService.class).size());var api=mvc(c.getBeanProvider(JobsService.class));var first=api.perform(create("fictional-runtime","fictional-runtime-key")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("waiting_input")).andReturn();assertEquals(0,f.f.count("record_kind='logical_task'"));assertEquals(AdHocPreparationCycle.Outcome.PREPARED,c.getBean(AdHocPreparationCycle.class).run(()->true));assertEquals(1,f.f.count("record_kind='logical_task' AND priority_no=14 AND call_count=0"));assertEquals(1,f.f.count("record_kind='ai_attempt' AND state='prepared'"));var replay=api.perform(create("fictional-runtime","fictional-runtime-key")).andExpect(status().isOk()).andReturn();var json=tools.jackson.databind.json.JsonMapper.shared();assertEquals(JdbcFrozenInputs.canonical(json.readTree(first.getResponse().getContentAsString()).get("data")),JdbcFrozenInputs.canonical(json.readTree(replay.getResponse().getContentAsString()).get("data")));verify(settings,times(1)).freezeForNewInput();});}
    @Test void NativePauseAndBadOriginalRemainRefusedThroughTheActualSpringFacade(){context().run(c->{var api=mvc(c.getBeanProvider(JobsService.class));api.perform(create("fictional-runtime","fictional-runtime-key")).andExpect(status().isOk());f.allowed=false;api.perform(create("fictional-runtime","fictional-runtime-key")).andExpect(status().isOk());api.perform(create("fictional-new","fictional-new-key")).andExpect(status().isServiceUnavailable());assertEquals(AdHocPreparationCycle.Outcome.GATED,c.getBean(AdHocPreparationCycle.class).run(()->true));assertEquals(0,f.f.count("record_kind='logical_task' OR record_kind='ai_attempt'"));assertEquals(1,f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]));api.perform(create("fictional-runtime","fictional-runtime-key")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"));verify(settings,times(1)).freezeForNewInput();});}
}
