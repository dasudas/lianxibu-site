package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.service.identity.*;
import java.net.URI;
import java.net.http.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.support.AopUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:browser-runtime;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "site.browser.enabled=true",
    "SITE_BROWSER_ORIGIN=https://site.example.invalid", "SITE_OWNER_IDS=fictional-owner",
    "SITE_USER_IDS=fictional-reader", "site.learning.enabled=true", "site.articles.enabled=true", "site.sources.enabled=true"
})
class BrowserRuntimeConfigurationTest {
    @org.springframework.test.context.DynamicPropertySource
    static void learningDirectory(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("SITE_LEARNING_IMPORT_DIRECTORY", () -> java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"fictional-learning-runtime-"+java.util.UUID.randomUUID()).toAbsolutePath().toString());
    }
    @LocalServerPort int port;
    @Autowired DataSource source;
    @Autowired(required=false) BrowserSessionService browser;
    private JdbcTemplate jdbc;
    private static final String ORIGIN="https://site.example.invalid";
    private static final String PASSWORD="fictional-long-password";
    private final JsonMapper json=JsonMapper.builder().build();

    @BeforeEach void database() {
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS user_sessions");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users(id VARCHAR(64) PRIMARY KEY, username VARCHAR(64), password_hash VARCHAR(255))");
        jdbc.execute("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), token_hash BINARY(32), csrf_hash BINARY(32), expires_at TIMESTAMP(6), revoked_at TIMESTAMP(6))");
        String hash=new Argon2Passwords().encode(PASSWORD);
        for(String id: new String[]{"fictional-owner","fictional-reader","fictional-unmapped"})
            jdbc.update("INSERT INTO users VALUES(?,?,?)",id,id,hash);
        jdbc.execute("DROP TABLE IF EXISTS contents");
        jdbc.execute("CREATE TABLE contents(kind VARCHAR(32),id VARCHAR(64),title VARCHAR(512),state VARCHAR(32),revision BIGINT,body_revision BIGINT,body_json CLOB,PRIMARY KEY(kind,id))");
        jdbc.update("INSERT INTO contents VALUES('report','fictional-legacy','Fictional legacy','published',3,NULL,?)",
            "{\"overview\":\"fictional original body\"}");
    }

    @Test void realApplicationAuthenticatesAndRevokesJdbcSession() throws Exception {
        assertNotNull(browser); assertTrue(AopUtils.isAopProxy(browser),"transaction advice must actually be installed");
        var login=login("fictional-owner",PASSWORD,ORIGIN);
        assertEquals(200,login.statusCode());
        assertEquals("owner",json.readTree(login.body()).path("data").path("role").asText());
        String cookie=cookie(login,"lianxibu_session");
        assertTrue(login.headers().allValues("Set-Cookie").stream().anyMatch(v->v.contains("HttpOnly")&&v.contains("Secure")&&v.contains("SameSite=Lax")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions",Integer.class));
        assertTrue(json.readTree(request("GET","/session",cookie,null,null,null).body()).path("data").path("authenticated").asBoolean());
        String csrf=json.readTree(login.body()).path("data").path("csrf_token").asText();
        assertEquals(403,request("DELETE","/session",cookie,"wrong",ORIGIN,null).statusCode());
        assertEquals(200,request("DELETE","/session",cookie,csrf,ORIGIN,null).statusCode());
        assertFalse(json.readTree(request("GET","/session",cookie,null,null,null).body()).path("data").path("authenticated").asBoolean());
    }

    @Test void privateRecordRuntimeUsesPublishedObjectsAndOwnerScopedDurableReceipts() throws Exception {
        for(String table:new String[]{"user_records","request_receipts","learning_items","learning_packages"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),object_kind VARCHAR(16),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),self_assessment VARCHAR(32),note CLOB,artifact_url VARCHAR(2000),response_text CLOB,confirmed_body_revision BIGINT,updated_at TIMESTAMP,is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) PRIMARY KEY,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),UNIQUE(actor_id,scope,key_hash))");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),position_no INT,body_json CLOB,answer_text CLOB,answer_state VARCHAR(16),answer_generation_state VARCHAR(32),revision BIGINT,PRIMARY KEY(package_id,kind,id))");
        jdbc.update("INSERT INTO learning_packages VALUES('fixture-package','Fictional','published',1)");
        jdbc.update("INSERT INTO learning_items VALUES('fixture-package','node','fixture-node','Fictional',0,'{}',NULL,NULL,NULL,3)");
        String body="""
                {"expected_revision":0,"status":"learning","self_assessment":"partial","note":"Private fixture note","artifact_url":null,"package_id":"fixture-package","object_kind":"node"}
                """;
        var login=login("fictional-reader",PASSWORD,ORIGIN);String cookie=cookie(login,"lianxibu_session");String csrf=json.readTree(login.body()).path("data").path("csrf_token").asText();
        assertEquals(401,request("GET","/private/records",null,null,null,null).statusCode());
        assertEquals(403,request("PUT","/private/records/node/fixture-node",cookie,"wrong",ORIGIN,body).statusCode());
        var saved=request("PUT","/private/records/node/fixture-node",cookie,csrf,ORIGIN,body);
        assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(200,request("PUT","/private/records/node/fixture-node",cookie,csrf,ORIGIN,body).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM user_records",Integer.class));
        String ownerCookie=cookie(login("fictional-owner",PASSWORD,ORIGIN),"lianxibu_session");
        var listing=request("GET","/private/records?package_id=fixture-package&kind=node",ownerCookie,null,null,null);
        assertEquals(200,listing.statusCode(),listing.body());assertEquals(0,json.readTree(listing.body()).path("data").path("total").asInt());
        jdbc.update("UPDATE learning_packages SET state='disabled'");
        assertNotEquals(200,request("PUT","/private/records/node/missing-node",cookie,csrf,ORIGIN,body).statusCode());
    }

    @Test void articleRecordsRuntimeScopesRowsAndStatisticsToSessionUser() throws Exception {
        for(String table:new String[]{"user_records","articles","sources"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE sources(id VARCHAR(64) PRIMARY KEY,name VARCHAR(200))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(64),title VARCHAR(200),summary CLOB,url VARCHAR(2000),published_at TIMESTAMP,updated_at TIMESTAMP,revision BIGINT DEFAULT 1,latest_revision_id VARCHAR(64))");
        jdbc.execute("DROP TABLE IF EXISTS article_revisions");
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,article_id VARCHAR(64),content_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE user_records(user_id VARCHAR(64),package_id VARCHAR(64),object_kind VARCHAR(16),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),self_assessment VARCHAR(32),note CLOB,artifact_url VARCHAR(2000),confirmed_body_revision BIGINT,updated_at TIMESTAMP,is_read BOOLEAN,is_favorite BOOLEAN,is_ignored BOOLEAN,PRIMARY KEY(user_id,object_kind,package_id,object_id))");
        jdbc.update("INSERT INTO sources VALUES('fictional-source','Fictional source')");
        jdbc.update("INSERT INTO articles(id,source_id,title,url,updated_at) VALUES('fictional-article','fictional-source','Fictional article','https://example.invalid/article',CURRENT_TIMESTAMP)");
        jdbc.execute("ALTER TABLE user_records ADD response_text CLOB");
        for(String user:new String[]{"fictional-reader","fictional-owner"})jdbc.update("INSERT INTO user_records VALUES(?,'','article','fictional-article',1,'planned','unassessed',?,NULL,NULL,CURRENT_TIMESTAMP,FALSE,TRUE,FALSE,NULL)",user,user+" private note");
        jdbc.update("INSERT INTO user_records VALUES('fictional-reader','','article','fictional-missing',1,'completed','unassessed',NULL,NULL,NULL,CURRENT_TIMESTAMP,TRUE,FALSE,FALSE,NULL)");
        assertEquals(401,request("GET","/private/article-records",null,null,null,null).statusCode());
        String reader=cookie(login("fictional-reader",PASSWORD,ORIGIN),"lianxibu_session");
        var filtered=request("GET","/private/article-records?favorite=true",reader,null,null,null);
        assertEquals(200,filtered.statusCode(),filtered.body());
        var data=json.readTree(filtered.body()).path("data");
        assertEquals(1,data.path("total").asInt());assertEquals(2,data.path("stats").path("total").asInt());
        assertEquals("fictional-reader private note",data.path("items").get(0).path("record").path("note").asText());
        assertFalse(filtered.body().contains("fictional-owner private note"));
        String owner=cookie(login("fictional-owner",PASSWORD,ORIGIN),"lianxibu_session");
        var ownerResult=request("GET","/private/article-records",owner,null,null,null);
        assertEquals(200,ownerResult.statusCode(),ownerResult.body());
        assertEquals(1,json.readTree(ownerResult.body()).path("data").path("stats").path("total").asInt());
        assertFalse(ownerResult.body().contains("fictional-reader private note"));
        var missing=request("GET","/private/article-records?status=completed",reader,null,null,null);
        assertEquals(200,missing.statusCode(),missing.body());
        assertTrue(json.readTree(missing.body()).path("data").path("items").get(0).path("article").isNull());
        assertEquals(400,request("GET","/private/article-records?user_id=fictional-owner",reader,null,null,null).statusCode());
        assertEquals(401,request("GET","/private/articles",null,null,null,null).statusCode());
        var articles=request("GET","/private/articles?status=planned",reader,null,null,null);
        assertEquals(200,articles.statusCode(),articles.body());
        assertEquals("unknown",json.readTree(articles.body()).path("data").path("items").get(0).path("content_status").asText());
        assertFalse(articles.body().contains("fictional-owner private note"));
        assertEquals(404,request("GET","/private/articles/missing",reader,null,null,null).statusCode());
        assertEquals(403,request("GET","/admin/entities",reader,null,null,null).statusCode());
        jdbc.execute("DROP TABLE IF EXISTS analyzed_entities");
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),title VARCHAR(512),summary CLOB,first_recorded_at TIMESTAMP,first_seen_business_date DATE,last_seen_at TIMESTAMP,occurrence_count BIGINT,repeat_count BIGINT,analysis_state VARCHAR(32),revision BIGINT)");
        jdbc.execute("DROP TABLE IF EXISTS source_run_items");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64),article_id VARCHAR(64),source_id VARCHAR(80),category VARCHAR(16),entity_id VARCHAR(64),state VARCHAR(32),terminal_state VARCHAR(32),effective_valid INT)");
        jdbc.update("INSERT INTO analyzed_entities VALUES('fictional-entity','news_tech','Fictional entity',NULL,NULL,NULL,NULL,1,0,'pending',1)");
        jdbc.update("INSERT INTO source_run_items VALUES('fictional-item','fictional-article','fictional-source','news_tech','fictional-entity','ready','accepted',1)");
        var enriched=request("GET","/private/articles/fictional-article?include_entity=true",reader,null,null,null);
        assertEquals(200,enriched.statusCode(),enriched.body());
        assertEquals("fictional-entity",json.readTree(enriched.body()).path("data").path("entity_summary").path("id").asText());
        assertEquals(200,request("GET","/admin/entities/fictional-entity",owner,null,null,null).statusCode());
        assertEquals(403,request("POST","/admin/entities/fictional-entity/actions",owner,"wrong",ORIGIN,"{}").statusCode());
    }

    @Test void sourceSnapshotRuntimeRequiresOwnerAndExplicitCurrentMapping() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS sources");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(16),category VARCHAR(16),draft_config_json CLOB,effective_config_json CLOB,config_valid_until TIMESTAMP,config_json CLOB)");
        jdbc.update("INSERT INTO sources VALUES('fictional-source','article','Fictional source',2,1,'disabled','news_tech',NULL,NULL,NULL,'private legacy marker')");
        assertEquals(401,request("GET","/admin/sources/fictional-source",null,null,null,null).statusCode());
        String reader=cookie(login("fictional-reader",PASSWORD,ORIGIN),"lianxibu_session");
        assertEquals(403,request("GET","/admin/sources/fictional-source",reader,null,null,null).statusCode());
        String owner=cookie(login("fictional-owner",PASSWORD,ORIGIN),"lianxibu_session");
        var result=request("GET","/admin/sources/fictional-source",owner,null,null,null);
        assertEquals(200,result.statusCode(),result.body());assertFalse(result.body().contains("private legacy marker"));
        assertEquals(2,json.readTree(result.body()).path("data").path("revision").asInt());
        assertEquals(404,request("GET","/admin/sources/missing",owner,null,null,null).statusCode());
        jdbc.update("UPDATE sources SET category=NULL");
        var legacy=request("GET","/admin/sources/fictional-source",owner,null,null,null);
        assertEquals(503,legacy.statusCode());assertEquals("source_mapping_required",json.readTree(legacy.body()).path("error").path("code").asText());
        assertFalse(legacy.body().contains("private legacy marker"));
        assertEquals(403,request("PUT","/admin/sources/fictional-source",owner,"fixture-csrf",ORIGIN,"{}").statusCode());
    }

    @Test void sourceCommandsRuntimePersistsOriginalRepliesAndChecksCsrf() throws Exception {
        for(String table:new String[]{"sources","worker_lock","request_receipts"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),state VARCHAR(16),config_json CLOB,probe_json CLOB,revision BIGINT,updated_at TIMESTAMP,draft_config_json CLOB,effective_config_json CLOB,config_revision BIGINT,activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        String config="""
          {"adapter":"fictional-adapter","entry_urls":["https://example.invalid/feed"],"identity_rule":"native-id","window_rule":"daily","parser_version":"fictional-parser","max_items":20,"max_response_bytes":200000,"allowed_hosts":["example.invalid"],"valid_until":"2099-01-01T00:00:00.000000Z","auth_secret_ref":null}
          """;
        String create="{\"id\":\"fictional-source\",\"name\":\"Fictional original\",\"category\":\"news_tech\",\"config\":"+config+"}";
        var login=login("fictional-owner",PASSWORD,ORIGIN);String owner=cookie(login,"lianxibu_session"),csrf=json.readTree(login.body()).path("data").path("csrf_token").asText();
        assertEquals(401,request("GET","/admin/sources",null,null,null,null).statusCode());
        assertEquals(403,request("POST","/admin/sources",owner,"wrong",ORIGIN,create).statusCode());
        var first=request("POST","/admin/sources",owner,csrf,ORIGIN,create);assertEquals(200,first.statusCode(),first.body());
        String edit="{\"expected_revision\":1,\"name\":\"Fictional edited\",\"category\":\"news_tech\",\"config\":"+config+"}";
        var edited=request("PUT","/admin/sources/fictional-source",owner,csrf,ORIGIN,edit);assertEquals(200,edited.statusCode(),edited.body());
        var replay=request("POST","/admin/sources",owner,csrf,ORIGIN,create);assertEquals(200,replay.statusCode(),replay.body());assertEquals("Fictional original",json.readTree(replay.body()).path("data").path("name").asText());
        var list=request("GET","/admin/sources",owner,null,null,null);assertEquals(200,list.statusCode(),list.body());assertEquals("Fictional edited",json.readTree(list.body()).path("data").path("items").get(0).path("name").asText());
        jdbc.update("UPDATE sources SET activation_stage='production'");
        String disable="{\"expected_revision\":2,\"action\":\"disable\",\"reason\":\"Fictional maintenance\"}";
        var disabled=request("POST","/admin/sources/fictional-source/actions",owner,csrf,ORIGIN,disable);assertEquals(200,disabled.statusCode(),disabled.body());
        assertEquals("disabled",jdbc.queryForObject("SELECT activation_stage FROM sources",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }

    @Test void rejectsBadPasswordForeignOriginAndUnmappedRole() throws Exception {
        assertEquals(401,login("fictional-owner","incorrect",ORIGIN).statusCode());
        assertEquals(403,login("fictional-owner",PASSWORD,"https://foreign.example.invalid").statusCode());
        assertEquals(403,login("fictional-unmapped",PASSWORD,ORIGIN).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions",Integer.class));
        assertEquals(200,login("fictional-reader",PASSWORD,ORIGIN).statusCode());
        assertEquals(401,request("GET","/admin/collector-nodes",null,null,null,null).statusCode());
        assertEquals(403,request("GET","/reports",null,null,null,null).statusCode());
    }

    @Test void failedSessionReplacementRollsBackRevocation() {
        assertNotNull(browser);
        var anonymous=browser.session(null,null);
        var first=browser.login(new IdentityService.LoginCommand("fictional-owner",PASSWORD),null,
            anonymous.anonymousToken(),anonymous.session().csrfToken(),ORIGIN);
        jdbc.execute("ALTER TABLE user_sessions ADD CONSTRAINT single_session CHECK(id='"+
            jdbc.queryForObject("SELECT id FROM user_sessions",String.class)+"')");
        assertThrows(RuntimeException.class,()->browser.login(new IdentityService.LoginCommand("fictional-owner",PASSWORD),
            first.sessionToken(),null,first.session().csrfToken(),ORIGIN));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE revoked_at IS NOT NULL",Integer.class));
        assertEquals("fictional-owner",browser.authenticate(first.sessionToken()).userId());
    }

    @Test void legacyDirectoryIsOwnerOnlyAndNeverRewritesOriginalContent() throws Exception {
        String before=jdbc.queryForObject("SELECT body_json FROM contents",String.class);
        assertEquals(401,request("GET","/admin/content",null,null,null,null).statusCode());
        String reader=cookie(login("fictional-reader",PASSWORD,ORIGIN),"lianxibu_session");
        assertEquals(403,request("GET","/admin/content",reader,null,null,null).statusCode());
        String owner=cookie(login("fictional-owner",PASSWORD,ORIGIN),"lianxibu_session");
        var response=request("GET","/admin/content?kind=report",owner,null,null,null);
        assertEquals(200,response.statusCode());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        var data=json.readTree(response.body()).path("data");
        assertEquals(1,data.path("total").asInt());
        assertEquals("fictional-legacy",data.path("items").get(0).path("id").asText());
        assertTrue(data.path("items").get(0).path("body_revision").isNull());
        assertFalse(response.body().contains("fictional original body"));
        assertEquals(400,request("GET","/admin/content?include_private=true",owner,null,null,null).statusCode());
        assertEquals(403,request("GET","/reports/fictional-legacy",owner,null,null,null).statusCode());
        assertEquals(before,jdbc.queryForObject("SELECT body_json FROM contents",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT revision FROM contents",Integer.class));
        assertEquals("published",jdbc.queryForObject("SELECT state FROM contents",String.class));
    }

    @Test void settingsRuntimePersistsMappingAndPauseWithOwnerAuthorization() throws Exception {
        for (String table : new String[]{"worker_lock", "runtime_settings", "request_receipts", "learning_packages"}) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)"); jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB,revision BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.update("INSERT INTO learning_packages VALUES('fixture-package','Fictional','published',1)");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,CURRENT_TIMESTAMP)", "{\"watch_terms_raw\":\"Fictional legacy || text\"}");
        assertEquals(401, request("GET","/admin/preferences",null,null,null,null).statusCode());
        var reader=login("fictional-reader",PASSWORD,ORIGIN);
        assertEquals(403, request("GET","/admin/preferences",cookie(reader,"lianxibu_session"),null,null,null).statusCode());
        var owner=login("fictional-owner",PASSWORD,ORIGIN);String cookie=cookie(owner,"lianxibu_session"),csrf=json.readTree(owner.body()).path("data").path("csrf_token").asText();
        assertEquals(503,request("GET","/admin/preferences",cookie,null,null,null).statusCode());
        String body="""
            {"expected_revision":1,"follow_keywords":["Fictional"],"blocked_keywords":[],"ai_paused":true,"default_package_id":"fixture-package","operational_limits":[]}
            """;
        assertEquals(403,request("PUT","/admin/preferences",cookie,"wrong",ORIGIN,body).statusCode());
        var result=request("PUT","/admin/preferences",cookie,csrf,ORIGIN,body); assertEquals(200,result.statusCode(),result.body());
        assertEquals(200,request("PUT","/admin/preferences",cookie,csrf,ORIGIN,body).statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT revision FROM runtime_settings WHERE setting_key='preferences'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        var raw=json.readTree(jdbc.queryForObject("SELECT value_json FROM runtime_settings WHERE setting_key='preferences'",String.class));
        assertEquals("Fictional legacy || text",raw.path("watch_terms_raw").asText());
        assertTrue(json.readTree(jdbc.queryForObject("SELECT value_json FROM runtime_settings WHERE setting_key='pause_intent-v1'",String.class)).path("paused").asBoolean());
        var read=request("GET","/admin/preferences",cookie,null,null,null); assertEquals(200,read.statusCode(),read.body());
        assertEquals("fixture-package",json.readTree(read.body()).path("data").path("default_package_id").asText());
    }

    @Test void learningRuntimeImportsPublishesAndGuardsAnswerReview() throws Exception {
        for(String table:new String[]{"learning_links","learning_items","learning_packages","request_receipts","worker_lock"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT,content_hash BINARY(32),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),id VARCHAR(64),title VARCHAR(200),position_no INT,body_json CLOB,answer_text CLOB,answer_state VARCHAR(16),revision BIGINT,answer_generation_state VARCHAR(32),answer_revision BIGINT,answer_review_hash_v1 BINARY(32),PRIMARY KEY(package_id,kind,id))");
        jdbc.execute("CREATE TABLE learning_links(package_id VARCHAR(64),from_kind VARCHAR(16),from_id VARCHAR(64),relation_kind VARCHAR(24),to_kind VARCHAR(16),to_id VARCHAR(64),position_no INT,PRIMARY KEY(package_id,from_kind,from_id,relation_kind,to_kind,to_id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64) UNIQUE,receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        var login=login("fictional-owner",PASSWORD,ORIGIN);String cookie=cookie(login,"lianxibu_session"),csrf=json.readTree(login.body()).path("data").path("csrf_token").asText();
        var question=json.readTree("""
            {"package_id":"temporary","id":"q","title":"Fictional question","question":"Fictional?","difficulty":"beginner","kind":"concept","assessment_points":[],"answer_state":"draft","generation_state":"not_generated","revision":0}
            """);
        var items=java.util.List.of(question);
        String hash=com.lianxibu.site.service.learning.LearningImportService.partHash("fictional-import",0,1,items);
        var chunk=json.createObjectNode();chunk.put("import_id","fictional-import");chunk.put("part_no",0);chunk.put("part_count",1);chunk.put("part_hash",hash);chunk.putArray("items").add(question);
        var stage=json.createObjectNode();stage.put("action","stage");stage.set("chunk",chunk);
        assertEquals(401,request("POST","/admin/learning/packages",null,null,null,stage.toString()).statusCode());
        assertEquals(403,request("POST","/admin/learning/packages",cookie,"wrong",ORIGIN,stage.toString()).statusCode());
        var staged=request("POST","/admin/learning/packages",cookie,csrf,ORIGIN,stage.toString());assertEquals(200,staged.statusCode(),staged.body());
        var manifest=json.createObjectNode();manifest.put("import_id","fictional-import");manifest.put("title","Fictional package");manifest.put("description","Persistent fictional description");manifest.put("part_count",1);
        manifest.put("manifest_hash",com.lianxibu.site.service.learning.LearningImportService.manifestHash("fictional-import",java.util.List.of(new com.lianxibu.site.service.learning.LearningImportService.Part(0,1,hash,items))));
        var commit=json.createObjectNode();commit.put("action","commit");commit.set("manifest",manifest);
        var saved=request("POST","/admin/learning/packages",cookie,csrf,ORIGIN,commit.toString());assertEquals(200,saved.statusCode(),saved.body());
        String pkg=json.readTree(saved.body()).path("data").path("id").asText();assertFalse(pkg.isEmpty(),saved.body());
        String admin="/admin/learning/packages/"+pkg,publicQuestion="/learning/packages/"+pkg+"/questions/q";
        assertEquals(404,request("GET",publicQuestion,null,null,null,null).statusCode());
        for(int rev=1;rev<=2;rev++){String body=json.writeValueAsString(java.util.Map.of("expected_revision",rev,"action",rev==1?"approve":"publish","reason","Fictional review"));var result=request("POST",admin+"/actions",cookie,csrf,ORIGIN,body);assertEquals(200,result.statusCode(),result.body());}
        assertEquals(200,request("GET",publicQuestion,null,null,null,null).statusCode());
        String edit="{\"expected_revision\":1,\"answer\":\"Fictional verified answer\"}";
        var edited=request("PUT",admin+"/questions/q/answer",cookie,csrf,ORIGIN,edit);assertEquals(200,edited.statusCode(),edited.body());
        String ref=json.readTree(edited.body()).path("data").path("operation_ref").asText();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE id=?",Integer.class,ref));
        assertEquals(ref,json.readTree(request("PUT",admin+"/questions/q/answer",cookie,csrf,ORIGIN,edit).body()).path("data").path("operation_ref").asText());
        for(int rev=2;rev<=3;rev++){String body=json.writeValueAsString(java.util.Map.of("expected_revision",rev,"action",rev==2?"approve":"publish","reason","Fictional review"));var result=request("POST",admin+"/questions/q/answer/actions",cookie,csrf,ORIGIN,body);assertEquals(200,result.statusCode(),result.body());}
        var visible=request("GET",publicQuestion,null,null,null,null);assertEquals(200,visible.statusCode(),visible.body());assertTrue(visible.body().contains("Fictional verified answer"));
        var metadata=request("GET",admin,cookie,null,null,null);assertEquals(200,metadata.statusCode(),metadata.body());assertTrue(metadata.body().contains("Persistent fictional description"));
        jdbc.update("UPDATE learning_items SET answer_text='Unreviewed replacement' WHERE package_id=?",pkg);
        var rejected=request("GET",publicQuestion,null,null,null,null);assertEquals(503,rejected.statusCode());assertFalse(rejected.body().contains("Unreviewed replacement"));
    }

    private HttpResponse<String> login(String user,String password,String origin) throws Exception {
        var anonymous=request("GET","/session",null,null,null,null);
        assertEquals(200,anonymous.statusCode());
        JsonNode data=json.readTree(anonymous.body()).path("data");
        String body=json.writeValueAsString(java.util.Map.of("username",user,"password",password));
        return request("POST","/session",cookie(anonymous,"lianxibu_anonymous"),data.path("csrf_token").asText(),origin,body);
    }
    private HttpResponse<String> request(String method,String path,String cookie,String csrf,String origin,String body) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(cookie!=null)req.header("Cookie",cookie);
        if(csrf!=null)req.header("X-CSRF-Token",csrf);
        if(origin!=null)req.header("Origin",origin);
        if(body!=null)req.header("Content-Type","application/json");
        if("PUT".equals(method)||("POST".equals(method)&&!"/session".equals(path)))req.header("Idempotency-Key","fixture-"+Integer.toHexString(java.util.Objects.hash(method,path,body)));
        try(var client=HttpClient.newHttpClient()) {return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    private static String cookie(HttpResponse<?> response,String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow().split(";",2)[0];
    }
}
