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
    "SITE_USER_IDS=fictional-reader"
})
class BrowserRuntimeConfigurationTest {
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
        try(var client=HttpClient.newHttpClient()) {return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    private static String cookie(HttpResponse<?> response,String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow().split(";",2)[0];
    }
}
