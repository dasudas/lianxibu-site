package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Actual native capture and short writer recheck; retention/handoff remains an explicit fictional fixture. */
class CatalogSourceFinalCommitPermitTest {
 CatalogSourceFinalArchiveTest f;JdbcCatalogSourceFinalCommitFacts facts;CatalogSourceFinalCommitPermitService service;CatalogSourceFinalArchiveService.Staged staged;String category="agent";
 GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
 void install(String category,int count,boolean complete)throws Exception{this.category=category;f=new CatalogSourceFinalArchiveTest(){@Override GenerationJdbcStoreTest database(){return CatalogSourceFinalCommitPermitTest.this.database();}};f.install(category,count);if(complete&&count>0)f.f.complete();facts=new JdbcCatalogSourceFinalCommitFacts(f.f.f.source,category);service=provider(facts);staged=f.service.stage(f.f.ref);}
 @BeforeEach void setup()throws Exception{install("agent",2,true);}
 CatalogSourceFinalCommitPermitService provider(JdbcCatalogSourceFinalCommitFacts storage){return new CatalogSourceFinalCommitPermitService(f.f.service,f.service,f.f.prepared.plans,f.f.resolutions,storage,new DataSourceTransactionManager(f.f.f.source),category);}
 CatalogSourceFinalCommitPermitService.Captured capture(){return service.capture(f.f.ref,staged.archiveRef());}
 @Test void fourCategoriesActualOriginalProofAndStagedBlocksAuthorizeOnlyOriginalWriter()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,2,true);int count=f.f.f.count("1=1");var token=capture();assertNotNull(token);assertEquals(staged.original().result().json(),token.proof().result().json());assertEquals(staged.archiveRef(),token.archiveRef());assertEquals(token.proof(),f.f.prepared.tx(()->service.verify(token)));assertEquals(count,f.f.f.count("1=1"));assertEquals(0,f.f.f.count("target_type='catalog_source_final_result'"));assertEquals(1,f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("CapturedCatalogSourceFinalCommit[redacted]",token.toString());}
 }
 @Test void tokenConstructorsArePrivateAndTokensAreBoundToTheirIssuer(){
  var token=capture();for(var constructor:CatalogSourceFinalCommitPermitService.Captured.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));var other=provider(facts);assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->other.verify(token)));assertThrows(RuntimeException.class,()->service.verify(token));assertThrows(RuntimeException.class,()->f.f.results.readonly(()->service.verify(token)));assertThrows(RuntimeException.class,()->f.f.prepared.tx(this::capture));assertThrows(RuntimeException.class,()->f.f.results.readonly(this::capture));assertEquals(0,f.f.f.count("target_type='catalog_source_final_result'"));
 }
 @Test void actualZeroRequiresItsOriginalCollectionAndNoRoots()throws Exception{
  for(String category:List.of("agent","skill","workflow","model")){install(category,0,false);var token=capture();assertNotNull(token);assertEquals(0,f.f.f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals("complete",f.f.prepared.tx(()->service.verify(token)).result().coverageState());f.f.f.jdbc.update("DELETE FROM jobs WHERE id=?",f.f.prepared.collection.dispositionId());assertThrows(RuntimeException.class,this::capture);assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->service.verify(token)));}
 }
 @Test void actualTerminalFailureNeedsDurablePartialSealBeforeAnyFinalPermission()throws Exception{
  install("skill",2,false);String root=f.f.results.chunk.rootId();f.f.f.jdbc.update("UPDATE jobs SET state='failed',call_count=3 WHERE id=?",root);assertNull(f.service.stage(f.f.ref));f.f.partialArchive.stage(f.f.ref);assertNull(f.service.stage(f.f.ref));var seal=f.f.seals.seal(f.f.ref);assertNotNull(seal);staged=f.service.stage(f.f.ref);var token=capture();assertNotNull(token);assertEquals("catalog-source-final-result-v2",CanonicalJson.parse(token.proof().result().json()).path("schema_version").asText());assertEquals(1,CanonicalJson.parse(token.proof().result().json()).path("unapplied_member_count").asInt());assertEquals(token.proof(),f.f.prepared.tx(()->service.verify(token)));f.f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcSourceDedupPartialReceipts.receiptId(f.f.ref.ownerId()));assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->service.verify(token)));assertThrows(RuntimeException.class,this::capture);
 }
 @Test void originalBodyChangeWithUnchangedHashAndRootRevisionChangeInsideWriterRefuse(){
  var token=capture();String id=f.f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE purpose='result' AND owner_job_id=? AND part_no=1",String.class,f.f.ref.ownerId());String body=f.f.f.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE id=?",String.class,id);f.f.f.jdbc.update("UPDATE jobs SET payload_json='{\"fictional_changed\":true}' WHERE id=?",id);assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->service.verify(token)));assertThrows(RuntimeException.class,this::capture);f.f.f.jdbc.update("UPDATE jobs SET payload_json=? WHERE id=?",body,id);assertEquals(token.proof(),f.f.prepared.tx(()->service.verify(token)));
  assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->{f.f.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",f.f.results.chunk.rootId());return service.verify(token);}));assertEquals(token.proof(),f.f.prepared.tx(()->service.verify(token)));assertEquals(0,f.f.f.count("target_type='catalog_source_final_result'"));
 }
 @Test void actualIndependentCommitBetweenProofAndFinalCaptureIsRejected(){
  var spy=org.mockito.Mockito.spy(facts);int[] captures={0};org.mockito.Mockito.doAnswer(invocation->{if(++captures[0]==2){try(var connection=f.f.f.source.getConnection();var statement=connection.prepareStatement("UPDATE jobs SET revision=revision+1 WHERE id=?")){connection.setAutoCommit(true);statement.setString(1,f.f.results.chunk.rootId());assertEquals(1,statement.executeUpdate());}}return invocation.callRealMethod();}).when(spy).capture(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList());service=provider(spy);assertThrows(RuntimeException.class,this::capture);assertEquals(2,captures[0]);assertEquals(0,f.f.f.count("target_type='catalog_source_final_result'"));
 }
 @Test void nativeValidOtherCategoryAndExtraUnplannedRootCannotGrantAuthority(){
  var token=capture();assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->{f.f.f.jdbc.update("UPDATE jobs SET task_type='workflow_dedup' WHERE id=?",f.f.ref.ownerId());return service.verify(token);}));assertEquals(token.proof(),f.f.prepared.tx(()->service.verify(token)));var plan=f.f.results.plan;String root=JdbcFrozenInputs.stableId("fictional-unplanned-root",f.f.ref.ownerId());f.f.prepared.tx(()->new JdbcJobRecords(f.f.f.source).createRoot(new JdbcJobRecords.Root(root,f.f.ref.ownerId(),plan.logicalKey(),plan.generation(),99,"agent_dedup",plan.stage().priority()),f.f.f.clock.instant()));assertThrows(RuntimeException.class,this::capture);assertThrows(RuntimeException.class,()->f.f.prepared.tx(()->service.verify(token)));assertEquals(0,f.f.f.count("target_type='catalog_source_final_result'"));
 }
}
