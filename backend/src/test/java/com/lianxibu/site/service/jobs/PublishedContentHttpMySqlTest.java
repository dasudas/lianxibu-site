package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.controller.content.ContentController;
import com.lianxibu.site.controller.jobs.PublicReadController;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.content.ContentService.*;
import com.lianxibu.site.service.learning.LearningService;
import com.lianxibu.site.service.settings.SettingsReadService;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.JsonNode;

/** Real TCP and permanent MySQL qualification; display metadata is explicitly fictional. */
@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class PublishedContentHttpMySqlTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import({ContentController.class,PublicReadController.class})
    static class HttpFixture {
        @Bean SecurityFilterChain fictionalPublicReads(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a->a.requestMatchers("/reports/**","/reports","/interpretations/**","/interpretations","/home").permitAll()
                    .anyRequest().denyAll()).build();
        }
    }
    @Test void realHttpRechecksQualificationBeforeDetailsListsHomeAndConditionalResponses() throws Exception {
        try(var f=new PooledValidityMySqlFixture()) {
            f.setup();f.publish();
            for(var kind:List.of("agent","model","skill","workflow")) {
                f.seed("http-"+kind,GenerationFlowService.Stage.valueOf(kind.toUpperCase(Locale.ROOT)+"_ANALYSIS"),"fictional-http-"+kind,List.of(f.base.article()));
                f.review(kind,"fictional-http-"+kind,"approve","fictional-http-approve-"+kind);
                f.review(kind,"fictional-http-"+kind,"publish","fictional-http-publish-"+kind);
            }
            var reads=new ContentReadService(new JdbcContentReads(f.fixture.source),f.fixture.tx,
                    new ReviewPublicationService(f.fixture.writes,new ReviewPublicationJdbcStore(null,
                            f.base.writer,f.base.contents,f.audit,f.base.versions,f.base.ledger)));
            var content=new PublishedContentFacade(reads,PublishedContentHttpMySqlTest::fictionalMetadata,f.fixture.tx);
            var learning=mock(LearningService.class);when(learning.packages(1,50)).thenReturn(new LearningService.Page<>(List.of(),1,50,0));
            var settings=mock(SettingsReadService.class);when(settings.resolvePublishedPackage(null)).thenReturn(Optional.empty());
            var home=new PublicHomeReadService(content,learning,settings,f.fixture.tx);
            try(var app=new SpringApplicationBuilder(HttpFixture.class)
                    .properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off",
                            "logging.level.root=ERROR")
                    .initializers(context->{var registry=(GenericApplicationContext)context;
                        registry.registerBean(ContentService.class,()->content);registry.registerBean(PublicReadService.class,()->home);})
                    .run();var client=HttpClient.newHttpClient()) {
                String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
                var first=get(client,root,"/reports/fictional-report");assertEquals(200,first.statusCode(),first.body());
                assertEquals("Fictional report",CanonicalJson.parse(first.body()).get("data").get("title").asString());
                visible(client,root,true);
                var invalid=f.invalidate(f.base.article(),0,"fictional-http-invalidate");
                // Before asynchronous display propagation, the immutable dependency vector already excludes it.
                assertEquals("published",f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().state());
                visible(client,root,false);
                f.service.change(f.base.command(f.base.article(),1,VersionValidityService.Action.REVERSE_INVALIDATION,invalid.dispositionId()),f.fixture.action("fictional-http-reverse"));
                visible(client,root,false);
                f.review("report","fictional-report","approve","fictional-http-reapprove");
                f.review("report","fictional-report","publish","fictional-http-republish");
                for(var kind:List.of("agent","model","skill","workflow")) {
                    f.review(kind,"fictional-http-"+kind,"approve","fictional-http-reapprove-"+kind);
                    f.review(kind,"fictional-http-"+kind,"publish","fictional-http-republish-"+kind);
                }
                visible(client,root,true);
                f.drain("fictional-http-late-");visible(client,root,true);
                // A missing original owner is never repaired from the visible body.
                f.fixture.jdbc.update("UPDATE contents SET input_job_id=NULL");
                visible(client,root,false);
            }
        }
    }
    static HttpResponse<String> get(HttpClient client,String root,String path) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(root+path))
                .header("If-None-Match","\"fictional-previous-body\"").header("If-Modified-Since","Thu, 01 Jan 2026 00:00:00 GMT")
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertNotEquals(304,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());return response;
    }
    static void visible(HttpClient client,String root,boolean expected) throws Exception {
        var detail=get(client,root,"/reports/fictional-report");assertEquals(expected?200:404,detail.statusCode(),detail.body());
        var list=get(client,root,"/reports?type=daily&page=1&page_size=1");assertEquals(200,list.statusCode(),list.body());
        var data=CanonicalJson.parse(list.body()).get("data");assertEquals(expected?1:0,data.get("total").asInt());assertEquals(expected?1:0,data.get("items").size());
        var home=get(client,root,"/home");assertEquals(200,home.statusCode(),home.body());assertEquals(expected?1:0,CanonicalJson.parse(home.body()).get("data").get("reports").size());
        for(var kind:List.of("agent","model","skill","workflow")) {
            var interpretation=get(client,root,"/interpretations/fictional-http-"+kind);assertEquals(expected?200:404,interpretation.statusCode(),interpretation.body());
            var typed=get(client,root,"/interpretations?kind="+kind);assertEquals(200,typed.statusCode(),typed.body());
            assertEquals(expected?1:0,CanonicalJson.parse(typed.body()).get("data").get("total").asInt());
        }
        var all=get(client,root,"/interpretations");assertEquals(200,all.statusCode(),all.body());
        assertEquals(expected?4:0,CanonicalJson.parse(all.body()).get("data").get("total").asInt());
        if(!expected){assertFalse(detail.body().contains("Fictional text"));assertFalse(list.body().contains("Fictional report"));assertFalse(home.body().contains("Fictional report"));}
    }
    static ContentView fictionalMetadata(PublishedContentFacade.Version v) {
        var body=CanonicalJson.parse(v.bodyJson());var sections=new ArrayList<Section>();
        for(var section:body.get("sections"))sections.add(new Section(section.get("heading").asString(),section.get("text").asString(),strings(section.get("citation_ids"))));
        ContentBody projected="report".equals(v.kind())?new ReportBody(body.get("title").asString(),sections,strings(body.get("citation_ids")),body.get("type").asString(),
                Instant.parse(body.get("period_start").asString()),Instant.parse(body.get("period_end").asString()),body.get("timezone").asString(),null)
                :new InterpretationBody(body.get("title").asString(),sections,strings(body.get("citation_ids")),body.get("category").asString(),body.get("entity_id").asString());
        return new ContentView(v.id(),v.kind(),v.title(),v.revision(),v.bodyRevision(),"published",v.publishedAt(),v.inputOwner(),1L,
                Instant.parse("2026-01-01T20:00:00Z"),new Coverage("complete",List.of(),List.of(),List.of()),false,List.of(),false,null,
                "report".equals(v.kind())?null:new InterpretationProfile("Fictional","Fictional",List.of(),"Fictional",Instant.parse("2026-01-01T20:00:00Z"),List.of(),null,null),
                projected,List.of(new Citation("fictional-citation","article_revision","Fictional citation","https://example.invalid/fictional",1L,"a".repeat(64))));
    }
    static List<String> strings(JsonNode value){var result=new ArrayList<String>();value.forEach(v->result.add(v.asString()));return List.copyOf(result);}
}
