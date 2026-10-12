package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

/** Actual final metadata storage, without claiming projection, business result application or final availability. */
class CatalogSourceFinalReceiptTest {
 CatalogSourceFinalProofTest f;CatalogSourceFinalReceiptService service;CatalogSourceFinalReceiptService.Stored original;SourceRunAnalysisCommand command;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category,boolean empty)throws Exception{
  f=new CatalogSourceFinalProofTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceFinalReceiptTest.this.database();}};f.install(category,empty);if(!empty)f.complete();service=new CatalogSourceFinalReceiptService(new JdbcCatalogSourceFinalReceipts(f.f.source),category);
  var result=f.read().result();var plan=f.results.plan;var collection=f.prepared.collection;var ref=FrozenCatalogSourceFinal.encode(result).reference();var position=new SourceRunAnalysisCommand.Position(f.ref.ownerId(),plan.logicalKey(),plan.generation(),plan.scope().cutoff(),result.hash(),CatalogSourceFinalReceiptService.receiptId(f.ref.ownerId()));
  command=new SourceRunAnalysisCommand(collection.facts().runId(),collection.facts().sourceId(),plan.scope().businessDate(),collection.facts().generation(),collection.facts().manifestHash(),collection.facts().accepted(),collection.facts().excluded(),position,result.summary(),"",result.coverageState());
  original=CatalogSourceFinalReceiptService.Stored.create(command,ref,new SourceRunAnalysisService.Outcome("applied",3),f.f.clock.instant(),category);
 }
 @BeforeEach void setup()throws Exception{install("agent",false);}
 CatalogSourceFinalReceiptService.Stored read(){return f.results.readonly(()->service.read(f.ref.ownerId()));}
 CatalogSourceFinalReceiptService.Stored append(){return f.prepared.tx(()->service.append(original));}
 int receipts(){return f.f.count("target_type='catalog_source_final_result'");}
 @Test void fourCategoriesReplayOneExactNativeReceiptAndReturnOnlyOriginalPosition()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,false);String nativeState=f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId());assertEquals("queued",nativeState);assertNull(read());assertEquals(original.json(),append().json());assertEquals(original.json(),read().json());assertEquals(category,read().category());assertEquals(original.position(),f.prepared.tx(()->service.prior(command.runId(),List.of(f.ref.ownerId(),f.results.plan.chunks().getFirst().rootId(),original.position().receiptId()))));int count=f.f.count("1=1");append();assertEquals(count,f.f.count("1=1"));assertEquals(1,receipts());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("StoredCatalogSourceFinal[redacted]",read().toString());assertEquals(nativeState,f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(0,f.f.count("target_type='source_stage_availability'"));}
 }
 @Test void rolesRehashedForeignCategoryGenerationAndNativeTimeCannotReplaceOriginal(){
  append();String id=original.position().receiptId();f.f.jdbc.update("UPDATE jobs SET operator_id='fictional-other' WHERE id=?",id);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET operator_id=? WHERE id=?",JdbcCatalogSourceFinalReceipts.ACTOR,id);
  for(int mode=0;mode<3;mode++){var payload=(ObjectNode)CanonicalJson.parse(original.json());switch(mode){case 0->payload.put("category","skill");case 1->((ObjectNode)payload.get("position")).put("generation",2);case 2->payload.put("fictional_extra",true);}String raw=JdbcFrozenInputs.canonical(payload);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",original.json(),JdbcGenerationLineage.hash(original.json()),id);}
  f.f.jdbc.update("UPDATE jobs SET acted_at=? WHERE id=?",java.sql.Timestamp.from(f.f.clock.instant().minusSeconds(1)),id);assertThrows(RuntimeException.class,this::read);f.f.jdbc.update("UPDATE jobs SET acted_at=? WHERE id=?",java.sql.Timestamp.from(f.f.clock.instant()),id);assertNotNull(read());
 }
 @Test void appendRollbackAndActualCommittedAckLossPreserveOneReceipt(){
  f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_receipt_failure CHECK(target_type IS NULL OR target_type<>'catalog_source_final_result')");assertThrows(RuntimeException.class,this::append);assertEquals(0,receipts());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_catalog_receipt_failure":"ALTER TABLE jobs DROP CHECK fictional_catalog_receipt_failure");
  var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional catalog receipt ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var result=writes.execute(new SharedWriteExecutor.ActionContext(CatalogSourceFinalReceiptService.action(f.ref.ownerId()),original.position().receiptId(),original.position().resultHash()),k->service.read(f.ref.ownerId()),a->service.append(original));assertEquals(original.json(),result.json());assertEquals(1,receipts());assertEquals(original.json(),read().json());
 }
 @Test void nativeCursorCompleteAndReceiptAppendAreAtomicAndCompareRevision(){
  long revision=f.f.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,f.ref.ownerId());assertThrows(RuntimeException.class,()->f.prepared.tx(()->{var cursor=service.begin(command);service.complete(cursor,false,f.f.clock.instant());service.append(original);throw new IllegalStateException("fictional after receipt rollback");}));assertEquals(0,receipts());assertEquals(revision,f.f.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,f.ref.ownerId()));
  f.prepared.tx(()->{var cursor=service.begin(command);service.complete(cursor,false,f.f.clock.instant());assertThrows(RuntimeException.class,()->service.complete(cursor,false,f.f.clock.instant()));service.append(original);return true;});assertEquals("succeeded",f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(revision+1,f.f.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,f.ref.ownerId()));assertEquals(original.json(),read().json());assertThrows(RuntimeException.class,()->f.prepared.tx(()->service.begin(command)));
 }
 @Test void exactConfiguredOwnerIsRecheckedInsideWriterAndForeignCategoryRollsBack(){
  assertThrows(RuntimeException.class,()->f.prepared.tx(()->{f.f.jdbc.update("UPDATE jobs SET task_type='skill_dedup' WHERE id=?",f.ref.ownerId());return service.append(original);}));assertEquals("agent_dedup",f.f.jdbc.queryForObject("SELECT task_type FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(0,receipts());var other=new CatalogSourceFinalReceiptService(new JdbcCatalogSourceFinalReceipts(f.f.source),"model");assertThrows(RuntimeException.class,()->f.results.readonly(()->other.read(f.ref.ownerId())));assertThrows(RuntimeException.class,()->f.prepared.tx(()->other.append(original)));assertEquals(0,receipts());
 }
 @Test void readonlyAppendMissingRefsCrossRunAliasesAndWidePayloadCannotGrantPosition(){
  assertThrows(RuntimeException.class,()->service.append(original));assertThrows(RuntimeException.class,()->f.results.readonly(()->service.append(original)));append();assertThrows(RuntimeException.class,()->f.prepared.tx(()->service.prior("fictional-wrong-run",List.of(original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.prepared.tx(()->service.prior(command.runId(),List.of("fictional-missing-ref",original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.results.readonly(()->service.prior(command.runId(),List.of(original.position().receiptId()))));assertThrows(RuntimeException.class,()->f.results.readonly(()->service.read(f.ref.ownerId().toUpperCase(Locale.ROOT))));String raw="{\"fictional_padding\":\""+"x".repeat(32769)+"\"}";f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),original.position().receiptId());assertThrows(RuntimeException.class,this::read);assertEquals(1,receipts());
 }
 @Test void actualEmptyMetadataUsesNoInputWithoutSyntheticRootsAndConstructorsHaveNoIo()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,true);assertEquals(0,command.accepted());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));f.prepared.tx(()->{var cursor=service.begin(command);service.complete(cursor,true,f.f.clock.instant());return service.append(original);});assertEquals("no_input",f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(original.json(),read().json());}
  var storage=org.mockito.Mockito.mock(JdbcCatalogSourceFinalReceipts.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogSourceFinalReceiptService(storage,category));for(String category:List.of("news_tech","AGENT","report",""))assertThrows(RuntimeException.class,()->new CatalogSourceFinalReceiptService(storage,category));org.mockito.Mockito.verifyNoInteractions(storage);
 }
}
