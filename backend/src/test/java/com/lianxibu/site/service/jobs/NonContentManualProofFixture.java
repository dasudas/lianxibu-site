package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionSystemException;


/** Actual native generation/worker/result persistence with explicit fictional report-selection proof. */
class NonContentManualProofFixture extends OwnerResultComparisonFixture {
 @Override
 void setup(boolean mysql,boolean send){
  jdbc=new JdbcTemplate(source);
  if(!mysql){jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");}
  alter("worker_lock","slot_state VARCHAR(16) DEFAULT 'free',slot_revision BIGINT DEFAULT 0,fencing_token BIGINT DEFAULT 0,job_id VARCHAR(64),holder VARCHAR(64),lease_until "+time(mysql)+",request_deadline "+time(mysql)+",occupied_since "+time(mysql));
  alter("jobs","worker_instance_id VARCHAR(64),dispatch_token BIGINT,request_deadline "+time(mysql)+",send_authorized_at "+time(mysql)+",slot_released_at "+time(mysql)+",release_reason VARCHAR(2048),result_hash BINARY(32),result_json "+(mysql?"JSON":"CLOB")+",application_state VARCHAR(32)");
  jdbc.execute(mysql?CONTENT_DDL.replace("CLOB","JSON").replace("TIMESTAMP","DATETIME(6)")+" ENGINE=InnoDB":CONTENT_DDL);
  jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,effective_valid BOOLEAN NOT NULL)");jdbc.update("INSERT INTO article_revisions VALUES('fictional-citation',true)");
  if(!mysql){jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");}
  tx=new FaultManager(source);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx,()->25,n->{});
  var old=generation.command(false);var ctx=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AD_HOC_AI).replace("\"base_revision\":3","\"base_revision\":null")));
  var cmd=new Prepare("fictional-ad-hoc",old.generation(),Stage.AD_HOC_AI,old.businessDate(),old.sourceId(),old.dependencies(),old.evidence(),old.expectedDependencies(),old.explicitSupplement(),old.settingsHash(),ctx);
  var prepared=new GenerationJdbcStore(source,clock,"fictional-owner",ROOT,cmd);new GenerationFlowService(writes,prepared,(stage,snapshot,hash)->"{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional fixture\"}],\"response_format\":{\"type\":\"json_object\"},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",clock,()->true,reportInputs(),(root,c,cutoff,snapshot)->{
   var extension=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();
   return extension;
  }).prepare(cmd,action("fictional-prepare"));
  // The owned H2 fixture must bind the original native UTC header explicitly, like the MySQL UTC connection.
  if(!mysql)jdbc.update(connection->{var statement=connection.prepareStatement("UPDATE jobs SET input_cutoff_at=? WHERE id=?");statement.setTimestamp(1,java.sql.Timestamp.from(clock.instant()),java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));statement.setString(2,ROOT);return statement;});
  var workerState=new JdbcWorkerState(source);worker=new WorkerAuthorizationService(writes,new WorkerJdbcStore(workerState,new JdbcJobRecords(source),"fictional-epoch",()->false,id->true,clock),clock,()->true);
  var claim=worker.claim(action("fictional-claim"));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());permit=claim.permit();identity=permit.identity();if(send)assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(permit,request->{sends++;return "fictional response";}).state());
  jdbc.execute("ALTER TABLE jobs ADD COLUMN report_mode VARCHAR(32)");
  restart();
 }

 @Override String raw(){return ResultApplicationService.providerEnvelope(store.input(),"{\"kind\":\"ad_hoc\",\"title\":\"Fictional temporary answer\",\"sections\":[],\"citation_ids\":[\"fictional-citation\"]}");}
 ReportInputSnapshotService reportInputs(){return new ReportInputSnapshotService((command,cutoff)->{
  var candidates=new java.util.ArrayList<ReportInputSnapshotService.Candidate>();
  for(var evidence:command.evidence()){
   var metadata=new ReportInputSnapshotService.Metadata(new ReportInputSnapshotService.ResourceRef("source_run","fictional-source-run",null),null,null,evidence.bodyHash(),"fictional-source","fictional-source-run",null,evidence.businessDate(),"Fictional frozen summary","Fictional original summary",null);
   candidates.add(new ReportInputSnapshotService.Candidate(ReportInputSnapshotService.Group.SOURCE_SUMMARIES,metadata,evidence,"news_tech",false,false,null,null));
  }
  return new ReportInputSnapshotService.SourceProof(cutoff,command.businessDate(),command.businessDate().plusDays(1),true,candidates,java.util.List.of(),java.util.List.of(),java.util.List.of());
 });}
}
