package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.jobs.JobsController;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class OperationalJobsHttpTest {
    private OperationalPersistenceFixture fixture;
    private OperationalJdbcStore store;
    private OperationalMonitoringService monitor;
    private MockMvc mvc;
    private IdentityService identity;
    private static final Key KEY=new Key("OPS-04","database","fictional-capacity");
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        fixture=new OperationalPersistenceFixture();store=fixture.store();monitor=fixture.monitor(store);
        identity=mock(IdentityService.class);when(identity.authenticate("fictional-session")).thenReturn(new Actor("fictional-owner","owner"));
        when(identity.authorizeWrite("fictional-session","fictional-csrf","https://site.example.invalid")).thenReturn(new Actor("fictional-owner","owner"));
        ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);
        ObjectProvider<JobsService> jobs=mock(ObjectProvider.class);when(jobs.getIfAvailable()).thenReturn(new OperationalJobsService(store,monitor,null,fixture.tx,fixture.clock));
        mvc=MockMvcBuilders.standaloneSetup(new JobsController(auth,jobs)).build();
    }
    @Test void realDatabaseAlertActionsHaveOwnerCasRecoveryAndAppendOnlyAudit()throws Exception{
        State state=sample(0,"4");String id=OperationalJdbcStore.alertId(KEY);
        mvc.perform(get("/admin/jobs?record_kind=disposition&target_type=ops_rule").cookie(cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].alert_id").value(id))
                .andExpect(jsonPath("$.data.items[0].observed_value").value("4"))
                .andExpect(jsonPath("$.data.items[0].threshold").value("5"));
        mvc.perform(action(id,state.revision(),"resume_capacity_guard","early")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("recovery_condition_unmet"));
        mvc.perform(action(id,state.revision()+1,"acknowledge_alert","wrong-rev")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("revision_conflict"));
        mvc.perform(action(id,state.revision(),"acknowledge_alert","ack")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result_resource_ref.kind").value("ops_rule"));
        assertTrue(store.read(KEY).acknowledged());assertEquals(4,store.read(KEY).guards().size());
        for(int m=1;m<=31;m++)state=sample(m,"15");
        mvc.perform(action(id,state.revision(),"resume_capacity_guard","resume")).andExpect(status().isOk());
        assertTrue(store.read(KEY).guards().isEmpty());
        long count=fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);
        mvc.perform(action(id,state.revision(),"resume_capacity_guard","resume")).andExpect(status().isOk());
        assertEquals(count,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));
        assertEquals(0L,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind<>'disposition' OR call_count<>0",Long.class));
        mvc.perform(get("/admin/jobs?record_kind=disposition&target_type=ops_rule").cookie(cookie()))
                .andExpect(jsonPath("$.data.items[0].state").value("resolved"));
    }
    @Test void unknownValueIsNullAndWrongRoleOrCsrfDoesNotTouchPersistentState()throws Exception{
        sample(0,"4");fixture.clock.now=fixture.clock.now.plusSeconds(60);
        State state=monitor.sample(KEY,false,null,fixture.clock.instant(),new ActionContext("missing-sample","fictional-ops",null));
        mvc.perform(get("/admin/jobs?record_kind=disposition&target_type=ops_rule").cookie(cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].observed_value").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/admin/jobs/"+OperationalJdbcStore.alertId(KEY)+"/actions").cookie(cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        when(identity.authenticate("fictional-session")).thenReturn(new Actor("fictional-user","user"));
        mvc.perform(action(OperationalJdbcStore.alertId(KEY),state.revision(),"acknowledge_alert","forbidden"))
                .andExpect(status().isForbidden());assertEquals(state,store.read(KEY));
    }
    private State sample(int minute,String free){
        fixture.clock.now=Instant.parse("2026-10-01T00:00:00Z").plusSeconds(minute*60L);
        return monitor.sample(KEY,true,new Capacity(new BigDecimal(free)),fixture.clock.instant(),new ActionContext("sample-"+minute,"fictional-ops",null));
    }
    private static jakarta.servlet.http.Cookie cookie(){return new jakarta.servlet.http.Cookie("lianxibu_session","fictional-session");}
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String id,long rev,String name,String key){
        return post("/admin/jobs/"+id+"/actions").cookie(cookie()).contentType(MediaType.APPLICATION_JSON)
                .header("X-CSRF-Token","fictional-csrf").header("Origin","https://site.example.invalid").header("Idempotency-Key",key)
                .content("{\"expected_revision\":"+rev+",\"action\":\""+name+"\",\"reason\":\"Fictional operation\"}");
    }
}
