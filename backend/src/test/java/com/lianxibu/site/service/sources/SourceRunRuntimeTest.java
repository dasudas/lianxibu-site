package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.sources.SourcesController;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.identity.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

class SourceRunRuntimeTest extends SourceRunReadServiceTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import({SourcesController.class,SourcesRuntimeConfiguration.class,BrowserIdentityConfiguration.class,BootstrapSecurityConfiguration.class})
    static class App { }
    @Test void productionReadAssemblyRequiresOwnerAndNeverLeaksFailedStorage() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS user_sessions");jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users(id VARCHAR(64) PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(255))");
        jdbc.execute("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),token_hash BINARY(32),csrf_hash BINARY(32),expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        String password="fictional-long-password", origin="https://site.example.invalid";
        String hash=new Argon2Passwords().encode(password);
        jdbc.update("INSERT INTO users VALUES('fictional-owner','fictional-owner',?),('fictional-user','fictional-user',?)",hash,hash);
        var ds=jdbc.getDataSource();
        try(var app=new SpringApplicationBuilder(App.class).properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","logging.level.root=ERROR")
            .initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(javax.sql.DataSource.class,()->ds);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));})
            .run("--site.browser.enabled=true","--site.sources.enabled=true","--SITE_BROWSER_ORIGIN="+origin,"--SITE_OWNER_IDS=fictional-owner","--SITE_USER_IDS=fictional-user");
            var client=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var browser=app.getBean(BrowserSessionService.class);
            java.util.function.Function<String,String> login=name->{var challenge=browser.session(null,null);return browser.login(new IdentityService.LoginCommand(name,password),null,challenge.anonymousToken(),challenge.session().csrfToken(),origin).sessionToken();};
            String ownerToken=login.apply("fictional-owner"),userToken=login.apply("fictional-user");
            var denied=client.send(HttpRequest.newBuilder(URI.create(root+"/admin/source-runs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,denied.statusCode());
            assertEquals(403,get(client,root,"/admin/source-runs",userToken).statusCode());
            var page=get(client,root,"/admin/source-runs?source_id=fictional-source&page_size=1",ownerToken);assertEquals(200,page.statusCode(),page.body());
            assertEquals(2,CanonicalJson.parse(page.body()).at("/data/total").asInt());assertEquals("no-store",page.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(200,get(client,root,"/admin/source-runs/fictional-run-1",ownerToken).statusCode());
            assertEquals(200,get(client,root,"/admin/source-runs/fictional-run-1/items",ownerToken).statusCode());
            assertEquals(404,get(client,root,"/admin/source-runs/fictional-missing/items",ownerToken).statusCode());
            jdbc.update("UPDATE source_runs SET coverage_json='[\"fictional-private-payload\"]'");
            var bad=get(client,root,"/admin/source-runs",ownerToken);assertEquals(503,bad.statusCode());assertFalse(bad.body().contains("fictional-private-payload"));assertFalse(bad.body().contains("SELECT"));
        }
    }
    private static HttpResponse<String> get(HttpClient c,String root,String path,String token) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(root+path)).header("Cookie","lianxibu_session="+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
