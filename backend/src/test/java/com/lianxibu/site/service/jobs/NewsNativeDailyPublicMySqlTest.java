package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.controller.content.ContentController;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

/** Actual nonempty collector/P1/P2/final/daily/claim/send/application/review/public HTTP.
 * All inputs and three Sender responses are fictional. No public qualification is seeded. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsNativeDailyPublicMySqlTest {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 @EnableAutoConfiguration(exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
 @Import({ContentController.class,PublicContentRuntimeConfiguration.class,BootstrapSecurityConfiguration.class})
 static class App{}
 NewsNativeDailyInputMySqlTest h;GenerationJdbcStoreTest db;ReportPreparedInputService originals;
 ReportPreparedInputService.Prepared prepared;ReportPreparationService preparation;ReportDispatchPermitService permits;
 WorkerAuthorizationService worker;Clock clock;ResultApplicationFactory factory;int reportSends;
 @BeforeEach void utc(){h=new NewsNativeDailyInputMySqlTest();h.utc();}
 @AfterEach void restore(){h.restore();}
 void setup()throws Exception{
  h.setup();db=h.db;var tx=h.newsFlow.day.manager;clock=Clock.fixed(h.cutoff.plusSeconds(1),ZoneOffset.UTC);
  var capture=new ReportInputCaptureService(h.service,mock(ReportPeriodInputComponentService.class),tx);var admission=new ReportInputWriterAdmissionService(db.source,tx,capture);originals=new ReportPreparedInputService(db.source,tx);var members=h.newsFlow.f.p1.members;
  var metadata=new ReportPublicMetadataEnricher(new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(db.source),members),members,new FrozenContentVersionService(new JdbcFrozenContentVersions(db.source)));
  preparation=new ReportPreparationService(db.source,originals,admission,db.writes,()->new FrozenGenerationSettings(1,List.of(),List.of()),metadata,new GenerationRequestBuilder(),clock,()->true);var scope=new ReportInputCaptureService.Scope("report:daily:"+h.newsFlow.day.day,1,GenerationFlowService.Stage.DAILY_REPORT,h.newsFlow.day.day);prepared=preparation.prepare(scope);assertEquals("complete",prepared.input().coverageState());assertEquals("queued",prepared.input().state());
  var noUpdates=new ReportNoUpdateProofService(db.source,originals,db.writes,tx,clock);permits=new ReportDispatchPermitService(db.source,originals,noUpdates,tx);
  var input=new JdbcFrozenInputs(db.source);var contents=new ContentReadService(new JdbcContentReads(db.source),tx,ContentReadService.denyUnverifiedReferences());var readiness=new DispatchReadinessService(new JdbcDispatchInputs(db.source),input,new FrozenDependencyIndexService(input,new JdbcFrozenDependencies(db.source)),new GenerationLineageService(new JdbcGenerationLineage(db.source),clock,"fictional-native-public"),contents,members,ReportDispatchNativeReferenceFixture.create(db.source,tx),null,null,false,null,null,tx,null,null,permits);
  worker=new WorkerAuthorizationService(db.writes,new WorkerJdbcStore(new JdbcWorkerState(db.source),new JdbcJobRecords(db.source),"fictional-native-public",()->false,readiness,clock,tx),clock,()->true);
  var catalogPermits=new LinkedHashMap<String,CatalogDayAnalysisResultPermitService>();h.catalogGraphs.forEach((category,graph)->catalogPermits.put(category,graph.result()));var graph=h.newsFlow.day.graph;factory=new ResultApplicationFactory(db.source,tx,clock,graph.dependencies(),graph.dedupPermits(),catalogPermits,new JdbcEntityHeatSnapshots(db.source),permits);reportSends=0;
 }
 ResultApplicationService.Result generate(){
  var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-native-daily-public-claim",prepared.input().ownerId(),prepared.input().inputHash()));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());var identity=claim.permit().identity();assertEquals(prepared.input().ownerId(),identity.rootId());var profile=ReportPreparationInputProfile.complete(CanonicalJson.parse(prepared.input().snapshotJson()));String raw=new ReportNativeGenerationIntegrationTest().raw(profile.command().applicationContext().input(identity.rootId(),identity.attemptId(),identity.inputHash(),new JdbcJobRecords(db.source).byId(identity.rootId()).revision()));
  assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{assertEquals(identity.frozenRequestJson(),request);assertEquals(CanonicalJson.parse(prepared.input().requestJson()),CanonicalJson.parse(request));reportSends++;return raw;}).state());var app=factory.forAttempt(identity);var action=new SharedWriteExecutor.ActionContext("fictional-native-daily-public-result",identity.rootId(),identity.inputHash());var result=app.service().applyAutomatic(app.input(),raw,action);assertEquals(ResultApplicationService.Status.APPLIED,result.status());assertEquals(result,app.service().applyAutomatic(app.input(),raw,action));return result;
 }
 org.springframework.context.ConfigurableApplicationContext start(){return new SpringApplicationBuilder(App.class).properties("server.port=0","server.address=127.0.0.1","spring.main.banner-mode=off","logging.level.root=ERROR").initializers(context->{var registry=(GenericApplicationContext)context;registry.registerBean(javax.sql.DataSource.class,()->db.source);registry.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->h.newsFlow.day.manager);}).run("--site.browser.enabled=true","--site.content.enabled=true");}
 HttpResponse<String> get(HttpClient client,String root,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());}
 @Test void FullActualNonemptyNewsGeneratesReviewedDailyAndQualifiesOnlyExactPublicHttpProof()throws Exception{
  setup();var result=generate();String id=result.contentId();assertNotNull(id);assertNotNull(h.rr(()->new JdbcReportCompletionReceipts(db.source).read(prepared.input().ownerId())));assertEquals("free",new JdbcWorkerState(db.source).slot().state());assertEquals(1,reportSends);assertEquals(2,h.newsFlow.sends);assertEquals(3,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
  try(var app=start();var client=HttpClient.newHttpClient()){
   String root="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();assertEquals(404,get(client,root,"/reports/"+id).statusCode());var content=app.getBean(ContentService.class);var actor=new Actor("fictional-owner","owner");long revision=content.adminContent(actor,"report",id).revision();
   content.action(actor,"report",id,new ContentService.ContentAction(revision,"approve","Fictional original review",null),"fictional-native-daily-approve","fictional-csrf","https://example.invalid");revision=content.adminContent(actor,"report",id).revision();content.action(actor,"report",id,new ContentService.ContentAction(revision,"publish","Fictional original publication",null),"fictional-native-daily-publish","fictional-csrf","https://example.invalid");
   var response=get(client,root,"/reports/"+id);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());var data=CanonicalJson.parse(response.body()).get("data");assertEquals("complete",data.get("coverage").get("state").asText());assertFalse(data.get("citations").isEmpty());assertTrue(data.toString().contains("https://example.invalid/story/0000"));assertFalse(data.toString().contains("frozenRequestJson"));assertEquals(1,CanonicalJson.parse(get(client,root,"/reports?type=daily").body()).get("data").get("total").asInt());
   var invalidation=content.evidenceAction(actor,"article_revision",h.articleRevision,new ContentService.EvidenceAction(0,"invalidate","Fictional original source correction",null),"fictional-native-daily-leaf-invalidate","fictional-csrf","https://example.invalid");assertNotNull(invalidation.operationRef());assertEquals(1,invalidation.resultRevision());assertEquals(404,get(client,root,"/reports/"+id).statusCode());assertEquals(0,CanonicalJson.parse(get(client,root,"/reports?type=daily").body()).get("data").get("total").asInt());assertEquals(1,reportSends);assertEquals(3,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
  }
 }
 @Test void LostOriginalLeafBeforeClaimCannotSpendDailyBudgetOrReachSender()throws Exception{
  setup();try(var app=start()){var content=app.getBean(ContentService.class);var invalidation=content.evidenceAction(new Actor("fictional-owner","owner"),"article_revision",h.articleRevision,new ContentService.EvidenceAction(0,"invalidate","Fictional invalid source before claim",null),"fictional-native-preclaim-leaf-invalidate","fictional-csrf","https://example.invalid");assertNotNull(invalidation.operationRef());assertEquals(1,invalidation.resultRevision());var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-invalid-daily-claim",prepared.input().ownerId(),prepared.input().inputHash()));assertEquals(WorkerAuthorizationService.State.IDLE,claim.state());assertEquals(0,new JdbcJobRecords(db.source).byId(prepared.input().ownerId()).callCount());assertEquals(0,reportSends);assertEquals(2,h.newsFlow.sends);assertEquals("free",new JdbcWorkerState(db.source).slot().state());assertEquals(0L,db.jdbc.queryForObject("SELECT COUNT(*) FROM contents WHERE kind='report'",Long.class));}
 }
}
