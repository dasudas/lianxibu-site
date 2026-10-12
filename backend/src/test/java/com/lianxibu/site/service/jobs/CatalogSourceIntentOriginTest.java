package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.sources.*;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Actual original collection dispositions supply immutable catalog routes only. */
class CatalogSourceIntentOriginTest {
 CatalogSourcePlanPreparationTest fixture;GenerationJdbcStoreTest f;JdbcCatalogSourceIntentScan storage;CatalogSourceIntentOriginService service;JdbcSourceIntentScan.Candidate candidate;String receipt;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category)throws Exception {
  fixture=new CatalogSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return CatalogSourceIntentOriginTest.this.database();}@Override void setup()throws Exception {
        f=fixture();f.clock=Clock.fixed(f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),ZoneOffset.UTC);f.fixture();var sourceFixture=new SourceRunUniverseServiceTest(){@Override protected javax.sql.DataSource database(){return f.source;}void initialize(){setup();}};sourceFixture.initialize();
        String json=f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.jdbc.execute("ALTER TABLE source_runs ADD finished_at TIMESTAMP(6)");
        for(String column:List.of("category VARCHAR(16)","entity_id VARCHAR(64)","state VARCHAR(32)","projection_decision VARCHAR(16)","effective_valid INT","revision BIGINT","target_business_date DATE","created_at TIMESTAMP(6)","evidence_json "+json,"evidence_hash BINARY(32)"))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        f.jdbc.update("UPDATE sources SET category=?",category);
        var payload=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","1").put("category",category).put("title","Fictional agent release").put("source_url","https://example.invalid/fictional-agent");
        payload.putObject("identity").put("namespace",CatalogSourcePlanBuilderTest.namespace(category)).put("key","fictional-agent");payload.putNull("version").putNull("published_at").putNull("updated_at");payload.put("summary","Fictional source summary").put("document_text","Fictional source document").put("truncated",false).put("parser_version","fictional-parser-v1").put("config_version",1);
        payload.putArray("evidence").addObject().put("url","https://example.invalid/fictional-agent").putNull("revision").putNull("path").putNull("file_hash").put("evidence_type","metadata");String body=JdbcFrozenInputs.canonical(payload);
        f.jdbc.update("UPDATE source_runs SET finished_at=?,config_snapshot_json='{\"parser_version\":\"fictional-parser-v1\"}'",java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)));
        f.jdbc.update("UPDATE source_run_items SET category=?,state=CASE WHEN terminal_state='accepted' THEN 'pending' ELSE 'excluded' END,projection_decision='initialized',effective_valid=1,revision=2,created_at=?,evidence_json=?,evidence_hash=?",category,java.sql.Timestamp.from(f.clock.instant().minusSeconds(2)),body,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));
        f.jdbc.execute("DROP TABLE IF EXISTS analyzed_entities");f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),title VARCHAR(512),summary TEXT,revision BIGINT,first_recorded_at TIMESTAMP(6),analysis_state VARCHAR(32),legacy_flag INT)");
        var sourceHandoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);
        tx(()->{sourceHandoff.terminal("fictional-run");return true;});collections=new CollectionHandoffProofService(f.source,sourceHandoff);collection=tx(()->collections.read("fictional-run"));
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),new SourceRunUniverseService(new JdbcSourceRunUniverse(f.source),sourceHandoff),f.clock);
        plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source));
        eligibility=new CatalogDedupEligibilityService(new JdbcNewsDedupEligibility(f.source),members);history=new CatalogCandidateHistoryService(f.source,category,f.clock);

  }};fixture.setup();f=fixture.f;storage=new JdbcCatalogSourceIntentScan(f.source,category);service=new CatalogSourceIntentOriginService(storage,category);candidate=readonly(()->storage.page(null,50)).candidates().getFirst();receipt=fixture.collection.dispositionId();
 }
 @BeforeEach void setup()throws Exception{install("agent");}
 <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
 CatalogSourceIntentOriginService.Route read(){return readonly(()->service.read(candidate));}
 void reject(){assertThrows(RuntimeException.class,this::read);}
 void body(String body){f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",body,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(body))),receipt);}
 @Test void fourOriginalCategoriesSupplyOnlyExactImmutableProductionRoutes()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category);int rows=f.count("1=1");var route=read();assertEquals(category,route.category());assertEquals(category+"_dedup",route.type());assertEquals(candidate.id(),route.intentId());assertEquals("fictional-run",route.runId());assertEquals("CatalogSourceIntentOriginRoute[redacted]",route.toString());
   f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");f.jdbc.update("DELETE FROM source_run_items");assertEquals(route,read());assertEquals(rows,f.count("1=1"));assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt') OR target_type='source_stage_plan'"));
  }
 }
 @Test void originalRolesReasonsRawIdentityAndAllNativeHashesCannotBeReplaced(){
  for(String field:List.of("operator_id","reason","record_kind","id","target_type")){String old=f.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",String.class,receipt),changed=field.equals("id")?old.toUpperCase(Locale.ROOT):"fictional-forged";f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",changed,receipt);reject();f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",old,field.equals("id")?changed:receipt);}
  for(String field:List.of("action_key_hash","target_key_hash","action_payload_hash")){byte[] old=f.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",byte[].class,receipt);f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",new byte[32],receipt);reject();f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",old,receipt);}assertNotNull(read());
 }
 @Test void rehashedClosedBodyWrongCategoryGenerationDayActivationAndCoercionRefuse(){
  String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,receipt);for(int n=0;n<10;n++){var node=(ObjectNode)CanonicalJson.parse(original);var facts=(ObjectNode)node.get("facts");switch(n){case 0->node.put("fictional_extra",true);case 1->facts.put("fictional_extra",true);case 2->facts.put("generation","1");case 3->facts.put("sourceId",42);case 4->facts.put("category","skill");case 5->facts.put("generation",2);case 6->facts.put("businessDate","2026-01-02");case 7->facts.put("activationStage","trial");case 8->facts.put("runKind","trial");case 9->node.put("intent_id",candidate.id().toUpperCase(Locale.ROOT));}body(JdbcFrozenInputs.canonical(node));reject();body(original);}assertNotNull(read());
 }
 @Test void nativeMissingDuplicateAndWideOriginsNeverInventPreparation(){
  String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,receipt);var padded=(ObjectNode)CanonicalJson.parse(original);padded.put("fictional_padding","x".repeat(8193));body(JdbcFrozenInputs.canonical(padded));reject();body(original);
  var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",candidate.id());fixture.tx(()->new JdbcJobRecords(f.source).appendDisposition(new JdbcJobRecords.Disposition("fictional-duplicate-catalog-origin","fictional-duplicate-catalog-origin-action","collection_handoff",JdbcFrozenInputs.canonical(target),"collection-worker","Terminal collection handed to analysis",original),f.clock.instant()));reject();f.jdbc.update("DELETE FROM jobs WHERE id='fictional-duplicate-catalog-origin'");assertNotNull(read());f.jdbc.update("DELETE FROM jobs WHERE id=?",receipt);reject();assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt')"));
 }
 @Test void fabricatedCandidateWrongTargetAndWriterReadsRefuseWithoutMutation(){
  var original=candidate;candidate=new JdbcSourceIntentScan.Candidate(original.id(),original.key(),new byte[32],original.generation(),original.type(),original.day(),original.state(),original.revision());reject();candidate=new JdbcSourceIntentScan.Candidate(original.id(),original.key(),original.keyHash(),original.generation(),original.type(),original.day().plusDays(1),original.state(),original.revision());reject();candidate=original;
  var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",candidate.id());String raw=JdbcFrozenInputs.canonical(target);f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),receipt);reject();target.put("kind","generation_intent");raw=JdbcFrozenInputs.canonical(target);f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),receipt);int rows=f.count("1=1");assertThrows(RuntimeException.class,()->service.read(candidate));assertThrows(RuntimeException.class,()->fixture.tx(()->service.read(candidate)));assertEquals(rows,f.count("1=1"));assertNotNull(read());
 }
 @Test void terminalMetadataRemainsOnlyARouteAndClosedConstructorsDoNotPerformIo(){
  var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogSourceIntentOriginService(new JdbcCatalogSourceIntentScan(source,category),category));org.mockito.Mockito.verifyNoInteractions(source);for(String category:List.of("news_tech","AGENT","report",""))assertThrows(RuntimeException.class,()->new CatalogSourceIntentOriginService(storage,category));
  for(String state:List.of("succeeded","no_input")){f.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,candidate.id());candidate=readonly(()->storage.page(null,50)).candidates().getFirst();assertEquals("fictional-run",read().runId());assertEquals(0,f.count("target_type IN ('source_stage_plan','source_dedup_partial_seal','catalog_source_final_result')"));}f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",candidate.id());assertTrue(readonly(()->storage.page(null,50)).candidates().isEmpty());
 }

 @Test void originalCollectionComesFromActualHeaderAndPermanentFactsAfterCurrentSourceRemoval()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category);var expected=fixture.collection;var proof=readonly(()->service.readCollection(candidate.id()));assertEquals(expected,proof.collection());assertEquals(read(),proof.route());assertEquals(f.clock.instant(),proof.actedAt());assertEquals("OriginalCatalogCollection[redacted]",proof.toString());assertNull(readonly(()->service.readCollection("fictional-missing")));
   f.jdbc.update("DELETE FROM source_run_items");f.jdbc.update("DELETE FROM source_runs");f.jdbc.update("DELETE FROM sources");assertEquals(expected,readonly(()->service.readCollection(candidate.id())).collection());
   f.jdbc.update("UPDATE jobs SET task_type='news_dedup' WHERE id=?",candidate.id());assertThrows(RuntimeException.class,()->readonly(()->service.readCollection(candidate.id())));f.jdbc.update("UPDATE jobs SET task_type=? WHERE id=?",category+"_dedup",candidate.id());assertEquals(expected,readonly(()->service.readCollection(candidate.id())).collection());f.jdbc.update("DELETE FROM jobs WHERE id=?",receipt);assertThrows(RuntimeException.class,()->readonly(()->service.readCollection(candidate.id())));
  }
 }
 @Test void permanentOriginalCollectionDateAndMicrosecondTimeStayExactAcrossDefaultTimeZones(){
  var expected=fixture.collection;var old=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){TimeZone.setDefault(TimeZone.getTimeZone(zone));var original=readonly(()->service.readCollection(candidate.id()));assertEquals(expected,original.collection(),zone);assertEquals(f.clock.instant(),original.actedAt(),zone);}}finally{TimeZone.setDefault(old);}
 }
}
