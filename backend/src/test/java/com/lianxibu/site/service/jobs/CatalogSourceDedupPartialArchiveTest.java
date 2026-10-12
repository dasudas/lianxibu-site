package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;

class CatalogSourceDedupPartialArchiveTest {
 CatalogSourceStageResolutionTest f;JdbcSourceDedupPartialBlocks blocks;SourceDedupPartialArchiveService service;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void setup(String category,int count){f=new CatalogSourceStageResolutionTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceDedupPartialArchiveTest.this.database();}};f.install(category,count);blocks=new JdbcSourceDedupPartialBlocks(db().source);service=provider(db().writes);}
 @BeforeEach void setup(){setup("agent",203);}
 GenerationJdbcStoreTest db(){return f.f.f;}
 SourceDedupPartialArchiveService provider(SharedWriteExecutor writes){return new SourceDedupPartialArchiveService(f.service,blocks,writes,new DataSourceTransactionManager(db().source),db().clock);}
 int blockCount(){return db().count("record_kind='snapshot_part' AND purpose='result' AND owner_job_id='"+f.ref.ownerId()+"'");}
 void sparse(){f.complete(0);f.terminal(1,"failed");}
 @Test void actualCatalogSparseBytesReplayWithoutSealingObservingOrNewModelCalls(){
  sparse();int calls=db().jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var value=service.stage(f.ref);var original=SourceDedupStageResult.createResolved(value.original());assertEquals(2,blockCount());assertEquals(original.json(),f.f.readonly(()->service.verifyStaged(value.archiveRef(),original)).json());int rows=db().count("1=1");service=provider(db().writes);assertEquals(value.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(rows,db().count("1=1"));assertEquals(calls,db().jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,db().count("target_type IN ('source_dedup_partial_result','source_stage_availability')"));
 }
 @Test void fourExactOriginalOwnerTypesAllowTheirOwnPartialBlocksAndLegacyPortRemainsNews(){
  for(String category:java.util.List.of("agent","skill","workflow","model")){setup(category,5);f.terminal(0,"failed");assertNotNull(service.stage(f.ref));assertEquals(2,blockCount());assertThrows(RuntimeException.class,()->f.f.readonly(()->{blocks.immutableOwner(f.ref.ownerId());return true;}));assertThrows(RuntimeException.class,()->f.f.readonly(()->{blocks.immutableOwner(f.ref.ownerId(),category.equals("agent")?"workflow_dedup":"agent_dedup");return true;}));}
 }
 @Test void actualInterruptedBlockPrefixAndRealCommitAckLossRecoverOnlyOriginalBytes(){
  setup("skill",5);f.terminal(0,"failed");db().jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_partial_prefix CHECK(purpose IS NULL OR purpose<>'result' OR part_no<>1)");assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(1,blockCount());db().jdbc.execute("ALTER TABLE jobs"+(db().source instanceof org.h2.jdbcx.JdbcDataSource?" DROP CONSTRAINT ":" DROP CHECK ")+"fictional_catalog_partial_prefix");
  var manager=new DataSourceTransactionManager(db().source){boolean lost;@Override protected void doCommit(DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional catalog block ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(db().source),manager,()->25,n->{});var staged=provider(writes).stage(f.ref);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(2,blockCount());
 }
 @Test void changedOriginalOwnerTypeInsideWriterCannotStageAnyCatalogBlock(){
  setup("agent",5);f.terminal(0,"failed");blocks=spy(blocks);doAnswer(call->{db().jdbc.update("UPDATE jobs SET task_type='workflow_dedup' WHERE id=?",f.ref.ownerId());return call.callRealMethod();}).when(blocks).lockOwner(f.ref.ownerId(),"agent_dedup");service=provider(db().writes);assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(0,blockCount());assertEquals("agent_dedup",db().jdbc.queryForObject("SELECT task_type FROM jobs WHERE id=?",String.class,f.ref.ownerId()));
 }
 @Test void actualNativeChangeDuringStagingNeverConfersDurablePartialCompletion(){
  setup("workflow",5);f.terminal(0,"failed");blocks=spy(blocks);doAnswer(call->{var value=call.callRealMethod();if((int)call.getArgument(2)==0)f.terminal(0,"cancelled");return value;}).when(blocks).put(anyString(),anyString(),anyInt(),anyString(),any());service=provider(db().writes);assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,blockCount());assertEquals(0,db().count("target_type='source_dedup_partial_result'"));blocks=new JdbcSourceDedupPartialBlocks(db().source);service=provider(db().writes);assertNotNull(service.stage(f.ref));assertEquals(4,blockCount());
 }
 @Test void brokenOriginalBlocksHashesRolesAndWrongTransactionsStayRejected(){
  setup("model",5);f.terminal(0,"failed");var staged=service.stage(f.ref);var proof=SourceDedupStageResult.createResolved(staged.original());assertThrows(RuntimeException.class,()->service.verifyStaged(staged.archiveRef(),proof));assertThrows(RuntimeException.class,()->f.f.tx(()->service.verifyStaged(staged.archiveRef(),proof)));db().jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result' AND part_no=1",new byte[32],f.ref.ownerId());assertThrows(RuntimeException.class,()->f.f.readonly(()->service.verifyStaged(staged.archiveRef(),proof)));assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,blockCount());
 }
 @Test void pendingUnknownCompleteAndZeroCannotCreateSyntheticPartialBlocks(){
  setup("agent",5);assertNull(service.stage(f.ref));for(String state:java.util.List.of("unknown_external","blocked_configuration")){db().jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,f.f.plan.chunks().getFirst().rootId());assertNull(service.stage(f.ref));}assertEquals(0,blockCount());f.complete(0);assertNull(service.stage(f.ref));assertThrows(RuntimeException.class,()->f.f.tx(()->service.stage(f.ref)));setup("model",0);assertNull(service.stage(f.ref));assertEquals(0,blockCount());
 }
}
