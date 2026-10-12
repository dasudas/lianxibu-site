package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.content.ContentController;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

class OwnerContentRuntimeTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import({ContentController.class,com.lianxibu.site.controller.content.VersionDispositionController.class,PublicContentRuntimeConfiguration.class,BrowserIdentityConfiguration.class,BootstrapSecurityConfiguration.class})
    static class App { }
    static final String ORIGIN="https://site.example.invalid",PATH="/admin/content/agent/fictional-public-agent";
    PublicContentRuntimeTest data;ConfigurableApplicationContext app;HttpClient client;String root;
    IdentityService.SessionOutcome owner,user;
    VersionValidityJdbcStoreTest fixture(){var value=new VersionValidityJdbcStoreTest();value.setup();return value;}
    @BeforeEach void setup() {
        data=new PublicContentRuntimeTest();data.f=fixture();data.seed("agent",true,false);
        var jdbc=data.f.fixture.jdbc;
        jdbc.execute("DROP TABLE IF EXISTS user_sessions");jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users(id VARCHAR(64) PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(255))");
        jdbc.execute("CREATE TABLE user_sessions(id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(64),token_hash BINARY(32),csrf_hash BINARY(32),expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        String hash=new Argon2Passwords().encode("fictional-long-password");
        jdbc.update("INSERT INTO users VALUES('fictional-owner','fictional-owner',?),('fictional-user','fictional-user',?)",hash,hash);
        app=new SpringApplicationBuilder(App.class).properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","logging.level.root=ERROR")
            .initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(javax.sql.DataSource.class,()->data.f.fixture.source);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->data.f.fixture.tx);})
            .run("--site.browser.enabled=true","--site.content.enabled=true","--SITE_BROWSER_ORIGIN="+ORIGIN,"--SITE_OWNER_IDS=fictional-owner","--SITE_USER_IDS=fictional-user");
        root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();client=HttpClient.newHttpClient();
        owner=login("fictional-owner");user=login("fictional-user");
    }
    IdentityService.SessionOutcome login(String name){var browser=app.getBean(BrowserSessionService.class);var challenge=browser.session(null,null);return browser.login(new IdentityService.LoginCommand(name,"fictional-long-password"),null,challenge.anonymousToken(),challenge.session().csrfToken(),ORIGIN);}
    @AfterEach void close() throws Exception {if(client!=null)client.close();if(app!=null)app.close();if(data!=null)data.closeFixture();}
    HttpResponse<String> request(String path,String method,IdentityService.SessionOutcome session,String csrf,String origin,String key,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(10));
        if(session!=null)builder.header("Cookie","lianxibu_session="+session.sessionToken());
        if(csrf!=null)builder.header("X-CSRF-Token",csrf);if(origin!=null)builder.header("Origin",origin);if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> action(String action,long revision,String key) throws Exception {
        return request(PATH+"/actions","POST",owner,owner.session().csrfToken(),ORIGIN,key,"{\"expected_revision\":"+revision+",\"action\":\""+action+"\",\"reason\":\"Fictional owner review\"}");
    }
    @Test void actualOwnerSessionReviewsAndPublishesWithPermanentReplayAndInvalidationVisibility() throws Exception {
        var draft=request(PATH,"GET",owner,null,null,null,null);assertEquals(200,draft.statusCode(),draft.body());
        assertEquals("draft",CanonicalJson.parse(draft.body()).at("/data/publication_state").asString());assertTrue(CanonicalJson.parse(draft.body()).at("/data/review_required").asBoolean());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        var approved=action("approve",1,"fictional-owner-approve");assertEquals(200,approved.statusCode(),approved.body());
        var original=CanonicalJson.parse(approved.body()).get("data");assertEquals(2,original.get("result_revision").asInt());
        assertEquals(original,CanonicalJson.parse(action("approve",1,"fictional-owner-approve").body()).get("data"));
        assertEquals(409,action("publish",1,"fictional-owner-approve").statusCode());assertEquals(409,action("publish",1,"fictional-stale").statusCode());
        var published=action("publish",2,"fictional-owner-publish");assertEquals(200,published.statusCode(),published.body());
        assertEquals(original,CanonicalJson.parse(action("approve",1,"fictional-owner-approve").body()).get("data"));
        assertEquals(200,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        String operation=original.get("operation_ref").asString();assertEquals("fictional-owner",data.f.fixture.jdbc.queryForObject("SELECT operator_id FROM jobs WHERE id=?",String.class,operation));
        data.f.invalidate(data.f.base.article(),0,"fictional-owner-invalidate");
        var detail=request(PATH,"GET",owner,null,null,null,null);assertEquals(200,detail.statusCode(),detail.body());assertTrue(detail.body().contains("invalid_evidence"));assertTrue(CanonicalJson.parse(detail.body()).at("/data/review_required").asBoolean());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());assertEquals(409,action("approve",3,"fictional-invalid-review").statusCode());
        assertFalse(detail.body().contains("Fictional input"));assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElseThrow());
    }
    @Test void authenticationOriginCsrfAndMissingProofCannotWriteOrLeakDetail() throws Exception {
        assertEquals(401,request(PATH,"GET",null,null,null,null,null).statusCode());assertEquals(403,request(PATH,"GET",user,null,null,null,null).statusCode());
        String body="{\"expected_revision\":1,\"action\":\"approve\",\"reason\":\"Fictional review\"}";
        assertEquals(403,request(PATH+"/actions","POST",owner,"wrong",ORIGIN,"fictional-wrong-csrf",body).statusCode());
        assertEquals(403,request(PATH+"/actions","POST",owner,owner.session().csrfToken(),"https://other.example.invalid","fictional-wrong-origin",body).statusCode());
        assertEquals(403,request(PATH+"/actions","POST",user,user.session().csrfToken(),ORIGIN,"fictional-wrong-user",body).statusCode());
        assertEquals(1,data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow().revision());
        assertThrows(IdentityService.Rejected.class,()->app.getBean(ContentService.class).adminContent(new IdentityService.Actor("fictional-user","user"),"agent","fictional-public-agent"));
        assertEquals(404,request("/admin/content/agent/fictional-missing","GET",owner,null,null,null,null).statusCode());
        data.f.fixture.jdbc.update("UPDATE contents SET input_job_id=NULL WHERE kind='agent'");
        var invalid=request(PATH,"GET",owner,null,null,null,null);assertEquals(503,invalid.statusCode());assertFalse(invalid.body().contains("Fictional public text"));
    }
    @Test void failedReceiptRollsBackAndUnavailableActionIsNeverReportedSuccessful() throws Exception {
        data.f.fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_owner_receipt_failure CHECK(target_type IS NULL OR target_type<>'content_review_action')");
        var response=action("approve",1,"fictional-failed-review");assertEquals(503,response.statusCode(),response.body());assertFalse(response.body().contains("SELECT"));
        assertEquals("draft",data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow().state());
        assertEquals(503,action("supplement_generate",1,"fictional-not-installed").statusCode());
    }
    @Test void rejectionClosesPublicationRetainsExactBodyAndReplaysOriginalReceipt() throws Exception {
        assertEquals(200,action("approve",1,"fictional-before-reject-approve").statusCode());
        assertEquals(200,action("publish",2,"fictional-before-reject-publish").statusCode());
        var rejected=action("reject",3,"fictional-reject");assertEquals(200,rejected.statusCode(),rejected.body());
        var original=CanonicalJson.parse(rejected.body()).get("data");assertEquals(4,original.get("result_revision").asInt());
        assertEquals(original,CanonicalJson.parse(action("reject",3,"fictional-reject").body()).get("data"));
        var current=data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow();
        assertEquals("draft",current.state());assertEquals(1,current.bodyRevision());assertNull(current.reviewStampHash());assertNull(current.publishedAt());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        assertEquals(409,action("publish",4,"fictional-rejected-publish").statusCode());
        assertEquals(200,action("approve",4,"fictional-reapprove").statusCode());
        assertEquals(original,CanonicalJson.parse(action("reject",3,"fictional-reject").body()).get("data"));
        assertEquals("approved",data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow().state());
    }
    HttpResponse<String> edit(long revision,String key,String title,String entity,String citation) throws Exception {
        String body="{\"expected_revision\":"+revision+",\"title\":\""+title+"\",\"sections\":[],\"citation_ids\":[\""+citation+"\"],\"category\":\"agent\",\"entity_id\":\""+entity+"\"}";
        return request(PATH,"PUT",owner,owner.session().csrfToken(),ORIGIN,key,body);
    }
    @Test void editingPublishedBodyReturnsOriginalVersionOnReplayAfterLaterEdit() throws Exception {
        assertEquals(200,action("approve",1,"fictional-edit-approve").statusCode());assertEquals(200,action("publish",2,"fictional-edit-publish").statusCode());
        var first=edit(3,"fictional-first-edit","Fictional first edit","fictional-entity","fictional-citation");assertEquals(200,first.statusCode(),first.body());
        var original=CanonicalJson.parse(first.body()).get("data");assertEquals("draft",original.get("publication_state").asString());assertEquals(2,original.get("body_revision").asInt());assertEquals(4,original.get("revision").asInt());assertTrue(original.get("review_required").asBoolean());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        var second=edit(4,"fictional-second-edit","Fictional second edit","fictional-entity","fictional-citation");assertEquals(200,second.statusCode(),second.body());
        var replay=edit(3,"fictional-first-edit","Fictional first edit","fictional-entity","fictional-citation");assertEquals(200,replay.statusCode(),replay.body());assertEquals(original,CanonicalJson.parse(replay.body()).get("data"));
        var current=data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow();assertEquals("Fictional second edit",current.title());assertEquals(3,current.bodyRevision());assertEquals(5,current.revision());
        assertEquals(409,edit(3,"fictional-first-edit","Fictional changed replay","fictional-entity","fictional-citation").statusCode());
        assertEquals(409,edit(5,"fictional-wrong-entity","Fictional bad entity","fictional-other-entity","fictional-citation").statusCode());
        assertNotEquals(200,edit(5,"fictional-wrong-reference","Fictional bad reference","fictional-entity","fictional-not-in-input").statusCode());
        assertEquals(5,data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow().revision());
    }
    ContentReadService qualifiedReads(){var f=data.f;return new ContentReadService(new com.lianxibu.site.repository.content.JdbcContentReads(f.fixture.source),f.fixture.tx,new ReviewPublicationService(f.fixture.writes,new ReviewPublicationJdbcStore(null,f.base.writer,f.base.contents,f.audit,f.base.versions,f.base.ledger)));}
    HttpResponse<String> withdraw(long revision,String key,String cause) throws Exception {
        return request(PATH+"/actions","POST",owner,owner.session().csrfToken(),ORIGIN,key,
            "{\"expected_revision\":"+revision+",\"action\":\"withdraw\",\"reason\":\"Fictional withdrawal\",\"withdrawal_reason\":\""+cause+"\"}");
    }
    @Test void displayWithdrawalPreservesValidityAndReplaysOriginalCommand() throws Exception {
        action("approve",1,"fictional-display-approve");action("publish",2,"fictional-display-publish");
        var response=withdraw(3,"fictional-display-withdraw","display_adjustment");assertEquals(200,response.statusCode(),response.body());
        var original=CanonicalJson.parse(response.body()).get("data");assertEquals(4,original.get("result_revision").asInt());
        assertEquals(original,CanonicalJson.parse(withdraw(3,"fictional-display-withdraw","display_adjustment").body()).get("data"));
        assertEquals(409,withdraw(3,"fictional-display-withdraw","conclusion_error").statusCode());
        assertEquals(409,withdraw(4,"fictional-second-withdraw","display_adjustment").statusCode());
        assertEquals(0,data.f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='content_version'",Integer.class));
        assertEquals(0,data.f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE task_type='validity_propagation'",Integer.class));
        var current=data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow();assertEquals("withdrawn",current.state());assertEquals(1,current.bodyRevision());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
    }
    @Test void conclusionWithdrawalInvalidatesExactBodyAndRollbackIncludesQueue() throws Exception {
        action("approve",1,"fictional-conclusion-approve");action("publish",2,"fictional-conclusion-publish");
        var row=data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow();
        var target=FrozenReferenceClosure.version(new com.lianxibu.site.domain.protocol.ContentVersionKey(row.kind(),row.id(),row.bodyRevision(),java.util.HexFormat.of().formatHex(row.bodyHashV1()),row.inputJobId()));
        data.f.seed("withdraw-dependent",java.util.List.of(target));data.f.review("agent","fictional-agent-withdraw-dependent","approve","fictional-dependent-approve");data.f.review("agent","fictional-agent-withdraw-dependent","publish","fictional-dependent-publish");
        assertTrue(qualifiedReads().published("agent","fictional-agent-withdraw-dependent").isPresent());
        data.f.fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_withdraw_queue_failure CHECK(task_type IS NULL OR task_type<>'validity_propagation')");
        assertEquals(503,withdraw(3,"fictional-conclusion-withdraw","conclusion_error").statusCode());
        assertEquals("published",data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow().state());assertTrue(data.f.base.ledger.validity(target).effectiveValid());
        data.f.fixture.jdbc.execute("ALTER TABLE jobs DROP CONSTRAINT fictional_withdraw_queue_failure");
        var response=withdraw(3,"fictional-conclusion-withdraw","conclusion_error");assertEquals(200,response.statusCode(),response.body());
        assertFalse(data.f.base.ledger.validity(target).effectiveValid());assertEquals(1,data.f.base.ledger.validity(target).revision());
        assertFalse(qualifiedReads().published("agent","fictional-agent-withdraw-dependent").isPresent());
        assertEquals(1,data.f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE task_type='validity_propagation'",Integer.class));
        assertEquals(200,withdraw(3,"fictional-conclusion-withdraw","conclusion_error").statusCode());assertEquals(1,data.f.base.ledger.validity(target).revision());
    }
    HttpResponse<String> versionAction(tools.jackson.databind.JsonNode target,long revision,String action,String reverse,String key) throws Exception {
        var command=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();command.set("target",target);command.put("expected_validity_revision",revision);command.put("action",action);command.put("reason","Fictional version decision");if(reverse!=null)command.put("invalidation_id",reverse);
        return request(PATH+"/version-actions","POST",owner,owner.session().csrfToken(),ORIGIN,key,command.toString());
    }
    @Test void oldVersionActionsAreExactAndReversalDoesNotRepublish() throws Exception {
        var row=data.f.base.contents.reviewSnapshot("agent","fictional-public-agent").orElseThrow();
        var target=FrozenReferenceClosure.version(new com.lianxibu.site.domain.protocol.ContentVersionKey(row.kind(),row.id(),row.bodyRevision(),java.util.HexFormat.of().formatHex(row.bodyHashV1()),row.inputJobId()));
        assertEquals(200,edit(1,"fictional-version-edit","Fictional new body","fictional-entity","fictional-citation").statusCode());
        assertEquals(200,action("approve",2,"fictional-new-version-approve").statusCode());assertEquals(200,action("publish",3,"fictional-new-version-publish").statusCode());
        var invalid=versionAction(target,0,"invalidate_conclusion",null,"fictional-old-invalid");assertEquals(200,invalid.statusCode(),invalid.body());
        var result=CanonicalJson.parse(invalid.body()).get("data");String invalidation=result.get("operation_ref").asString();assertEquals(1,result.get("result_revision").asInt());
        assertEquals(200,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        assertEquals(409,versionAction(target,1,"invalidate_conclusion",null,"fictional-old-invalid").statusCode());
        var reversed=versionAction(target,1,"reverse_invalidation",invalidation,"fictional-old-reverse");assertEquals(200,reversed.statusCode(),reversed.body());assertEquals(2,data.f.base.ledger.validity(target).revision());
        assertEquals(result,CanonicalJson.parse(versionAction(target,0,"invalidate_conclusion",null,"fictional-old-invalid").body()).get("data"));
        var unknown=target.deepCopy();((tools.jackson.databind.node.ObjectNode)unknown).put("revision",100);var denied=versionAction(unknown,0,"invalidate_conclusion",null,"fictional-missing-version");assertEquals(422,denied.statusCode(),denied.body());assertTrue(denied.body().contains("unknown_version"));
        String leaf="{\"expected_validity_revision\":0,\"action\":\"invalidate\",\"reason\":\"Fictional leaf decision\"}";
        var evidence=request("/admin/evidence/article_revision/fictional-citation/actions","POST",owner,owner.session().csrfToken(),ORIGIN,"fictional-leaf-invalidate",leaf);assertEquals(200,evidence.statusCode(),evidence.body());
        String leafId=CanonicalJson.parse(evidence.body()).at("/data/operation_ref").asString();
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
        String reverse="{\"expected_validity_revision\":1,\"action\":\"reverse_invalidation\",\"reason\":\"Fictional leaf reversal\",\"invalidation_id\":\""+leafId+"\"}";
        assertEquals(200,request("/admin/evidence/article_revision/fictional-citation/actions","POST",owner,owner.session().csrfToken(),ORIGIN,"fictional-leaf-reverse",reverse).statusCode());
        assertEquals(404,request("/interpretations/fictional-public-agent","GET",null,null,null,null,null).statusCode());
    }
    @Test void versionDirectoryAndActiveDispositionsUseExactRetainedProofs() throws Exception {
        assertEquals(200,edit(1,"fictional-directory-edit","Fictional replacement","fictional-entity","fictional-citation").statusCode());
        var response=request(PATH+"/referenced-versions?page_size=1","GET",owner,null,null,null,null);assertEquals(200,response.statusCode(),response.body());
        var page=CanonicalJson.parse(response.body()).get("data");assertEquals(2,page.get("total").asInt());assertEquals(1,page.get("items").size());
        var first=page.get("items").get(0);assertEquals("body",first.get("version_kind").asString());assertEquals(1,first.at("/target/revision").asInt());
        var target=first.get("target");var invalid=versionAction(target,0,"invalidate_conclusion",null,"fictional-directory-invalid");assertEquals(200,invalid.statusCode());
        String invalidation=CanonicalJson.parse(invalid.body()).at("/data/operation_ref").asString();
        String query="?revision=1&body_hash="+target.get("body_hash").asString()+"&input_job_id="+target.get("input_job_id").asString();
        var details=request(PATH+"/version-dispositions"+query,"GET",owner,null,null,null,null);assertEquals(200,details.statusCode(),details.body());
        var dispositions=CanonicalJson.parse(details.body()).get("data");assertEquals(1,dispositions.get("total").asInt());assertEquals(invalidation,dispositions.at("/items/0/id").asString());
        response=request(PATH+"/referenced-versions","GET",owner,null,null,null,null);page=CanonicalJson.parse(response.body()).get("data");assertEquals(2,page.get("total").asInt());assertFalse(page.at("/items/0/effective_valid").asBoolean());assertTrue(page.at("/items/1/effective_valid").asBoolean());
        assertEquals(200,versionAction(target,1,"reverse_invalidation",invalidation,"fictional-directory-reverse").statusCode());
        details=request(PATH+"/version-dispositions"+query,"GET",owner,null,null,null,null);assertEquals(0,CanonicalJson.parse(details.body()).at("/data/total").asInt());
        assertEquals(401,request(PATH+"/referenced-versions","GET",null,null,null,null,null).statusCode());assertEquals(403,request(PATH+"/version-dispositions"+query,"GET",user,null,null,null,null).statusCode());
        assertFalse(response.body().contains("Fictional replacement"));assertFalse(response.body().contains("Fictional input"));
        data.f.fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='content_review_version'",new byte[32]);
        assertEquals(503,request(PATH+"/referenced-versions","GET",owner,null,null,null,null).statusCode());
    }
}
