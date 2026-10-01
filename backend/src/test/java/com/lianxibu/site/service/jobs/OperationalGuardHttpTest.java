package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.identity.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.identity.JdbcBrowserSessionStore;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.identity.*;
import com.lianxibu.site.service.settings.*;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.OperationalAdmissionService.Scopes;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "SITE_DB_URL=${B26_TEST_DB_URL:jdbc:h2:mem:ops-guard-http}","SITE_DB_USER=${B26_TEST_DB_USER:sa}","SITE_DB_PASSWORD=${B26_TEST_DB_PASSWORD:fixture}",
        "SITE_NODE_TOKEN_SECRET_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "SITE_RUN_LEASE_KEY_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=","SITE_RUN_LEASE_KEY_ID=fixture","site.collector.enabled=true"})
@Import(OperationalGuardHttpTest.FixtureAssembly.class)
@EnabledIfEnvironmentVariable(named="V11_GO_EXEC",matches=".+")
class OperationalGuardHttpTest {
    static final String ORIGIN="https://site.example.invalid",TOKEN="A".repeat(43);
    static final Instant NOW=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final Key CAPACITY=new Key("OPS-04","database","fictional-database"),TELEMETRY=new Key("OPS-10","monitor","fictional-monitor"),SOURCE=new Key("OPS-07","source","fixture:feed");
    static final SessionSecrets.Pair SESSION=SessionSecrets.create(new SecureRandom());
    @TestConfiguration(proxyBeanMethods=false) static class FixtureAssembly {
        @Bean @Primary OperationalScopeCatalog fictionalScopes(){return new OperationalScopeCatalog(new Scopes(List.of(CAPACITY,TELEMETRY),
                List.of(new Key("OPS-03","worker","fictional-slot"),new Key("OPS-06","java","fictional-java"),new Key("OPS-08","provider","fictional-provider")),Map.of("fixture:feed",List.of(SOURCE))));}
        @Bean SettingsJdbcStore settingsStore(DataSource source){return new SettingsJdbcStore(new JdbcSettingsWrites(source),id->false,new OperationalSettingsAudit(new JdbcJobRecords(source)),CLOCK);}
        @Bean SharedWriteExecutor fixtureWrites(DataSource source,PlatformTransactionManager tx){return new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);}
        @Bean SettingsService settingsService(SettingsJdbcStore store,SharedWriteExecutor writes){return new SettingsUpdateService(store,writes);}
        @Bean OperationalJdbcStore opsStore(DataSource source){return new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),CLOCK);}
        @Bean OperationalMonitoringService opsMonitor(OperationalJdbcStore store,SettingsJdbcStore settings,SharedWriteExecutor writes){return new OperationalMonitoringService(store,()->{var config=settings.operationalConfiguration();return new OperationalMonitoringService.Configuration(config.revision(),config.ready()?config.rules():List.of());},writes,CLOCK);}
        @Bean JobsService operationalJobs(OperationalJdbcStore store,OperationalMonitoringService monitor,PlatformTransactionManager tx){return new OperationalJobsService(store,monitor,null,tx,CLOCK);}
        @Bean IdentityService fixtureIdentity(JdbcTemplate jdbc){
            var verifier=new BrowserSessionService.PasswordVerifier(){public boolean matches(String password,String hash){return false;}public String dummyHash(){return "$argon2id$fictional-unused";}};
            return new BrowserIdentityAdapter(new BrowserSessionService(new JdbcBrowserSessionStore(jdbc),verifier,id->"fictional-owner".equals(id)?"owner":"user",Set.of(ORIGIN),CLOCK,new SecureRandom()),null);
        }
        @Bean @Order(0) SecurityFilterChain fixtureAdminBoundary(HttpSecurity http)throws Exception{return http.securityMatcher("/admin/**").csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();}
    }
    @Autowired JdbcTemplate jdbc;@Autowired DataSource source;@Autowired PlatformTransactionManager tx;
    @Autowired OperationalJdbcStore store;@Autowired OperationalMonitoringService monitor;@Autowired SettingsJdbcStore settings;@Autowired OperationalInputGate gate;@Autowired SourceRunLeaseService leases;
    @LocalServerPort int port;@TempDir Path directory;
    final JsonMapper json=JsonMapper.shared();
    @Test void realMysqlHttpProtectionKeepsOutboxAndRequiresFullOwnerRecovery()throws Exception{
        String url=System.getenv("B26_TEST_DB_URL");if(url==null||!url.startsWith("jdbc:mysql://127.0.0.1:13307/b26_fixture_http_v11_"))throw new IllegalStateException("isolated loopback MySQL required");
        schema();new JdbcBrowserSessionStore(jdbc).createSession("fictional-session","fictional-owner",SessionSecrets.digest(SESSION.cookie()),SessionSecrets.digest(SESSION.csrf()),NOW.plusSeconds(3600));
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,?)","{\"watch_terms_raw\":\"Fictional retained\",\"pause_new_jobs\":false}",Timestamp.from(NOW));
        var command=configuration();assertEquals(200,admin("/admin/preferences","PUT",command,"configure",SESSION.csrf()).statusCode());assertTrue(settings.operationalConfiguration().ready());
        assertEquals(1L,count("jobs"));assertEquals(1L,count("request_receipts"));assertFalse(gate.uploadAllowed());
        String config="{\"adapter\":\"rss\",\"entry_urls\":[\"https://example.invalid/feed\"],\"identity_rule\":\"guid\",\"window_rule\":\"daily\",\"parser_version\":\"fixture-1\",\"max_items\":10,\"max_response_bytes\":1024,\"allowed_hosts\":[\"example.invalid\"],\"valid_until\":\"2027-01-01T00:00:00.000000Z\",\"auth_secret_ref\":null}";
        jdbc.update("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)","fixture:feed","rss","Fictional",config,config,config,1,1,"production",Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),"news_tech");
        jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,window_end,config_version,config_snapshot_json,activation_stage,revision,delivery_state,accepted_count,excluded_count,pending_count) VALUES('v11-run','fixture:feed','daily','2026-01-02',1,?,?,1,?,'production',0,'receiving',0,0,0)",Timestamp.from(Instant.parse("2026-01-01T16:00:00Z")),Timestamp.from(Instant.parse("2026-01-02T16:00:00Z")),"{\"parser_version\":\"fixture-1\",\"valid_until\":\"2027-01-01T00:00:00Z\"}");
        jdbc.update("INSERT INTO collector_nodes VALUES(?,?,?,?,?,?,?,?)","fixture-node","Fictional",NodeTokens.digest(TOKEN),"[\"fixture:feed\"]",1,null,1,"[\"observation:write\",\"run:read\"]");
        State protectedState=sample(CAPACITY,NOW.minusSeconds(32*60),new Capacity(new BigDecimal("4")),"capacity-trigger");
        sample(TELEMETRY,NOW,new Telemetry(0,0),"telemetry");sample(SOURCE,NOW,new ContractFailures(0,0,true),"source-health");
        assertFalse(gate.uploadAllowed());long receipts=count("request_receipts");
        assertThrows(SourceRunLeaseService.CapacityGuarded.class,()->leases.scheduleDaily("fixture:feed",new SharedWriteExecutor.ActionContext("protected-new","new-run",null)));
        assertEquals(1L,count("source_runs"));assertEquals(receipts,count("request_receipts"));
        go("blocked");assertEquals(0L,count("source_run_items"));assertEquals(receipts,count("request_receipts"));
        var rebuilt=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),CLOCK);assertEquals(protectedState,rebuilt.read(CAPACITY));
        String alertId=OperationalJdbcStore.alertId(CAPACITY);var ack=action(protectedState.revision(),"acknowledge_alert");
        assertEquals(403,admin("/admin/jobs/"+alertId+"/actions","POST",ack,"wrong-csrf","fictional-wrong").statusCode());
        assertEquals(200,admin("/admin/jobs/"+alertId+"/actions","POST",ack,"ack",SESSION.csrf()).statusCode());assertFalse(gate.uploadAllowed());
        State latest=null;for(int minute=1;minute<=30;minute++)latest=sample(CAPACITY,NOW.minusSeconds((32-minute)*60L),new Capacity(new BigDecimal("15")),"recovery-"+minute);
        assertFalse(latest.recoveryReady());assertEquals(409,admin("/admin/jobs/"+alertId+"/actions","POST",action(latest.revision(),"resume_capacity_guard"),"early",SESSION.csrf()).statusCode());
        latest=sample(CAPACITY,NOW.minusSeconds(60),new Capacity(new BigDecimal("15")),"recovery-31");assertTrue(latest.recoveryReady());
        var resume=action(latest.revision(),"resume_capacity_guard");assertEquals(200,admin("/admin/jobs/"+alertId+"/actions","POST",resume,"resume",SESSION.csrf()).statusCode());assertTrue(gate.uploadAllowed());
        var created=leases.scheduleDaily("fixture:feed",new SharedWriteExecutor.ActionContext("protected-new","new-run",null));assertEquals("new-run",created.runId());
        go("deliver");assertEquals(1L,count("source_run_items"));assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE observation_id='v11-observation'",Long.class));
        sample(CAPACITY,NOW,new Capacity(new BigDecimal("4")),"rearm");assertFalse(gate.uploadAllowed());long auditRows=count("jobs");
        assertEquals(200,admin("/admin/jobs/"+alertId+"/actions","POST",resume,"resume",SESSION.csrf()).statusCode());assertFalse(gate.uploadAllowed());assertEquals(auditRows,count("jobs"));
        go("replay");assertEquals(1L,count("source_run_items"));assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE observation_id='v11-observation'",Long.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE JSON_UNQUOTE(JSON_EXTRACT(action_payload,'$.record_type'))='ops_notice_intent' AND JSON_UNQUOTE(JSON_EXTRACT(action_payload,'$.delivery_state'))='pending'",Long.class)>0);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind<>'disposition' OR call_count<>0",Long.class));
        assertEquals("Fictional retained",CanonicalJson.parse(new JdbcSettingsWrites(source).read().preferences().valueJson()).get("watch_terms_raw").asText());
    }
    State sample(Key key,Instant at,Reading reading,String action){return monitor.sample(key,true,reading,at,new SharedWriteExecutor.ActionContext(action,"fixture-ops",null));}
    ObjectNode action(long revision,String action){var body=json.createObjectNode();body.put("expected_revision",revision);body.put("action",action);body.put("reason","Fictional reviewed recovery");return body;}
    ObjectNode configuration(){var body=json.createObjectNode();body.put("expected_revision",1);body.putArray("follow_keywords").add("Fictional mapped");body.putArray("blocked_keywords");body.put("ai_paused",true);body.putNull("default_package_id");body.put("operational_change_reason","Fictional configuration");var rules=body.putArray("operational_limits");for(var rule:MonitoringFixtures.complete()){var row=rules.addObject();row.put("rule_id",rule.ruleId());row.put("warning_threshold",rule.warningThreshold());row.put("critical_threshold",rule.criticalThreshold());row.put("recovery_threshold",rule.recoveryThreshold());row.putNull("notification_secret_ref");row.put("notification_configured",false);var parameters=row.putObject("rule_parameters");rule.ruleParameters().forEach(parameters::put);}return body;}
    HttpResponse<String> admin(String path,String method,ObjectNode body,String key,String csrf)throws Exception{try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Cookie","lianxibu_session="+SESSION.cookie()).header("Origin",ORIGIN).header("X-CSRF-Token",csrf).header("Idempotency-Key",key).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());}}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    void go(String phase)throws Exception{var child=new ProcessBuilder(System.getenv("V11_GO_EXEC"),"test","./internal/outbox","-run","^TestOperationalGuardJavaHTTP$","-count=1");child.directory(Path.of("../collector").toAbsolutePath().normalize().toFile());var env=child.environment();env.put("V11_BASE_URL","https://127.0.0.1:"+port);env.put("V11_NODE_TOKEN",TOKEN);env.put("V11_PHASE",phase);env.put("V11_OUTBOX_PATH",directory.resolve("outbox.db").toString());env.put("GOTOOLCHAIN","local");env.put("GOPROXY","off");env.put("GOROOT",Path.of(System.getenv("V11_GO_EXEC")).getParent().getParent().toString());Path log=directory.resolve(phase+".log");child.redirectErrorStream(true);child.redirectOutput(log.toFile());var process=child.start();if(!process.waitFor(50,TimeUnit.SECONDS)){process.destroyForcibly();fail("local interop timeout");}assertEquals(0,process.exitValue(),()->{try{return Files.readString(log);}catch(Exception error){return "local interop failed";}});}
    // DDL is generated from current fictional test fixtures only; no historical SQL is run.
    void schema(){
        ddl("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        ddl("CREATE TABLE collector_nodes(id VARCHAR(64) PRIMARY KEY,name VARCHAR(100),"
                + "token_hash BINARY(32),allowed_source_ids CLOB,enabled INT,last_seen_at TIMESTAMP,"
                + "config_revision BIGINT,permissions_json CLOB)");
        ddl("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),"
                + "config_json CLOB,draft_config_json CLOB,effective_config_json CLOB,revision BIGINT,"
                + "config_revision BIGINT,activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        ddl("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,revision BIGINT,"
                + "source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,"
                + "window_start TIMESTAMP,window_end TIMESTAMP,base_run_id VARCHAR(64),config_version BIGINT,"
                + "config_snapshot_json CLOB,activation_stage VARCHAR(16),fetch_mode VARCHAR(32),"
                + "collection_reason VARCHAR(32),target_version_json CLOB,delivery_state VARCHAR(32),"
                + "fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,excluded_count BIGINT,"
                + "pending_count BIGINT,coverage_json CLOB,job_refs_json CLOB,claim_node_id VARCHAR(64),"
                + "claim_token_hash BINARY(32),claim_token_ciphertext BLOB,claim_key_id VARCHAR(64),"
                + "lease_until TIMESTAMP,node_id VARCHAR(64),started_at TIMESTAMP,finished_at TIMESTAMP,"
                + "barrier_closed_at TIMESTAMP,"
                + "result_json CLOB,probe_trial_result_json CLOB,manifest_hash BINARY(32),part_count BIGINT,"
                + "expected_count BIGINT,UNIQUE(source_id,run_kind,business_date,generation))");
        ddl("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),source_id VARCHAR(80),"
                + "member_id VARCHAR(64),observation_id VARCHAR(64),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),ack_json CLOB,result_ref_json CLOB,created_at TIMESTAMP,"
                + "revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),"
                + "control_operation VARCHAR(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(id),UNIQUE(actor_id,scope,key_hash))");
        ddl("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,run_id VARCHAR(64),member_id VARCHAR(64),source_id VARCHAR(80),"
                + "source_item_id CLOB,source_item_hash BINARY(32),category VARCHAR(16),"
                + "observation_id VARCHAR(64),node_id VARCHAR(64),payload_hash BINARY(32),"
                + "replaces_observation_id VARCHAR(64),evidence_json CLOB,evidence_hash BINARY(32),"
                + "article_id VARCHAR(64),article_revision_id VARCHAR(64),part_no BIGINT,terminal_state VARCHAR(16),"
                + "receipt_ref VARCHAR(64),state VARCHAR(32),projection_decision VARCHAR(16),"
                + "source_order_json CLOB,validity_revision BIGINT,effective_valid INT,"
                + "exclusion_reason VARCHAR(128),activation_state VARCHAR(16),UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
        ddl("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) NOT NULL UNIQUE,"
                + "task_kind VARCHAR(32) NOT NULL,target_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "priority_no INT NOT NULL,eligible_at TIMESTAMP NOT NULL,revision BIGINT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,record_kind VARCHAR(24) NOT NULL,"
                + "record_identity_hash BINARY(32) NOT NULL UNIQUE,call_count INT NOT NULL,"
                + "max_attempts INT,logical_key CLOB,logical_key_hash BINARY(32),generation BIGINT,"
                + "stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,"
                + "input_state VARCHAR(16),root_job_id VARCHAR(64),attempt_no INT,request_json CLOB,"
                + "snapshot_owner_id VARCHAR(64),input_hash BINARY(32),owner_job_id VARCHAR(64),"
                + "purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_json CLOB,"
                + "payload_hash BINARY(32),action_key_hash BINARY(32),target_type VARCHAR(32),"
                + "target_key_json CLOB,target_key_hash BINARY(32),operator_id VARCHAR(64),"
                + "reason VARCHAR(2048),acted_at TIMESTAMP,action_payload CLOB,"
                + "action_payload_hash BINARY(32),applied_at TIMESTAMP,provider_request_id VARCHAR(256),"
                + "usage_json CLOB,cost_decimal DECIMAL(20,8),"
                + "UNIQUE(root_job_id,attempt_no),UNIQUE(owner_job_id,purpose,stamp_id,part_no),"
                + "CHECK ((record_kind='logical_task' AND max_attempts=3 AND call_count<=3"
                + " AND priority_no BETWEEN 1 AND 14) OR (record_kind<>'logical_task'"
                + " AND max_attempts IS NULL AND call_count=0 AND priority_no=0)),"
                + "CHECK (record_kind<>'ai_attempt' OR (root_job_id IS NOT NULL AND attempt_no BETWEEN 1 AND 3"
                + " AND request_json IS NOT NULL AND snapshot_owner_id IS NOT NULL AND input_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'snapshot_part' OR (owner_job_id IS NOT NULL"
                + " AND purpose IS NOT NULL AND stamp_id IS NOT NULL AND part_no IS NOT NULL"
                + " AND payload_json IS NOT NULL AND payload_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'disposition' OR (action_key_hash IS NOT NULL"
                + " AND target_type IS NOT NULL AND target_key_json IS NOT NULL"
                + " AND target_key_hash IS NOT NULL AND operator_id IS NOT NULL"
                + " AND reason IS NOT NULL AND acted_at IS NOT NULL AND action_payload IS NOT NULL"
                + " AND action_payload_hash IS NOT NULL)))");

        ddl("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB NOT NULL,revision BIGINT UNSIGNED NOT NULL,updated_at TIMESTAMP NOT NULL)");
        ddl("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),token_hash BINARY(32) UNIQUE,csrf_hash BINARY(32),expires_at TIMESTAMP,revoked_at TIMESTAMP)");
}
    void ddl(String statement){for(String column:List.of("allowed_source_ids","permissions_json","config_json","draft_config_json","effective_config_json","config_snapshot_json","target_version_json","coverage_json","job_refs_json","result_json","probe_trial_result_json","response_json","ack_json","result_ref_json","evidence_json","source_order_json","logical_key","request_json","payload_json","target_key_json","action_payload","usage_json","value_json"))statement=statement.replace(column+" CLOB",column+" JSON");statement=statement.replace(" CLOB"," LONGTEXT").replace(" BLOB"," LONGBLOB").replace("TIMESTAMP","DATETIME(6)");jdbc.execute(statement);}
}
