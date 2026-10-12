package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Native route metadata only; no fixture row supplies business completion. */
class CatalogSourceIntentScanTest {
 GenerationJdbcStoreTest f;JdbcCatalogSourceIntentScan store;Map<String,List<String>> ids;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){
  f=database();f.fixture();ids=new LinkedHashMap<>();
   var records=new JdbcJobRecords(f.source);for(String category:List.of("agent","skill","workflow","model")){
    var selected=new ArrayList<String>();for(int n=0;n<54;n++){String key="fictional-catalog-scan:"+category+":"+n,id=JdbcFrozenInputs.stableId("intent",key+":1"),state=n==51?"succeeded":n==52?"no_input":n==53?"cancelled":"waiting_input";
     f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-catalog-scan-seed:"+category+":"+n,"fictional-owner",null),k->null,a->{records.ensureIntent(new JdbcJobRecords.Intent(id,key,1,category+"_dedup",LocalDate.of(2026,1,1)),f.clock.instant());f.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,id);return true;});if(n<53)selected.add(id);
    }ids.put(category,selected.stream().sorted().toList());
   }for(String type:List.of("news_dedup","daily_report")){String key="fictional-unrelated:"+type;f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-unrelated-seed:"+type,"fictional-owner",null),k->null,a->records.ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",key+":1"),key,1,type,LocalDate.of(2026,1,1)),f.clock.instant()));}store=new JdbcCatalogSourceIntentScan(f.source,"agent");
 }
 <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
 JdbcSourceIntentScan.Page page(String after,int limit){return readonly(()->store.page(after,limit));}
 @Test void fourExactCategoriesHaveStableFiftyPlusOnePagesAndTerminalRecoveryMetadata(){
  int rows=f.count("1=1");for(String category:ids.keySet()){store=new JdbcCatalogSourceIntentScan(f.source,category);var first=page(null,50);assertTrue(first.hasMore());assertEquals(ids.get(category).subList(0,50),first.candidates().stream().map(JdbcSourceIntentScan.Candidate::id).toList());assertEquals(first.candidates().getLast().id(),first.nextCursor());
   var last=page(first.nextCursor(),50);assertEquals(ids.get(category).subList(50,53),last.candidates().stream().map(JdbcSourceIntentScan.Candidate::id).toList());assertFalse(last.hasMore());assertNull(last.nextCursor());assertTrue(page(ids.get(category).getLast(),50).candidates().isEmpty());var all=page(null,50).candidates();assertTrue(all.stream().allMatch(c->c.type().equals(category+"_dedup")));
   byte[] bytes=first.candidates().getFirst().keyHash();bytes[0]^=1;assertArrayEquals(JdbcGenerationLineage.hash(first.candidates().getFirst().key()),first.candidates().getFirst().keyHash());
  }assertEquals(rows,f.count("1=1"));assertEquals(1,readonly(()->new JdbcSourceIntentScan(f.source).page(null,50)).candidates().size());assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt')"));
 }
 @Test void actualRawIdentityNullableFieldsHashAndWideKeyRefuseTheWholePage(){
  String id=ids.get("agent").getFirst();f.jdbc.update("UPDATE jobs SET id=? WHERE id=?",id.toUpperCase(Locale.ROOT),id);assertThrows(RuntimeException.class,()->page(null,50));f.jdbc.update("UPDATE jobs SET id=? WHERE id=?",id,id.toUpperCase(Locale.ROOT));
  byte[] hash=f.jdbc.queryForObject("SELECT logical_key_hash FROM jobs WHERE id=?",byte[].class,id);f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE id=?",new byte[32],id);assertThrows(RuntimeException.class,()->page(null,50));f.jdbc.update("UPDATE jobs SET logical_key_hash=? WHERE id=?",hash,id);
  for(String field:List.of("generation","business_date","revision")){Object old=f.jdbc.queryForObject("SELECT "+field+" FROM jobs WHERE id=?",Object.class,id);if(field.equals("revision"))f.jdbc.update("UPDATE jobs SET revision=-1 WHERE id=?",id);else f.jdbc.update("UPDATE jobs SET "+field+"=NULL WHERE id=?",id);assertThrows(RuntimeException.class,()->page(null,50));f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",old,id);}
  if(!(f.source instanceof org.h2.jdbcx.JdbcDataSource)){f.jdbc.update("UPDATE jobs SET task_type='AGENT_DEDUP' WHERE id=?",id);assertThrows(RuntimeException.class,()->page(null,50));f.jdbc.update("UPDATE jobs SET task_type='agent_dedup' WHERE id=?",id);}
  f.jdbc.update("UPDATE jobs SET logical_key=? WHERE id=?","x".repeat(4097),id);assertThrows(RuntimeException.class,()->page(null,50));
 }
 @Test void constructorIsClosedAndNoIoWhileOriginalTransactionAndCursorBoundsRemain(){
  var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);for(String category:ids.keySet())assertNotNull(new JdbcCatalogSourceIntentScan(source,category));org.mockito.Mockito.verifyNoInteractions(source);
  for(String category:List.of("news_tech","report","AGENT","","agent_dedup"))assertThrows(RuntimeException.class,()->new JdbcCatalogSourceIntentScan(source,category));assertThrows(RuntimeException.class,()->store.page(null,1));assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-writer-scan","fictional-owner",null),k->null,a->store.page(null,1)));
  for(int limit:List.of(-1,0,51))assertThrows(RuntimeException.class,()->page(null,limit));for(String cursor:List.of("","INVALID","x".repeat(65),"invalid cursor"))assertThrows(RuntimeException.class,()->page(cursor,1));assertThrows(RuntimeException.class,()->readonly(()->store.origins(ids.get("agent").getFirst(),"skill_dedup")));
 }
 @Test void boundedActualOriginMetadataHasDefensiveHashesAndDoesNotGrantAPlan(){
  String owner=ids.get("agent").getFirst();var target=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","generation_intent").put("id",owner);String key=JdbcFrozenInputs.canonical(target),body="{\"schema_version\":\"fictional-origin-v1\"}";
  f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-origin-write","fictional-owner",null),k->null,a->new JdbcJobRecords(f.source).appendDisposition(new JdbcJobRecords.Disposition("fictional-catalog-origin","fictional-origin-action","collection_handoff",key,"fictional-actor","Fictional metadata fixture",body),f.clock.instant()));
  var raw=readonly(()->store.origins(owner,"agent_dedup")).getFirst();assertEquals(body,JdbcFrozenInputs.canonical(CanonicalJson.parse(raw.payload())));assertArrayEquals(JdbcGenerationLineage.hash(body),raw.payloadHash());byte[] hash=raw.payloadHash();hash[0]^=1;assertArrayEquals(JdbcGenerationLineage.hash(body),raw.payloadHash());assertEquals("SourceIntentOriginMetadata[redacted]",raw.toString());
  var padded=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("fictional_padding","x".repeat(8193));String wide=JdbcFrozenInputs.canonical(padded);f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id='fictional-catalog-origin'",wide,JdbcGenerationLineage.hash(wide));raw=readonly(()->store.origins(owner,"agent_dedup")).getFirst();assertNull(raw.payload());assertTrue(raw.payloadBytes()>8192);assertEquals(0,f.count("target_type='source_stage_plan' OR record_kind='ai_attempt'"));
 }
 @Test void nativeBusinessDateRemainsOriginalUnderFourJvmDefaultTimeZones(){
  var original=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){TimeZone.setDefault(TimeZone.getTimeZone(zone));var actual=page(null,50);assertTrue(actual.candidates().stream().allMatch(c->c.day().equals(LocalDate.of(2026,1,1))));}}finally{TimeZone.setDefault(original);}
 }

 @Test void actualByIdRequiresNativeHeaderAndExactConfiguredCategoryWithoutPagingFallback(){
  String id=ids.get("agent").getFirst();var original=page(null,50).candidates().getFirst();var actual=readonly(()->store.find(id));assertEquals(original.id(),actual.id());assertEquals(original.key(),actual.key());assertArrayEquals(original.keyHash(),actual.keyHash());assertEquals(original.day(),actual.day());assertEquals(original.generation(),actual.generation());assertEquals(original.revision(),actual.revision());assertEquals(original.type(),actual.type());assertEquals(original.state(),actual.state());assertNull(readonly(()->store.find("fictional-missing")));
  assertThrows(RuntimeException.class,()->readonly(()->new JdbcCatalogSourceIntentScan(f.source,"skill").find(id)));assertThrows(RuntimeException.class,()->store.find(id));assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-writer-find","fictional-owner",null),k->null,a->store.find(id)));
  f.jdbc.update("UPDATE jobs SET record_kind='snapshot_part',owner_job_id=?,purpose='input',stamp_id='fictional-role-change',part_no=0,payload_json='{}',payload_hash=? WHERE id=?",id,HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),id);assertThrows(RuntimeException.class,()->readonly(()->store.find(id)));f.jdbc.update("UPDATE jobs SET record_kind='generation_intent',logical_key_hash=? WHERE id=?",new byte[32],id);assertThrows(RuntimeException.class,()->readonly(()->store.find(id)));
 }
}
