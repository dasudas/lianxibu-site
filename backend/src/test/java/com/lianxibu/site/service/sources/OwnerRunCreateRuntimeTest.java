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

class OwnerRunCreateRuntimeTest extends OwnerRunCreateServiceTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import({SourcesController.class,SourcesRuntimeConfiguration.class,BrowserIdentityConfiguration.class,BootstrapSecurityConfiguration.class,OperationalTestFixture.class})
    static class App { }
    @Test void realOwnerCreatesMaterializedRunAndReplaysOriginalResponseAfterClaim() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS user_sessions");jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users(id VARCHAR(64) PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(255))");
        jdbc.execute("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),token_hash BINARY(32),csrf_hash BINARY(32),expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        String password="fictional-long-password",origin="https://site.example.invalid";
        jdbc.update("INSERT INTO users VALUES('fictional-owner','fictional-owner',?)",new Argon2Passwords().encode(password));
        var ds=jdbc.getDataSource();
        try(var app=new SpringApplicationBuilder(App.class).properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","logging.level.root=ERROR")
            .initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(javax.sql.DataSource.class,()->ds);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));})
            .run("--site.browser.enabled=true","--site.sources.enabled=true","--SITE_BROWSER_ORIGIN="+origin,"--SITE_OWNER_IDS=fictional-owner");
            var client=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var browser=app.getBean(BrowserSessionService.class);var challenge=browser.session(null,null);
            var session=browser.login(new IdentityService.LoginCommand("fictional-owner",password),null,challenge.anonymousToken(),challenge.session().csrfToken(),origin);
            String body="{\"source_id\":\"fixture:feed\",\"business_date\":\"2026-01-01\",\"window_start\":\"2025-12-31T16:00:00.000000Z\",\"window_end\":\"2026-01-01T16:00:00.000000Z\",\"run_kind\":\"historical_backfill\",\"config_version\":1,\"expected_revision\":1,\"reason\":\"Fictional collection\"}";
            var builder=HttpRequest.newBuilder(URI.create(root+"/admin/source-runs")).header("Cookie","lianxibu_session="+session.sessionToken()).header("Origin",origin).header("Content-Type","application/json").header("Idempotency-Key","fictional-http-create");
            assertEquals(403,client.send(builder.copy().header("X-CSRF-Token","wrong").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var request=builder.header("X-CSRF-Token",session.session().csrfToken()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var first=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,first.statusCode(),first.body());
            var original=CanonicalJson.parse(first.body()).get("data");String id=original.get("run_id").asText();
            jdbc.update("UPDATE source_runs SET delivery_state='claimed',revision=3 WHERE id=?",id);
            assertEquals(original,CanonicalJson.parse(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).get("data"));
            var changed=builder.POST(HttpRequest.BodyPublishers.ofString(body.replace("Fictional collection","Different request"))).build();
            assertEquals(409,client.send(changed,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));
        }
    }
}
