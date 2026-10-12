package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.node.ObjectNode;

class CatalogSourceDedupPartialSealTest {
 CatalogSourceDedupPartialArchiveTest a;JdbcSourceDedupPartialReceipts receipts;JdbcSourceStageRoots roots;SourceDedupPartialSealService service;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){setup("agent",203);}
 void setup(String category,int count){a=new CatalogSourceDedupPartialArchiveTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceDedupPartialSealTest.this.database();}};a.setup(category,count);receipts=new JdbcSourceDedupPartialReceipts(a.db().source);roots=new JdbcSourceStageRoots(a.db().source);service=provider(a.db().writes);}
 SourceDedupPartialSealService provider(SharedWriteExecutor writes){return new SourceDedupPartialSealService(a.f.service,a.service,receipts,roots,writes,new DataSourceTransactionManager(a.db().source),a.db().clock);}
 int seals(){return a.db().count("target_type='source_dedup_partial_result'");}
 @Test void sparseOriginal203DecisionCatalogSealsExactlyOnceWithoutParentObservationOrNewCalls(){
  a.sparse();String state=a.db().jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,a.f.ref.ownerId());int calls=a.db().jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var proof=service.seal(a.f.ref);assertEquals(203,proof.proof().decisions().size());assertEquals(100,proof.proof().members().size());assertEquals("agent_dedup",CanonicalJson.parse(proof.proof().observationKeyJson()).path("stage").asText());assertEquals(a.db().clock.instant(),proof.minimumObservationTime());assertEquals(1,seals());int rows=a.db().count("1=1");assertEquals(proof.receiptJson(),provider(a.db().writes).seal(a.f.ref).receiptJson());assertEquals(rows,a.db().count("1=1"));assertEquals(state,a.db().jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,a.f.ref.ownerId()));assertEquals(calls,a.db().jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,a.db().count("target_type='source_stage_availability'"));
 }
 @Test void actualFourCatalogOwnerTypesMustExactlyMatchTheirOriginalStage(){
  for(String category:java.util.List.of("agent","skill","workflow","model")){setup(category,5);a.f.terminal(0,"failed");var sealed=service.seal(a.f.ref);assertEquals(category+"_dedup",CanonicalJson.parse(sealed.proof().observationKeyJson()).path("stage").asText());assertEquals(1,seals());assertThrows(RuntimeException.class,()->a.f.f.tx(()->{receipts.lockOwner(a.f.ref.ownerId());return true;}));}
 }
 @Test void actualSealRollbackKeepsOnlyBlocksAndRealCommitAckLossRecovers(){
  setup("skill",5);a.f.terminal(0,"failed");a.db().jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_partial_seal CHECK(target_type IS NULL OR target_type<>'source_dedup_partial_result')");assertThrows(RuntimeException.class,()->service.seal(a.f.ref));assertEquals(0,seals());assertEquals(2,a.blockCount());a.db().jdbc.execute("ALTER TABLE jobs"+(a.db().source instanceof org.h2.jdbcx.JdbcDataSource?" DROP CONSTRAINT ":" DROP CHECK ")+"fictional_catalog_partial_seal");
  var manager=new DataSourceTransactionManager(a.db().source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional catalog seal ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(a.db().source),manager,()->25,n->{});var value=provider(writes).seal(a.f.ref);assertEquals(value.receiptJson(),service.seal(a.f.ref).receiptJson());assertEquals(1,seals());
 }
 @Test void originalOwnerTypeAndRootRevisionMustStillAgreeInsideActualSealWriter(){
  setup("workflow",5);a.f.terminal(0,"failed");receipts=spy(receipts);doAnswer(call->{a.db().jdbc.update("UPDATE jobs SET task_type='model_dedup' WHERE id=?",a.f.ref.ownerId());return call.callRealMethod();}).when(receipts).lockOwner(a.f.ref.ownerId(),"workflow_dedup");service=provider(a.db().writes);assertThrows(RuntimeException.class,()->service.seal(a.f.ref));assertEquals(0,seals());assertEquals(2,a.blockCount());receipts=new JdbcSourceDedupPartialReceipts(a.db().source);roots=spy(roots);
  doAnswer(call->{a.db().jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",a.f.f.plan.chunks().getFirst().rootId());return call.callRealMethod();}).when(roots).lockOriginal(anyList());service=provider(a.db().writes);assertThrows(RuntimeException.class,()->service.seal(a.f.ref));assertEquals(0,seals());roots=new JdbcSourceStageRoots(a.db().source);service=provider(a.db().writes);assertNotNull(service.seal(a.f.ref));
 }
 @Test void rehashedReceiptChangedNativeOrBrokenArchiveCannotBecomeCommittedPartial(){
  setup("model",5);a.f.terminal(0,"failed");var actual=service.seal(a.f.ref);String id=JdbcSourceDedupPartialReceipts.receiptId(a.f.ref.ownerId());
  for(String mode:java.util.List.of("extra","result_hash","native_capture_hash","plan_ref","archive_ref","sealed_at")){var node=(ObjectNode)CanonicalJson.parse(actual.receiptJson());switch(mode){case "extra"->node.put("extra",true);case "plan_ref","archive_ref"->((ObjectNode)node.get(mode)).put("owner_job_id","fictional-other");case "sealed_at"->node.put(mode,"2020-01-01T00:00:00.000000Z");default->node.put(mode,"fictional-wrong");}String raw=JdbcFrozenInputs.canonical(node);a.db().jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,()->a.f.f.readonly(()->service.readCommitted(a.f.ref)));a.db().jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",actual.receiptJson(),JdbcGenerationLineage.hash(actual.receiptJson()),id);}
  a.db().jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result' AND part_no=1",new byte[32],a.f.ref.ownerId());assertThrows(RuntimeException.class,()->a.f.f.readonly(()->service.readCommitted(a.f.ref)));assertEquals(1,seals());
 }
 @Test void pendingUnknownCompleteZeroWrongTransactionsAndCapacityCannotWriteSeal(){
  setup("agent",5);assertNull(service.seal(a.f.ref));a.f.complete(0);assertNull(service.seal(a.f.ref));assertThrows(RuntimeException.class,()->a.f.f.tx(()->service.seal(a.f.ref)));assertThrows(RuntimeException.class,()->service.readCommitted(a.f.ref));setup("model",0);assertNull(service.seal(a.f.ref));assertEquals(0,seals());setup("skill",5);a.f.terminal(0,"failed");roots=spy(roots);doThrow(new JdbcSourceStageRoots.CapacityRejected()).when(roots).lockOriginal(anyList());service=provider(a.db().writes);assertThrows(GenerationFlowService.CapacityGuarded.class,()->service.seal(a.f.ref));assertEquals(0,seals());assertEquals(2,a.blockCount());
 }
}
