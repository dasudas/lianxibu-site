package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual original collection, preparation and complete native archive; retained results are fictional fixtures. */
class CatalogSourceFinalArchiveTest {
 CatalogSourceFinalProofTest f;JdbcCatalogSourceFinalBlocks store;CatalogSourceFinalArchiveService service;
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category,int count)throws Exception{f=new CatalogSourceFinalProofTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceFinalArchiveTest.this.database();}};f.install(category,count);store=new JdbcCatalogSourceFinalBlocks(f.f.source,category);service=provider(f.f.writes);}
 @BeforeEach void setup()throws Exception{install("agent",2);}
 CatalogSourceFinalArchiveService provider(SharedWriteExecutor writes){return new CatalogSourceFinalArchiveService(f.service,store,writes,new DataSourceTransactionManager(f.f.source),f.f.clock);}
 int blocks(){return f.f.count("record_kind='snapshot_part' AND purpose='result' AND owner_job_id='"+f.ref.ownerId()+"'");}
 void large()throws Exception{
  install("workflow",501);f.results.pure=new CatalogSourceDedupFragmentProofTest(){
   @Override tools.jackson.databind.node.ObjectNode payload(SourcePartitionPlanner.Chunk c,String category){var n=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","😀".repeat(8000)+" tail-"+c.stagePart());var rows=n.putArray("items");for(String id:c.itemIds())rows.addObject().put("item_id",id).put("category",category).put("decision","new").putNull("candidate_entity_id").put("reason","Fictional archived result").put("confidence","1").put("suggested_title","Fictional catalog title").putNull("suggested_summary").put("reanalyze_recommended",false);return n;}
   @Override tools.jackson.databind.node.ObjectNode handoff(CatalogSourcePlanBuilderTest catalog,SourcePartitionPlanner.Plan plan,SourcePartitionPlanner.Chunk c){var result=ResultApplicationService.validate(input(c),envelope(c,catalog.category));var n=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",c.rootId()).put("input_hash",c.inputHash()).put("result_hash",result.resultHash()).put("stage",plan.stage().name().toLowerCase(Locale.ROOT)).put("generation",plan.generation()).put("stage_part",c.stagePart());n.set("payload",com.lianxibu.site.domain.protocol.CanonicalJson.parse(result.payloadJson()));var rows=n.putArray("members");for(String id:c.itemIds()){var original=catalog.original.members().stream().filter(m->m.itemId().equals(id)).findFirst().orElseThrow();rows.add(tools.jackson.databind.json.JsonMapper.shared().valueToTree(new com.lianxibu.site.service.sources.SourceMemberStageService.Fact(id,catalog.original.runId(),catalog.original.sourceId(),original.sourceItemId(),catalog.category,"fictional-created-"+id,"ready",4,plan.scope().businessDate(),null,plan.generation(),"production")));}return n;}
  };
  for(var chunk:f.results.plan.chunks()){f.results.chunk=chunk;f.results.complete();}
 }
 @Test void pendingDamagedNativeResultAndOuterTransactionCannotStageFinal(){
  assertNull(service.stage(f.ref));assertEquals(0,blocks());assertThrows(RuntimeException.class,()->f.prepared.tx(()->service.stage(f.ref)));f.complete();f.f.jdbc.update("UPDATE jobs SET result_hash=? WHERE id=?",new byte[32],f.results.chunk.rootId());assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(0,blocks());
 }
 @Test void actual501OriginalMembersEveryFragmentAndMultibyteTailSurviveNativeBlocks()throws Exception{
  large();var staged=service.stage(f.ref);assertNotNull(staged);assertTrue(blocks()>2);var node=com.lianxibu.site.domain.protocol.CanonicalJson.parse(staged.original().result().json());int members=0;for(var fragment:node.path("stage_result").path("fragments"))members+=fragment.path("members").size();assertEquals(501,members);assertEquals(6,node.path("stage_result").path("fragments").size());assertTrue(staged.original().result().summary().endsWith("tail-5"));assertEquals(staged.original().result().json(),f.results.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())).json());int count=f.f.count("1=1");service=provider(f.f.writes);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(count,f.f.count("1=1"));assertEquals(6,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,f.f.count("target_type IN ('catalog_source_final_result','source_stage_availability')"));
 }
 @Test void interruptedLargeStagingKeepsPrefixAndRestartCompletesOnlyMissingBlocks()throws Exception{
  large();f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_final_block_failure CHECK(purpose IS NULL OR purpose<>'result' OR owner_job_id<>'"+f.ref.ownerId()+"' OR part_no<>2)");assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,blocks());assertEquals(0,f.f.count("target_type='catalog_source_final_result'"));f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_catalog_final_block_failure":"ALTER TABLE jobs DROP CHECK fictional_catalog_final_block_failure");var staged=service.stage(f.ref);assertEquals(FrozenCatalogSourceFinal.encode(staged.original().result()).payloads().size(),blocks());assertEquals(staged.original().result().hash(),f.results.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())).hash());
 }
 @Test void actualBlockCommitAckLossRecoversAndChangedStoredBlocksRefuse(){
  f.complete();var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional catalog final block ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var staged=provider(writes).stage(f.ref);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(2,blocks());f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result' AND owner_job_id=? AND part_no=1",new byte[32],f.ref.ownerId());assertThrows(RuntimeException.class,()->service.stage(f.ref));assertThrows(RuntimeException.class,()->f.results.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())));assertEquals(2,blocks());
 }
 @Test void nativeOwnerTypeChangeBeforeAnyBlockAndActualZeroRemainExact()throws Exception{
  f.complete();f.f.jdbc.update("UPDATE jobs SET task_type='skill_dedup' WHERE id=?",f.ref.ownerId());assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(0,blocks());for(String category:List.of("agent","skill","workflow","model")){install(category,0);var staged=service.stage(f.ref);assertNotNull(staged);assertEquals(2,blocks());assertEquals(0,f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals("complete",staged.original().result().coverageState());assertEquals(0,f.f.count("target_type='catalog_source_final_result'"));}
 }
}
