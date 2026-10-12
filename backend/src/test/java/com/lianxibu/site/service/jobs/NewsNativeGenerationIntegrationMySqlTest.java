package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual registered collector facts, P1/P2 claims, applications and final original proof.
 * Collector inputs, settings and model responses are fictional; no terminal result is seeded. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class NewsNativeGenerationIntegrationMySqlTest {
 SourceDayPartialPermitIntegrationMySqlTest day;SourceAnalysisParentStepTest f;GenerationJdbcStoreTest db;
 SourcePlanPreparationCoordinator preparation;SourceAnalysisParentStepService parent;
 ResultApplicationFactory factory;WorkerAuthorizationService worker;int sends;
 TimeZone previous;
 @BeforeEach void utc(){previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));}
 @AfterEach void restore(){TimeZone.setDefault(previous);}
 void columns(String table,String... definitions)throws Exception{
  var names=new HashSet<String>();try(var c=db.source.getConnection();var r=c.getMetaData().getColumns(c.getCatalog(),null,"%",null)){while(r.next())if(table.equalsIgnoreCase(r.getString("TABLE_NAME")))names.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
  for(String definition:definitions)if(!names.contains(definition.split(" ")[0]))db.jdbc.execute("ALTER TABLE "+table+" ADD "+definition);
 }
 void setup()throws Exception{
  day=new SourceDayPartialPermitIntegrationMySqlTest(){@Override boolean newPreparationAllowed(){return true;}};
  day.original=new SourceDedupResolvedStageResultTest(){
   @Override GenerationJdbcStoreTest database(){var result=new GenerationJdbcStoreMySqlTest();result.clock=Clock.fixed(day.p1At,ZoneOffset.UTC);return result;}
   @Override String runId(){return day.run();}
   @Override boolean initializeRegisteredRun(String id){day.register(id);return true;}
  };day.original.setup(1);f=day.original.f;db=f.db;WorkerCandidateSelectionScopeTest.installWorker(db);
  columns("jobs","report_mode VARCHAR(32)","validity_revision BIGINT","reverses_disposition_id VARCHAR(64)","error_code VARCHAR(64)");
  columns("analyzed_entities","created_at DATETIME(6)","last_seen_at DATETIME(6)","last_occurrence_at DATETIME(6)","occurrence_count BIGINT","repeat_count BIGINT","analysis_generation BIGINT","aliases_json TEXT");
  columns("source_run_items","counted_occurrence_key BINARY(32) UNIQUE","validity_revision BIGINT DEFAULT 0","is_new INT","dedup_json TEXT","analysis_job_id VARCHAR(64)","applied_at DATETIME(6)","high_value INT","value_json JSON");
  var configuration=new SourceDayNewsDedupConfiguration();day.barriers=configuration.createBarrier(db.source,day.manager,day.cohorts,configuration.createProgress(day.manager,day.runs,day.graph),f.clock);
  var admission=new SourceDayRunClaimAdmissionConfiguration().create(db.source,day.manager,new SourceDayCollectionAdmissionService(day.cohorts));
  day.permits=new SourceDayNewsValuePermitService(day.barriers,admission,new JdbcSourceDayDedupClosures(db.source),day.graph.dedupProofs(),day.graph.dedupObserver(),day.graph.dependencies(),day.graph.dedupPermits(),day.manager);
  var value=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(db.source),new JdbcFrozenSourceUniverses(db.source),day.graph.universes(),day.graph.plans(),day.graph.collections(),new NewsValueEligibilityService(new JdbcNewsDedupEligibility(db.source),new JdbcNewsValueEntities(db.source),f.p1.members,f.clock),day.graph.dependencies(),()->new FrozenGenerationSettings(1,List.of(),List.of()),f.clock,()->true,"2",day.permits);
  preparation=new SourcePlanPreparationCoordinator(f.p1.preparation,value,day.graph.parts(),day.graph.handoffs(),day.graph.dedupProofs(),new JdbcFrozenSourcePlans(db.source),db.writes,day.manager,f.clock,()->true,day.graph.dedupPermits(),day.permits);
  parent=NewsSourcePreparationConfiguration.parentStep(db.source,day.manager,f.clock,preparation,day.graph);
  var input=new JdbcFrozenInputs(db.source);var readiness=new DispatchReadinessService(new JdbcDispatchInputs(db.source),input,new FrozenDependencyIndexService(input,new JdbcFrozenDependencies(db.source)),new GenerationLineageService(new JdbcGenerationLineage(db.source),f.clock,"fictional-native-news"),mock(ContentReadService.class),f.p1.members,target->{throw new AssertionError("source-only input cannot reference generated content");},day.graph.collections()::read,new SourcePartitionProofService(day.graph.plans()),true,day.graph.dependencies(),day.graph.dedupPermits(),day.manager,day.permits);
  worker=new WorkerAuthorizationService(db.writes,new WorkerJdbcStore(new JdbcWorkerState(db.source),new JdbcJobRecords(db.source),"fictional-native-news",()->false,readiness,f.clock,day.manager),f.clock,n->0,()->true);
  var catalogs=new LinkedHashMap<String,CatalogDayAnalysisResultPermitService>();for(String category:List.of("agent","skill","workflow","model"))catalogs.put(category,mock(CatalogDayAnalysisResultPermitService.class));
  factory=new ResultApplicationFactory(db.source,day.manager,f.clock,day.graph.dependencies(),day.graph.dedupPermits(),catalogs,new JdbcEntityHeatSnapshots(db.source));sends=0;
  assertEquals(0L,db.jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Long.class));assertEquals(0,db.count("application_state='APPLIED'"));
 }
 record Sent(WorkerAuthorizationService.Identity identity,AttemptOutcomeService.Application application,String raw,String key){}
 Sent send(SourcePartitionPlanner.Chunk chunk,boolean dedup){
  String key=dedup?"fictional-native-news-p1":"fictional-native-news-p2";
  var claim=worker.claim(new SharedWriteExecutor.ActionContext(key+"-claim",chunk.rootId(),chunk.inputHash()));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());var identity=claim.permit().identity();assertEquals(chunk.rootId(),identity.rootId());
  var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(chunk.snapshotJson()).get(FrozenApplicationContext.FIELD));var input=context.input(identity.rootId(),identity.attemptId(),identity.inputHash(),new JdbcJobRecords(db.source).byId(identity.rootId()).revision());
  var payload=JsonMapper.shared().createObjectNode().put("kind",dedup?"dedup":"value").put("summary","Fictional native source evidence");var items=payload.putArray("items");
  if(dedup)for(String id:chunk.itemIds())items.addObject().put("item_id",id).put("category","news_tech").put("decision","new").putNull("candidate_entity_id").put("reason","Fictional original input").put("confidence","1").put("suggested_title","Fictional native NEWS").putNull("suggested_summary").put("reanalyze_recommended",false);
  else{payload.put("value_result_version","2");var selected=payload.putArray("selected_item_ids");for(String id:chunk.itemIds()){selected.add(id);items.addObject().put("item_id",id).put("category","news_tech").put("high_value",true).put("valuable",true).put("reason","Fictional selected source evidence");}}
  String raw=ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(payload));assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(identity.frozenRequestJson(),request);assertEquals(CanonicalJson.parse(chunk.requestJson()),CanonicalJson.parse(request));sends++;return raw;}).state());
  return new Sent(identity,factory.forAttempt(identity),raw,key);
 }
 ResultApplicationService.Result apply(Sent sent){return sent.application().service().applyAutomatic(sent.application().input(),sent.raw(),new SharedWriteExecutor.ActionContext(sent.key()+"-result",sent.identity().rootId(),sent.identity().inputHash()));}
 String finishP1(){var first=send(day.original.plan.chunks().getFirst(),true);assertEquals(ResultApplicationService.Status.APPLIED,apply(first).status());assertEquals("free",new JdbcWorkerState(db.source).slot().state());assertEquals(1L,db.jdbc.queryForObject("SELECT SUM(occurrence_count) FROM analyzed_entities",Long.class));assertEquals(1,db.count("target_type='entity_decision'"));assertEquals(SourceAnalysisParentStepService.Status.HANDED_OFF,parent.step(f.candidate(day.original.ref.ownerId())).status());return day.graph.handoffs().handoff(day.original.ref).p2Owner();}
 SourcePartitionPlanner.Plan prepareP2(String owner){assertEquals(SourceAnalysisParentStepService.Status.PREPARED,parent.step(f.candidate(owner)).status());return f.read(()->day.graph.plans().readImmutable(f.reference(owner)));}
 @Test void OriginalNonemptyP1P2ClaimsAndApplicationsProduceWholeNativeFinalExactlyOnce()throws Exception{
  setup();String owner=finishP1();var plan=prepareP2(owner);assertEquals(1,plan.chunks().size());var sent=send(plan.chunks().getFirst(),false);assertEquals(ResultApplicationService.Status.APPLIED,apply(sent).status());assertEquals(SourceAnalysisParentStepService.Status.FINALIZED,parent.step(f.candidate(owner)).status());var reference=f.reference(owner);var result=f.read(()->day.graph.finalization().readCommitted(reference));assertNotNull(result);assertEquals("complete",CanonicalJson.parse(result.result().json()).get("coverage_state").asText());assertNotNull(f.read(()->day.graph.finalObserver().availableAt(result,f.clock.instant())));
  assertEquals(2,sends);assertEquals(2,db.count("target_type='worker_send'"));assertEquals(2,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals("free",new JdbcWorkerState(db.source).slot().state());assertEquals(1L,db.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE high_value=1 AND value_json IS NOT NULL AND entity_id IS NOT NULL",Long.class));assertEquals(1L,db.jdbc.queryForObject("SELECT SUM(occurrence_count) FROM analyzed_entities",Long.class));
  int rows=db.count("1=1");assertEquals(ResultApplicationService.Status.APPLIED,apply(sent).status());assertEquals(SourceAnalysisParentStepService.Status.FINALIZED,parent.step(f.candidate(owner)).status());var replay=f.read(()->day.graph.finalization().readCommitted(reference));assertEquals(result.result().json(),replay.result().json());assertEquals(result.result().hash(),replay.result().hash());assertEquals(result.receipt().json(),replay.receipt().json());assertEquals(result.observationKeyJson(),replay.observationKeyJson());assertEquals(result.minimumObservationTime(),replay.minimumObservationTime());assertEquals(rows,db.count("1=1"));assertEquals(2,sends);
 }
 @Test void StaleOriginalP1MemberRollsBackWholeApplicationWithoutCreatingAnyEntityOrHandoff()throws Exception{
  setup();var sent=send(day.original.plan.chunks().getFirst(),true);db.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id=?",day.original.plan.chunks().getFirst().itemIds().getFirst());int rows=db.count("1=1");assertThrows(RuntimeException.class,()->apply(sent));assertNull(new JdbcResultState(db.source).root(sent.identity().rootId()).resultHash());assertEquals(0L,db.jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Long.class));assertEquals(0,db.count("target_type='source_member_stage'"));assertEquals(0,db.count("record_kind='logical_task' AND task_type='news_value'"));assertEquals(rows,db.count("1=1"));assertEquals(1,new JdbcJobRecords(db.source).byId(sent.identity().rootId()).callCount());assertEquals("occupied",new JdbcWorkerState(db.source).slot().state());assertEquals(1,sends);
 }
 @Test void LostOriginalRegisteredRunAfterP2PreparationCannotClaimOrSpendItsBudget()throws Exception{
  setup();String owner=finishP1();var plan=prepareP2(owner);db.jdbc.update("DELETE FROM request_receipts WHERE actor_id='source-day-register'");assertThrows(RuntimeException.class,()->send(plan.chunks().getFirst(),false));assertEquals(0,new JdbcJobRecords(db.source).byId(plan.chunks().getFirst().rootId()).callCount());assertEquals("free",new JdbcWorkerState(db.source).slot().state());assertEquals(1,sends);assertEquals(1,db.count("target_type='worker_send'"));
 }
}
