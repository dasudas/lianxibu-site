package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.content.ContentController;
import com.lianxibu.site.controller.jobs.PublicReadController;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.content.JdbcGeneratedContents;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.node.ObjectNode;

class PublicContentRuntimeTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import({ContentController.class,PublicReadController.class,PublicContentRuntimeConfiguration.class,PublicHomeRuntimeConfiguration.class,LearningRuntimeConfiguration.class,BootstrapSecurityConfiguration.class})
    static class App { }
    VersionValidityJdbcStoreTest f;
    VersionValidityJdbcStoreTest fixture(){var value=new VersionValidityJdbcStoreTest();value.setup();return value;}
    @org.junit.jupiter.api.AfterEach void closeFixture() throws Exception {if(f instanceof AutoCloseable closeable)closeable.close();}
    void noUpdateReport() {
        String id="fictional-no-update",owner="fictional-no-update-input";
        // This fixture's earlier report owns the same natural period; replace only its local row.
        f.fixture.jdbc.update("DELETE FROM contents WHERE kind='report' AND id='fictional-report'");
        var node=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.DAILY_REPORT));
        ((ObjectNode)node.get("target_content")).put("id",id).putNull("base_revision");
        for(String field:List.of("item_ids","candidates_by_item","eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))node.putArray(field);
        var context=FrozenApplicationContext.fromJson(node);
        var command=new Prepare("fictional-no-update-key",1,Stage.DAILY_REPORT,LocalDate.of(2026,1,1),null,List.of(),List.of(),List.of(),false,"a".repeat(64),context);
        var source=new ReportInputSnapshotService((c,cutoff)->new ReportInputSnapshotService.SourceProof(cutoff,LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),true,List.of(),List.of(),List.of(LocalDate.of(2026,1,1)),List.of()));
        RequestBuilder request=(s,snapshot,hash)->"{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional no update\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}";
        var store=new GenerationJdbcStore(f.fixture.source,f.fixture.clock,"fictional-owner",owner,command);
        new GenerationFlowService(f.fixture.writes,store,request,f.fixture.clock,()->true,source,(r,c,cutoff,snapshot)->tools.jackson.databind.json.JsonMapper.shared().createObjectNode().set(FrozenPublicMetadata.FIELD,CanonicalJson.parse("{\"schema_version\":\"frozen-public-metadata-v1\",\"citations\":[],\"interpretation\":null}")))
            .prepare(command,new SharedWriteExecutor.ActionContext("fictional-no-update-freeze",owner,"a".repeat(64)));
        String body="{\"title\":\"Fictional no update\",\"sections\":[],\"citation_ids\":[],\"type\":\"daily\",\"period_start\":\"2025-12-31T16:00:00.000000Z\",\"period_end\":\"2026-01-01T16:00:00.000000Z\",\"timezone\":\"Asia/Shanghai\",\"learning_task\":null}";
        f.fixture.writes.execute(f.fixture.action("fictional-no-update-body"),k->null,a->{new GeneratedContentService(new JdbcGeneratedContents(f.fixture.source),f.fixture.clock).write(new GeneratedContentService.Write("report",id,null,owner,body));var row=f.base.contents.reviewSnapshot("report",id).orElseThrow();f.audit.archiveCurrent(new ContentVersionKey("report",id,row.bodyRevision(),HexFormat.of().formatHex(row.bodyHashV1()),owner));return true;});
        f.review("report",id,"approve","fictional-no-update-approve");f.review("report",id,"publish","fictional-no-update-publish");
    }
    void seed(String kind,boolean metadata) {
        seed(kind,metadata,true);
    }
    void seed(String kind,boolean metadata,boolean publish) {
        var fixture=f.fixture;String id="fictional-public-"+kind,owner="fictional-input-"+kind;
        var stage=Stage.valueOf(kind.toUpperCase(Locale.ROOT)+"_ANALYSIS");
        var node=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(stage));
        ((ObjectNode)node.get("target_content")).put("id",id);node.put("target_entity_id","fictional-entity");node.putArray("reference_targets").add(f.base.article());
        var context=FrozenApplicationContext.fromJson(node);
        var evidence=new Evidence("fictional-material","analysis",LocalDate.of(2026,1,1),Instant.parse("2026-01-01T20:00:00Z"),"a".repeat(64),"{\"text\":\"Fictional input\"}",true,false,"accepted",List.of());
        var command=new Prepare("fictional-public-key-"+kind,1,stage,LocalDate.of(2026,1,1),null,List.of(),List.of(evidence),List.of(),true,"a".repeat(64),context);
        RequestBuilder requests=(s,snapshot,hash)->"{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional input\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}";
        var store=new GenerationJdbcStore(fixture.source,fixture.clock,"fictional-owner",owner,command);
        InputEnricher enrich=(root,c,cutoff,snapshot)->tools.jackson.databind.json.JsonMapper.shared().createObjectNode().set(FrozenPublicMetadata.FIELD,CanonicalJson.parse("""
            {"schema_version":"frozen-public-metadata-v1","citations":[{"id":"fictional-citation","target":{"kind":"article_revision","id":"fictional-citation"},"title":"Fictional source","source_url":"https://example.invalid/source"}],"interpretation":{"name":"Fictional name","provider":null,"tags":[],"description":null,"evidence_time":null,"metrics":[],"stars":null,"stars_growth":null}}
            """));
        var generation=metadata?new GenerationFlowService(fixture.writes,store,requests,fixture.clock,()->true,null,enrich):new GenerationFlowService(fixture.writes,store,requests,fixture.clock,()->true);
        generation.prepare(command,new SharedWriteExecutor.ActionContext("fictional-freeze-"+kind,owner,"a".repeat(64)));
        String body="{\"title\":\"Fictional public text\",\"sections\":[],\"citation_ids\":[\"fictional-citation\"],\"category\":\""+kind+"\",\"entity_id\":\"fictional-entity\"}";
        fixture.writes.execute(fixture.action("fictional-body-"+kind),k->null,a->{new GeneratedContentService(new JdbcGeneratedContents(fixture.source),fixture.clock).write(new GeneratedContentService.Write(kind,id,null,owner,body));var row=f.base.contents.reviewSnapshot(kind,id).orElseThrow();f.audit.archiveCurrent(new ContentVersionKey(kind,id,row.bodyRevision(),HexFormat.of().formatHex(row.bodyHashV1()),owner));return true;});
        if(publish){f.review(kind,id,"approve","fictional-approve-"+kind);f.review(kind,id,"publish","fictional-publish-"+kind);}
    }
    org.springframework.context.ConfigurableApplicationContext start(boolean enabled) {
        return new SpringApplicationBuilder(App.class).properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","logging.level.root=ERROR","site.browser.enabled=true","site.content.enabled="+enabled)
            .initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(javax.sql.DataSource.class,()->f.fixture.source);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.fixture.tx);}).run("--site.browser.enabled=true","--site.content.enabled="+enabled,"--site.learning.enabled=true","--SITE_LEARNING_IMPORT_DIRECTORY="+java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"fictional-home-import-"+UUID.randomUUID()).toAbsolutePath());
    }
    static HttpResponse<String> request(HttpClient client,String root,String path,String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualProofsReadAnonymouslyAndInvalidationImmediatelyRemovesAllKinds() throws Exception {
        f=fixture();noUpdateReport();for(String kind:List.of("agent","model","skill","workflow"))seed(kind,true);
        try(var app=start(true);var client=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            for(String kind:List.of("agent","model","skill","workflow")) {
                var detail=request(client,root,"/interpretations/fictional-public-"+kind,"GET");assertEquals(200,detail.statusCode(),detail.body());assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElseThrow());assertTrue(detail.body().contains("Fictional name"));assertFalse(detail.body().contains("Fictional input"));
            }
            var list=request(client,root,"/interpretations","GET");assertEquals(4,CanonicalJson.parse(list.body()).get("data").get("total").asInt());
            assertEquals(404,request(client,root,"/reports/fictional-report","GET").statusCode());
            var report=request(client,root,"/reports/fictional-no-update","GET");assertEquals(200,report.statusCode(),report.body());assertEquals("2026-01-01",CanonicalJson.parse(report.body()).get("data").get("coverage").get("no_update_dates").get(0).asString());
            var reports=request(client,root,"/reports?type=daily","GET");assertEquals(1,CanonicalJson.parse(reports.body()).get("data").get("total").asInt());
            assertEquals(403,request(client,root,"/interpretations/fictional-public-agent","POST").statusCode());
            assertEquals(401,request(client,root,"/admin/content/agent/fictional-public-agent","GET").statusCode());
            f.invalidate(f.base.article(),0,"fictional-runtime-invalidate");
            assertEquals(404,request(client,root,"/interpretations/fictional-public-agent","GET").statusCode());
            list=request(client,root,"/interpretations","GET");assertEquals(0,CanonicalJson.parse(list.body()).get("data").get("total").asInt());assertFalse(list.body().contains("Fictional public text"));
        }
    }
    @Test void missingMetadataFailsClosedAndDisabledConfigurationDeniesRoutes() throws Exception {
        f=fixture();seed("agent",false);
        try(var app=start(true);var client=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var detail=request(client,root,"/interpretations/fictional-public-agent","GET");assertEquals(503,detail.statusCode(),detail.body());assertFalse(detail.body().contains("Fictional public text"));assertEquals(503,request(client,root,"/interpretations","GET").statusCode());
        }
        try(var app=start(false);var client=HttpClient.newHttpClient()) {
            assertTrue(app.getBeansOfType(ContentService.class).isEmpty());String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();assertEquals(403,request(client,root,"/interpretations","GET").statusCode());
        }
    }
    @Test void homeUsesCurrentPublishedPackagesAndStorageFailureIsUnavailable() throws Exception {
        f=fixture();var jdbc=f.fixture.jdbc;
        for(String table:List.of("learning_packages","runtime_settings","request_receipts"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json TEXT,revision BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE request_receipts(operation VARCHAR(64),scope VARCHAR(128),revision BIGINT,response_json TEXT)");
        jdbc.update("INSERT INTO learning_packages VALUES('fictional-package','Fictional learning','published',1),('fictional-draft','Private draft','draft',1)");
        jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,1,CURRENT_TIMESTAMP)","{\"default_package_id\":\"fictional-package\"}");
        try(var app=start(true);var client=HttpClient.newHttpClient()) {
            String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
            var response=request(client,root,"/home","GET");assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            var data=CanonicalJson.parse(response.body()).get("data");assertEquals(1,data.get("learning_packages").size());assertEquals("fictional-package",data.get("default_package_id").asString());assertTrue(data.get("reports").isEmpty());assertFalse(response.body().contains("Private draft"));
            assertEquals(200,request(client,root,"/capabilities","GET").statusCode());assertEquals(403,request(client,root,"/home","POST").statusCode());
            jdbc.update("UPDATE learning_packages SET state='draft',revision=2 WHERE id='fictional-package'");
            response=request(client,root,"/home","GET");assertEquals(200,response.statusCode());data=CanonicalJson.parse(response.body()).get("data");assertTrue(data.get("learning_packages").isEmpty());assertTrue(data.get("default_package_id").isNull());
            jdbc.execute("DROP TABLE runtime_settings");response=request(client,root,"/home","GET");assertEquals(503,response.statusCode(),response.body());assertTrue(response.body().contains("temporarily_unavailable"));assertFalse(response.body().contains("SELECT"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
