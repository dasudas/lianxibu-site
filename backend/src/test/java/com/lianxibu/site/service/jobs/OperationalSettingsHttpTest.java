package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lianxibu.site.controller.settings.SettingsController;
import com.lianxibu.site.domain.identity.SessionSecrets;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.identity.JdbcBrowserSessionStore;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.identity.*;
import com.lianxibu.site.service.settings.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.ObjectNode;

class OperationalSettingsHttpTest {
    static final String ORIGIN="https://site.example.invalid";
    OperationalPersistenceFixture fixture;SettingsJdbcStore settings;MockMvc mvc;
    SessionSecrets.Pair secrets;JdbcBrowserSessionStore sessions;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        fixture=new OperationalPersistenceFixture();
        fixture.jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32) NOT NULL,status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP NOT NULL,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        fixture.jdbc.execute("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),token_hash BINARY(32) UNIQUE,csrf_hash BINARY(32),expires_at TIMESTAMP,revoked_at TIMESTAMP)");
        fixture.jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,?)","{\"watch_terms_raw\":\"Fictional original\",\"blocked_terms_raw\":\"Fictional retained\",\"pause_new_jobs\":false}",java.sql.Timestamp.from(fixture.clock.instant()));
        sessions=new JdbcBrowserSessionStore(fixture.jdbc);secrets=SessionSecrets.create(new SecureRandom());
        sessions.createSession("fictional-session-id","fictional-owner",SessionSecrets.digest(secrets.cookie()),SessionSecrets.digest(secrets.csrf()),fixture.clock.instant().plusSeconds(3600));
        BrowserSessionService.PasswordVerifier verifier=mock(BrowserSessionService.PasswordVerifier.class);
        IdentityService identity=new BrowserIdentityAdapter(new BrowserSessionService(sessions,verifier,id->"owner",Set.of(ORIGIN),fixture.clock,new SecureRandom()),null);
        ObjectProvider<IdentityService> auth=mock(ObjectProvider.class);when(auth.getIfAvailable()).thenReturn(identity);
        settings=new SettingsJdbcStore(new JdbcSettingsWrites(fixture.source),id->true,new OperationalSettingsAudit(new JdbcJobRecords(fixture.source)),fixture.clock);
        ObjectProvider<SettingsService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(new SettingsUpdateService(settings,fixture.writes));
        mvc=MockMvcBuilders.standaloneSetup(new SettingsController(auth,provider)).build();
    }
    ObjectNode command(){
        ObjectNode body=OperationalStateCodec.JSON.createObjectNode();body.put("expected_revision",1);body.putArray("follow_keywords").add("Fictional mapped");body.putArray("blocked_keywords");
        body.put("ai_paused",true);body.putNull("default_package_id");body.put("operational_change_reason","Fictional configured");
        var array=body.putArray("operational_limits");for(var rule:MonitoringFixtures.complete()){
            ObjectNode row=array.addObject();row.put("rule_id",rule.ruleId());row.put("warning_threshold",rule.warningThreshold());row.put("critical_threshold",rule.criticalThreshold());row.put("recovery_threshold",rule.recoveryThreshold());
            row.putNull("notification_secret_ref");row.put("notification_configured",false);ObjectNode parameters=row.putObject("rule_parameters");rule.ruleParameters().forEach(parameters::put);
        }return body;
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.cookie(new jakarta.servlet.http.Cookie("lianxibu_session",secrets.cookie()));}
    MockHttpServletRequestBuilder write(ObjectNode body,String key,String csrf,String origin){return auth(put("/admin/preferences")).contentType(MediaType.APPLICATION_JSON).header("X-CSRF-Token",csrf).header("Origin",origin).header("Idempotency-Key",key).content(body.toString());}
    long count(String table){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test void ownerHttpConfigurationIsDurableIdempotentAndFeedsRealRuleSampling()throws Exception{
        mvc.perform(auth(get("/admin/preferences"))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("settings_mapping_required"));
        var body=command();mvc.perform(write(body,"configure",secrets.csrf(),ORIGIN)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2)).andExpect(jsonPath("$.data.operational_limits[3].rule_parameters.protect_free_percent").value("5"));
        assertEquals(1,count("jobs"));assertEquals(1,count("request_receipts"));
        mvc.perform(write(body,"configure",secrets.csrf(),ORIGIN)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));assertEquals(1,count("jobs"));
        var rebuilt=new SettingsJdbcStore(new JdbcSettingsWrites(fixture.source),id->true,new OperationalSettingsAudit(new JdbcJobRecords(fixture.source)),fixture.clock);
        assertTrue(rebuilt.operationalConfiguration().ready());assertEquals(2,new SettingsUpdateService(rebuilt,fixture.writes).get(new IdentityService.Actor("fictional-owner","owner")).revision());
        var monitor=new OperationalMonitoringService(fixture.store(),()->{var c=rebuilt.operationalConfiguration();return new OperationalMonitoringService.Configuration(c.revision(),c.ready()?c.rules():java.util.List.of());},fixture.writes,fixture.clock);
        var state=monitor.sample(new Key("OPS-04","database","fictional-capacity"),true,new Capacity(new BigDecimal("4")),fixture.clock.instant(),new ActionContext("sample","fictional-ops",null));
        assertEquals(1,state.configurationRevision());assertEquals(4,state.guards().size());
        var raw=CanonicalJson.parse(new JdbcSettingsWrites(fixture.source).read().preferences().valueJson());assertEquals("Fictional original",raw.get("watch_terms_raw").asText());assertEquals("Fictional retained",raw.get("blocked_terms_raw").asText());
        body.put("operational_change_reason","Fictional other reason");mvc.perform(write(body,"configure",secrets.csrf(),ORIGIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("identity_conflict"));
        mvc.perform(write(body,"stale",secrets.csrf(),ORIGIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("revision_conflict"));
    }
    @Test void wrongCsrfOriginRevokedSessionAndIncompleteRuleCannotPersist()throws Exception{
        mvc.perform(write(command(),"wrong-csrf","fictional-wrong",ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(write(command(),"wrong-origin",secrets.csrf(),"https://wrong.example.invalid")).andExpect(status().isForbidden());
        var body=command();body.remove("operational_change_reason");mvc.perform(write(body,"no-reason",secrets.csrf(),ORIGIN)).andExpect(status().isUnprocessableEntity());
        body=command();((ObjectNode)body.get("operational_limits").get(3).get("rule_parameters")).remove("protect_free_percent");mvc.perform(write(body,"incomplete",secrets.csrf(),ORIGIN)).andExpect(status().isUnprocessableEntity());
        sessions.revokeSession(SessionSecrets.digest(secrets.cookie()),fixture.clock.instant());mvc.perform(write(command(),"revoked",secrets.csrf(),ORIGIN)).andExpect(status().isUnauthorized());
        assertEquals(1,new JdbcSettingsWrites(fixture.source).read().preferences().revision());assertEquals(0,count("jobs"));assertEquals(0,count("request_receipts"));
    }
    @Test void databaseAuditFailureThroughHttpRollsBackTheWholeChange(){
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT reject_ops_configuration CHECK(target_type IS NULL OR target_type<>'ops_rule')");
        assertThrows(Exception.class,()->mvc.perform(write(command(),"fault",secrets.csrf(),ORIGIN)));
        var current=new JdbcSettingsWrites(fixture.source).read();assertEquals(1,current.preferences().revision());assertNull(current.operational());assertNull(current.pauseIntent());assertEquals(0,count("jobs"));assertEquals(0,count("request_receipts"));
    }
}
