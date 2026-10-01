package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.content.ReportInputProfile;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fictional H2 atomicity proof; this is not the permanent report source/store. */
class ReportGenerationFlowTest {
 static final LocalDate DAY=LocalDate.of(2026,1,1);
 static final String HASH="a".repeat(64);
 JdbcTemplate jdbc;SharedWriteExecutor writes;GenerationFlowService service;
 Clock clock=Clock.fixed(Instant.parse("2026-01-01T21:00:00.123456789Z"),ZoneOffset.UTC);
 boolean allowed=true,complete=true,requestBad,saveBad,sourceBad,empty,noUpdate;
 int reads,requests;Stage requestedStage;Instant observedCutoff;
 GenerationFlowService.FrozenStore store;
 @BeforeEach void setup(){
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:report-flow-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
  jdbc.execute("CREATE TABLE fictional_freeze(action_key VARCHAR(128) PRIMARY KEY, snapshot_json CLOB, request_json CLOB, hash VARCHAR(64), state VARCHAR(32), coverage VARCHAR(16))");
  jdbc.execute("CREATE TABLE fictional_receipt(action_key VARCHAR(128) PRIMARY KEY)");
  jdbc.execute("CREATE TABLE fictional_source_read(action_key VARCHAR(32))");
  writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(ds),new DataSourceTransactionManager(ds),()->25,n->{});
  store=new GenerationFlowService.FrozenStore(){
   final Map<String,Frozen> committed=new HashMap<>();
   public Frozen committed(String key){return jdbc.queryForObject("SELECT COUNT(*) FROM fictional_receipt WHERE action_key=?",Integer.class,key)==0?null:committed.get(key);}
   public void save(Frozen f,String key){
    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
    jdbc.update("INSERT INTO fictional_freeze VALUES(?,?,?,?,?,?)",key,f.snapshotJson(),f.requestJson(),f.inputHash(),f.state(),f.coverageState());
    if(saveBad)throw new IllegalStateException("fictional persistence failure");
    jdbc.update("INSERT INTO fictional_receipt VALUES(?)",key);committed.put(key,f);
   }
  };
  service=flow(reportInputs());
 }
 ReportInputSnapshotService reportInputs(){return new ReportInputSnapshotService((c,t)->{
  reads++;observedCutoff=t;assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
  assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
  assertEquals(1,jdbc.queryForObject("SELECT id FROM worker_lock WHERE id=1",Integer.class));jdbc.update("INSERT INTO fictional_source_read VALUES('fictional')");
  if(sourceBad)throw new IllegalStateException("fictional source unavailable");
  LocalDate first=c.stage()==Stage.WEEKLY_REPORT?c.businessDate().minusDays(6):c.stage()==Stage.MONTHLY_REPORT?c.businessDate().withDayOfMonth(1):c.businessDate();
  var candidates=new ArrayList<Candidate>();
  if(!empty){var group=c.stage()==Stage.DAILY_REPORT?Group.SOURCE_SUMMARIES:Group.UPSTREAM_REPORTS;var ref=new ResourceRef(group==Group.UPSTREAM_REPORTS?"report":"source_analysis","fictional-source-result",null);
   var metadata=new Metadata(ref,1L,"fictional-version",HASH,"fictional-source","fictional-run",null,first,"Fictional title","Fictional summary",null);
   var evidence=new Evidence("fictional-input",group==Group.UPSTREAM_REPORTS?"daily_report":"analysis",first,t.minusSeconds(1),HASH,"{\"fictional_body\":true}",true,false,"accepted",List.of());
   candidates.add(new Candidate(group,metadata,evidence,group==Group.UPSTREAM_REPORTS?"daily":"source",true,true,null,null));}
  return new SourceProof(t,first,c.businessDate().plusDays(1),complete,candidates,List.of(),noUpdate?List.of(first):List.of(),List.of());
 });}
 GenerationFlowService flow(ReportInputSnapshotService provider){return new GenerationFlowService(writes,store,(stage,snapshot,hash)->{
  requests++;requestedStage=stage;assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertNotNull(ReportInputProfile.validated(CanonicalJson.parse(snapshot)));
  return requestBad?"{\"model\":\"fictional-model\"}":"{\"provider\":\"fictional-provider\",\"model\":\"fictional-model\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"fictional\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"fictional-v1\",\"prompt_version\":\"fictional-v1\"}";
 },clock,()->allowed,provider);}
 static Prepare command(Stage stage,LocalDate day,boolean explicit){return new Prepare("fictional-report-"+stage.name(),2,stage,day,null,List.of(),List.of(),List.of(),explicit,HASH);}
 static ActionContext action(String key){return new ActionContext("fictional-"+key,"fictional-owner-"+key,HASH);}
 int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

 @Test void sourceRequestAndSnapshotAreOneTransactionAndReplayDoesNotRebuild(){
  var c=command(Stage.DAILY_REPORT,DAY,false);var a=action("one");var f=service.prepare(c,a);
  assertEquals("queued",f.state());assertEquals(1,reads);assertEquals(1,requests);assertEquals(1,count("fictional_freeze"));assertEquals(1,count("fictional_receipt"));assertEquals(1,count("fictional_source_read"));
  var root=CanonicalJson.parse(f.snapshotJson());assertTrue(root.has(ReportInputProfile.FIELD));assertEquals(f.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,root));assertEquals(f.inputHash(),CanonicalJson.parse(f.requestJson()).get("input_hash").asText());
  assertEquals(f.snapshotJson(),jdbc.queryForObject("SELECT snapshot_json FROM fictional_freeze",String.class));assertEquals(f.requestJson(),jdbc.queryForObject("SELECT request_json FROM fictional_freeze",String.class));
  allowed=false;empty=true;assertEquals(f,service.prepare(c,a));assertEquals(1,reads);assertEquals(1,requests);
 }
 @Test void requestFailureAndPartialSaveRollbackAllRowsThenSameActionCanRetry(){
  var c=command(Stage.DAILY_REPORT,DAY,false);var a=action("rollback");requestBad=true;
  assertThrows(InputRejected.class,()->service.prepare(c,a));assertEquals(0,count("fictional_freeze"));assertEquals(0,count("fictional_receipt"));assertEquals(0,count("fictional_source_read"));
  requestBad=false;saveBad=true;assertThrows(IllegalStateException.class,()->service.prepare(c,a));assertEquals(0,count("fictional_freeze"));assertEquals(0,count("fictional_receipt"));assertEquals(0,count("fictional_source_read"));
  saveBad=false;assertEquals("queued",service.prepare(c,a).state());assertEquals(1,count("fictional_freeze"));assertEquals(1,count("fictional_receipt"));assertEquals(1,count("fictional_source_read"));
 }
 @Test void incompleteProofAndUnavailableSourceNeverFallBackToGenericCommandEvidence(){
  var c=command(Stage.DAILY_REPORT,DAY,false);complete=false;
  assertThrows(ProofUnavailable.class,()->service.prepare(c,action("incomplete")));assertEquals(0,requests);assertEquals(0,count("fictional_freeze"));assertEquals(0,count("fictional_source_read"));
  complete=true;sourceBad=true;assertThrows(IllegalStateException.class,()->service.prepare(c,action("source")));assertEquals(0,requests);assertEquals(0,count("fictional_freeze"));assertEquals(0,count("fictional_source_read"));
 }
 @Test void waitingAndCapacityDoNotReadOrPrepareWhileProvenEmptyStillPersistsProfile(){
  allowed=false;assertThrows(CapacityGuarded.class,()->service.prepare(command(Stage.DAILY_REPORT,DAY,false),action("guard")));assertEquals(0,reads);
  allowed=true;clock=Clock.fixed(Instant.parse("2026-01-01T20:59:59Z"),ZoneOffset.UTC);service=flow(reportInputs());assertEquals("waiting_input",service.prepare(command(Stage.DAILY_REPORT,DAY,false),action("wait")).state());assertEquals(0,reads);assertEquals(0,requests);
  clock=Clock.fixed(Instant.parse("2026-01-01T21:00:00Z"),ZoneOffset.UTC);service=flow(reportInputs());empty=true;var f=service.prepare(command(Stage.DAILY_REPORT,DAY,false),action("empty"));assertEquals("no_input",f.state());assertNotNull(f.snapshotJson());assertNotNull(f.inputHash());assertNull(f.requestJson());assertEquals(0,requests);assertEquals(0,CanonicalJson.parse(f.snapshotJson()).get(ReportInputProfile.FIELD).get("total").asInt());
  noUpdate=true;var coverage=service.prepare(command(Stage.DAILY_REPORT,DAY,false),action("coverage"));assertEquals("coverage_only",coverage.state());assertEquals("complete",coverage.coverageState());assertNotNull(coverage.snapshotJson());assertNull(coverage.requestJson());assertEquals(0,requests);
 }
 @Test void explicitSupplementUsesFixedMicrosecondCutoffAndAllThreeReportStagesUseProvider(){
  var daily=service.prepare(command(Stage.DAILY_REPORT,DAY,true),action("explicit"));assertEquals(Instant.parse("2026-01-01T21:00:00.123456Z"),daily.cutoff());assertEquals(daily.cutoff(),observedCutoff);
  for(Stage stage:List.of(Stage.WEEKLY_REPORT,Stage.MONTHLY_REPORT)){
   LocalDate end=stage==Stage.WEEKLY_REPORT?LocalDate.of(2026,1,4):LocalDate.of(2026,1,31);clock=Clock.fixed(end.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC);service=flow(reportInputs());
   var f=service.prepare(command(stage,end,false),action(stage.name()));assertEquals("queued",f.state());assertEquals(stage,requestedStage);assertTrue(CanonicalJson.parse(f.snapshotJson()).has(ReportInputProfile.FIELD));assertEquals(stage.name().toLowerCase().replace("_report",""),CanonicalJson.parse(f.snapshotJson()).get(ReportInputProfile.FIELD).get("report_type").asText());
  }
  assertEquals(3,reads);assertEquals(3,requests);
 }
 @Test void newProviderConstructorRejectsNullAndNestedWriterDoesNotRead(){
  assertThrows(NullPointerException.class,()->flow(null));var c=command(Stage.DAILY_REPORT,DAY,false);
  assertThrows(IllegalStateException.class,()->writes.execute(action("outer"),store::committed,ctx->service.prepare(c,action("inner"))));assertEquals(0,reads);assertEquals(0,count("fictional_freeze"));
 }
}
