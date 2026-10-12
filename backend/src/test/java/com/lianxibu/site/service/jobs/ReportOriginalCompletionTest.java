package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native full-profile report prepare/worker/APPLIED/ready/first. Source and model genesis are fictional. */
class ReportOriginalCompletionTest {
 static void h2Schema(ResultJdbcStoreTest f){var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:report-original-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL;INIT=SET TIME ZONE 'UTC'");f.source=ds;f.jdbc=new JdbcTemplate(ds);var base=new GenerationJdbcStoreTest();base.source=ds;base.jdbc=f.jdbc;base.schema();f.generation=base;f.setup(false);}
 static class NativeFixture extends ResultJdbcStoreTest {
  String logicalKeyOverride;
  @Override @BeforeEach void fixture(){utc(()->{h2Schema(this);return true;});}
 void setup(boolean mysql,boolean send){
  var originalZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));try{
  jdbc=new JdbcTemplate(source);
  if(!mysql){jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");}
  alter("worker_lock","slot_state VARCHAR(16) DEFAULT 'free',slot_revision BIGINT DEFAULT 0,fencing_token BIGINT DEFAULT 0,job_id VARCHAR(64),holder VARCHAR(64),lease_until "+time(mysql)+",request_deadline "+time(mysql)+",occupied_since "+time(mysql));
  alter("jobs","worker_instance_id VARCHAR(64),dispatch_token BIGINT,request_deadline "+time(mysql)+",send_authorized_at "+time(mysql)+",slot_released_at "+time(mysql)+",release_reason VARCHAR(2048),result_hash BINARY(32),result_json "+(mysql?"JSON":"CLOB")+",application_state VARCHAR(32)");
  jdbc.execute(mysql?CONTENT_DDL.replace("CLOB","JSON").replace("TIMESTAMP","DATETIME(6)")+" ENGINE=InnoDB":CONTENT_DDL);
  jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,effective_valid BOOLEAN NOT NULL)");jdbc.update("INSERT INTO article_revisions VALUES('fictional-citation',true)");
  tx=new FaultManager(source);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx,()->25,n->{});
  var old=generation.command(false);var ctx=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.DAILY_REPORT).replace("\"base_revision\":3","\"base_revision\":null")));
  var cmd=new Prepare(logicalKeyOverride==null?old.logicalKey():logicalKeyOverride,old.generation(),old.stage(),old.businessDate(),old.sourceId(),old.dependencies(),old.evidence(),old.expectedDependencies(),old.explicitSupplement(),old.settingsHash(),ctx);
  var reportInputs=new ReportInputSnapshotService((command,cutoff)->{
   String raw="{\"fictional_original_input\":\"Fictional input body\"}";String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(raw));
   var e=new GenerationFlowService.Evidence("fictional-report-evidence","source_analysis",command.businessDate(),cutoff.minusSeconds(1),hash,raw,true,false,"accepted",List.of());
   var m=new ReportInputSnapshotService.Metadata(new ReportInputSnapshotService.ResourceRef("source_analysis","fictional-source-result",null),1L,"fictional-version",hash,"fictional-source","fictional-run",null,command.businessDate(),"Fictional original source","Fictional original summary",null);
   var c=new ReportInputSnapshotService.Candidate(ReportInputSnapshotService.Group.SOURCE_SUMMARIES,m,e,"news_tech",true,true,null,null);
   return new ReportInputSnapshotService.SourceProof(cutoff,command.businessDate(),command.businessDate().plusDays(1),true,List.of(c),List.of(),List.of(),List.of());
  });
  var prepared=new GenerationJdbcStore(source,clock,"fictional-owner",ROOT,cmd);new GenerationFlowService(writes,prepared,(stage,snapshot,hash)->"{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional fixture\"}],\"response_format\":{\"type\":\"json_object\"},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",clock,()->true,reportInputs).prepare(cmd,action("fictional-prepare"));
  var workerState=new JdbcWorkerState(source);worker=new WorkerAuthorizationService(writes,new WorkerJdbcStore(workerState,new JdbcJobRecords(source),"fictional-epoch",()->false,id->true,clock),clock,()->true);
  var claim=worker.claim(action("fictional-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());permit=claim.permit();identity=permit.identity();if(send)assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(permit,request->{sends++;return "fictional response";}).state());
  restart();
  }finally{TimeZone.setDefault(originalZone);}
 }
 }
 ResultJdbcStoreTest fixture(){return new NativeFixture();}
 ResultJdbcStoreTest legacyFixture(){return new ResultJdbcStoreTest(){@Override void fixture(){utc(()->{h2Schema(this);return true;});}};}
 ReportResultDownstreamTest h;ReportOriginalCompletionService service;ContentVersionKey version;
 @BeforeEach void setup(){h=new ReportResultDownstreamTest(){@Override ResultJdbcStoreTest fixture(){return ReportOriginalCompletionTest.this.fixture();}};h.setup();service=new ReportOriginalCompletionService(h.f.source,h.f.tx);}
 static <T>T utc(java.util.function.Supplier<T> action){var original=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("UTC"));return action.get();}finally{TimeZone.setDefault(original);}}
 Instant now(){return h.f.clock.instant();}
 void apply(){assertEquals(ResultApplicationService.Status.APPLIED,utc(()->h.f.apply()).status());var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);version=tx.execute(s->new JdbcReportCompletionReceipts(h.f.source).read(ResultJdbcStoreTest.ROOT).version());}
 Instant observe(Instant time){return new VersionAvailabilityService(new FrozenContentVersionService(new JdbcFrozenContentVersions(h.f.source)),new JdbcVersionAvailability(h.f.source),h.f.writes,h.f.tx,Clock.fixed(time,ZoneOffset.UTC)).capture(version);}
 ReportOriginalCompletionService.Completed read(){return service.find(ResultJdbcStoreTest.ROOT,now());}
 @Test void ActualOriginalFullInputReportReadyAndFirstSupplyTheCompleteImmutableBody(){assertNull(read());apply();assertNull(read());observe(now());var value=read();assertEquals(version,value.ready().version());assertEquals(now(),value.availableAt());assertTrue(value.snapshotJson().contains("report-input-summary-v1"));assertTrue(value.snapshotJson().contains("Fictional input body"));assertEquals(CanonicalJson.parse(ResultJdbcStoreTest.BODY),CanonicalJson.parse(value.bodyJson()));assertTrue(value.originalByteSize()>1024);assertFalse(value.toString().contains("Fictional"));utc(()->h.f.apply());assertEquals(value,read());assertEquals(1,h.f.sends);}
 @Test void ChangingOrRemovingCurrentBodyCannotReplaceOriginalReportInput(){apply();observe(now());var old=read();h.f.jdbc.update("UPDATE contents SET revision=99,body_revision=98,body_json='{}',input_job_id='fictional-current-replacement'");assertEquals(old,read());h.f.jdbc.update("DELETE FROM contents");assertEquals(old,read());}
 @Test void MissingOrLateFirstDoesNotBorrowTheReadyOrRootAppliedTime(){apply();assertNull(read());assertEquals(now().plusSeconds(5),observe(now().plusSeconds(5)));assertNull(read());assertNotNull(service.find(ResultJdbcStoreTest.ROOT,now().plusSeconds(5)));h.f.jdbc.update("UPDATE jobs SET state='failed' WHERE target_type='content_availability'");assertThrows(RuntimeException.class,()->service.find(ResultJdbcStoreTest.ROOT,now().plusSeconds(5)));}
 @Test void OriginalInputResultAndSuccessfulRequestAreAllMandatory(){for(String field:List.of("input","retained","root","ready","applied","attempt","request")){setup();apply();observe(now());switch(field){case "input"->h.f.jdbc.update("DELETE FROM jobs WHERE purpose='input' AND part_no=0");case "retained"->h.f.jdbc.update("DELETE FROM jobs WHERE purpose='result' AND record_kind='snapshot_part'");case "root"->h.f.jdbc.update("UPDATE jobs SET application_state='DISCARDED' WHERE id=?",ResultJdbcStoreTest.ROOT);case "ready"->h.f.jdbc.update("UPDATE jobs SET action_payload_hash=UNHEX(?) WHERE target_type='report_result_ready'","0".repeat(64));case "applied"->h.f.jdbc.update("UPDATE jobs SET action_payload_hash=UNHEX(?) WHERE target_type='result_application'","0".repeat(64));case "attempt"->h.f.jdbc.update("UPDATE jobs SET state='failed' WHERE record_kind='ai_attempt'");default->h.f.jdbc.update("UPDATE jobs SET request_json=? WHERE record_kind='ai_attempt'","{\"input_hash\":\""+"f".repeat(64)+"\"}");}assertThrows(RuntimeException.class,this::read,field);}}
 @Test void LegacyReportWithoutOriginalCompleteProfileCannotBePromoted(){var legacy=new ReportResultDownstreamTest(){@Override ResultJdbcStoreTest fixture(){return ReportOriginalCompletionTest.this.legacyFixture();}};utc(()->{legacy.setup();return true;});assertEquals(ResultApplicationService.Status.APPLIED,utc(()->legacy.f.apply()).status());var store=new ReportOriginalCompletionService(legacy.f.source,legacy.f.tx);assertThrows(RuntimeException.class,()->store.find(ResultJdbcStoreTest.ROOT,legacy.f.clock.instant()));}
 @Test void CompleteOriginalByteBudgetAndOwnedOrBorrowedRrHaveNoFallback(){apply();observe(now());assertThrows(RuntimeException.class,()->new ReportOriginalCompletionService(h.f.source,h.f.tx,1024).find(ResultJdbcStoreTest.ROOT,now()));assertThrows(RuntimeException.class,()->new ReportOriginalCompletionService(h.f.source,h.f.tx,1023));assertThrows(RuntimeException.class,()->service.read(ResultJdbcStoreTest.ROOT,now()));var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->tx.execute(s->service.read(ResultJdbcStoreTest.ROOT,now())));assertThrows(RuntimeException.class,()->h.f.writes.execute(h.f.action("fictional-completion-writer"),key->null,c->service.find(ResultJdbcStoreTest.ROOT,now())));assertThrows(RuntimeException.class,()->service.find(ResultJdbcStoreTest.ROOT,now().plusNanos(1)));var source=mock(javax.sql.DataSource.class);new ReportOriginalCompletionService(source,mock(PlatformTransactionManager.class));verifyNoInteractions(source);}
}
