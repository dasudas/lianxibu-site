package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual nonempty NEWS original P1/P2 and original article writer compose with native zero
 * catalog cohorts and touched entity counts. All input and model transport data are fictional. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsNativeDailyInputMySqlTest {
 NewsNativeGenerationIntegrationMySqlTest newsFlow;GenerationJdbcStoreTest db;DailyReportInputComponentService service;
 String articleRevision;Instant cutoff;Map<String,CatalogDayAnalysisProofGraph> catalogGraphs;
 @BeforeEach void utc(){newsFlow=new NewsNativeGenerationIntegrationMySqlTest();newsFlow.utc();}
 @AfterEach void restore(){newsFlow.restore();}
 void setup()throws Exception{
  newsFlow.OriginalNonemptyP1P2ClaimsAndApplicationsProduceWholeNativeFinalExactlyOnce();db=newsFlow.db;var day=newsFlow.day;var tx=day.manager;var clock=newsFlow.f.clock;cutoff=day.day.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
  newsFlow.columns("sources","source_kind VARCHAR(16)","name VARCHAR(200)","config_json JSON");String config="{\"url\":\"https://example.invalid/fictional-feed\"}";db.jdbc.update("UPDATE sources SET source_kind='article',name='Fictional source',config_json=?",config);
  for(String table:List.of("article_revisions","articles","contents"))db.jdbc.execute("DROP TABLE IF EXISTS "+table);
  db.jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80) NOT NULL,external_id VARCHAR(255) NOT NULL,external_key BINARY(32) NOT NULL,title VARCHAR(512) NOT NULL,url VARCHAR(2000) NOT NULL,published_at DATETIME(6),summary MEDIUMTEXT,body_text MEDIUMTEXT,facts_json JSON NOT NULL,content_hash BINARY(32),revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL,source_item_id MEDIUMTEXT,source_item_hash BINARY(32),latest_revision_id VARCHAR(64),projection_order_json JSON,UNIQUE(source_id,external_key),UNIQUE(source_id,source_item_hash))");
  db.jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,created_at DATETIME(6),revision BIGINT,article_id VARCHAR(64),revision_no BIGINT,content_hash BINARY(32),title VARCHAR(512),source_summary MEDIUMTEXT,body_text MEDIUMTEXT,source_time DATETIME(6),time_basis VARCHAR(32),content_status VARCHAR(32),body_truncated INT,parser_version VARCHAR(64),config_version BIGINT,qualification_events_json JSON,source_order_json JSON,legacy_flag INT,published_at DATETIME(6),updated_at DATETIME(6),submitted_at DATETIME(6),effective_valid INT DEFAULT 1,validity_revision BIGINT DEFAULT 0,UNIQUE(article_id,revision_no))");
  db.jdbc.execute(ResultJdbcStoreTest.CONTENT_DDL.replace("CLOB","JSON").replace("TIMESTAMP","DATETIME(6)"));newsFlow.columns("source_run_items","article_id VARCHAR(64)","article_revision_id VARCHAR(64)");
  String originalConfig=db.jdbc.queryForObject("SELECT config_json FROM sources WHERE id='fictional-source'",String.class);var mapping=new SourceIdentityMappingService(new JdbcSourceReads(db.source),tx,List.of(new SourceIdentityMappingService.ReviewedMapping("fictional-source","article",SourceIdentityMappingService.sha256(originalConfig),"https://example.invalid/fictional-feed","news_tech")));
  var writer=new ArticleCompatibilityWriter(new JdbcArticleWrites(db.source),mapping,tx,Clock.fixed(clock.instant().minusSeconds(3),ZoneOffset.UTC),List.of());String id=newsFlow.day.original.plan.chunks().getFirst().itemIds().getFirst();var payload=CanonicalJson.parse(db.jdbc.queryForObject("SELECT evidence_json FROM source_run_items WHERE id=?",String.class,id));
  var accepted=writer.accept(new ArticleCompatibilityWriter.Input("fictional-source",db.jdbc.queryForObject("SELECT source_item_id FROM source_run_items WHERE id=?",String.class,id),payload.get("title").asText(),payload.get("source_url").asText(),Instant.parse(payload.get("published_at").asText()),payload.get("source_summary").isNull()?null:payload.get("source_summary").asText(),payload.get("body_text").isNull()?null:payload.get("body_text").asText(),payload.get("time_basis").asText(),payload.get("content_status").asText(),payload.get("body_truncated").asBoolean(),"fictional-parser-v1",payload.get("config_version").asLong(),null));articleRevision=accepted.revisionId();assertTrue(accepted.inserted());assertFalse(accepted.legacyBaselineCreated());
  db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-native-article-association",id,null),key->null,a->{assertEquals(1,db.jdbc.update("UPDATE source_run_items SET article_id=?,article_revision_id=? WHERE id=?",accepted.articleId(),accepted.revisionId(),id));return true;});
  var newsConfig=new NewsDayReportProofConfiguration();var completions=newsConfig.sourceValueReportCompletion(day.graph,tx);var sourceNews=newsConfig.newsSourceReportComponent(db.source,tx,completions);var news=newsConfig.newsDayReportComponent(db.source,tx,day.cohorts,day.barriers,new SourceDayRunClaimAdmissionConfiguration().create(db.source,tx,new SourceDayCollectionAdmissionService(day.cohorts)),day.graph,day.permits,completions,sourceNews);
  catalogGraphs=new LinkedHashMap<>();for(String category:CatalogSourceProofConfiguration.CATEGORIES){var sources=new CatalogSourceProofGraph(db.source,tx,clock,()->new FrozenGenerationSettings(1,List.of(),List.of()),()->true,category);var graph=new CatalogDayAnalysisProofGraph(db.source,tx,clock,day.cohorts,day.runs,sources,()->new FrozenGenerationSettings(1,List.of(),List.of()),()->true,category);catalogGraphs.put(category,graph);assertNotNull(graph.barrier().close(day.day));}
  var catalogComponents=CatalogDayReportProofConfiguration.assemble(db.source,tx,clock,new CatalogDayAnalysisProofConfiguration.Graphs(catalogGraphs));var catalog=new CatalogDayReportAggregateService(catalogComponents.byCategory(),tx);
  var events=new JdbcEntityHeatEvents(db.source);var proof=new EntityHeatEventProofService(events,tx);var first=new EntityHeatEventAvailabilityService(proof,new JdbcEntityHeatAvailability(db.source,events),db.writes,tx,Clock.fixed(clock.instant().plusNanos(1000),ZoneOffset.UTC));var observations=new EntityHeatEventAvailabilityPoller(new JdbcEntityHeatEventLocators(db.source),first,tx,64).poll();assertEquals(0,observations.failed());assertEquals(1,observations.observed());
  var counts=new EntityHeatCutoffProofService(new JdbcEntityHeatCutoffUniverse(db.source),proof,first,tx);var origin=new EntityHeatSourceOriginService(new JdbcEntityHeatOriginHeaders(db.source),proof,news,day.cohorts,day.graph.plans(),new SourceDedupResultProofService(new JdbcSourceDedupResults(db.source),day.graph.plans(),day.graph.universes()),tx);var heat=new EntityHeatReportComponentService(counts,origin,new JdbcEntityHeatOriginHeaders(db.source),tx);service=new DailyReportInputComponentService(news,catalog,heat,tx);
 }
 <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(newsFlow.day.manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
 DailyReportInputComponentService.Captured capture(){return service.capture(newsFlow.day.day,cutoff);}
 @Test void ActualNonemptyOriginalNewsAndNativeCountsJoinAllFiveDailyCohorts()throws Exception{
  setup();var captured=capture();assertNotNull(captured);assertTrue(captured.sourceProof().complete());assertTrue(captured.sourceProof().missingInputs().isEmpty());assertFalse(captured.sourceProof().candidates().isEmpty());assertTrue(captured.sourceProof().noUpdateDates().isEmpty());assertFalse(captured.referenceTargetsByEvidence().isEmpty());assertTrue(captured.referenceTargetsByEvidence().values().stream().flatMap(Collection::stream).anyMatch(value->articleRevision.equals(CanonicalJson.parse(value).path("id").asText())));assertSame(captured,rr(()->service.verifyObserved(captured)));assertEquals(2,newsFlow.sends);assertEquals(2,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("free",new JdbcWorkerState(db.source).slot().state());
 }
 @Test void ActualOriginalLeafInvalidationRejectsCapturedDailyProofWithoutAnotherSend()throws Exception{
  setup();var captured=capture();assertNotNull(captured);int rows=db.count("1=1");assertEquals(1,db.jdbc.update("UPDATE article_revisions SET effective_valid=0,validity_revision=validity_revision+1 WHERE id=?",articleRevision));assertThrows(RuntimeException.class,()->rr(()->service.verifyObserved(captured)));assertEquals(rows,db.count("1=1"));assertEquals(2,newsFlow.sends);assertEquals(1L,db.jdbc.queryForObject("SELECT SUM(occurrence_count) FROM analyzed_entities",Long.class));
 }
}
