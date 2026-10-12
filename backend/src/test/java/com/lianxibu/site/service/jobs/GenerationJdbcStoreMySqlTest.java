package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import java.net.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class GenerationJdbcStoreMySqlTest extends GenerationJdbcStoreTest {
 private static com.zaxxer.hikari.HikariDataSource isolatedPool;
 private static String isolatedUrl;
 private static final class OwnedTestPool extends com.zaxxer.hikari.HikariDataSource {
  OwnedTestPool(com.zaxxer.hikari.HikariConfig configuration){super(configuration);}
  // A test application context borrows this pool; the fixture owns its JVM lifetime.
  @Override public void close(){ }
  void closeOwned(){super.close();}
 }
 static synchronized com.zaxxer.hikari.HikariDataSource isolatedSource(String url){
  try{
   if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalStateException("isolated test database required");
   var endpoint=URI.create(url.substring(5));
   if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath())||endpoint.getUserInfo()!=null)throw new IllegalStateException("dedicated loopback schema required");
   if(isolatedPool!=null){if(!url.equals(isolatedUrl))throw new IllegalStateException("isolated test endpoint changed");return isolatedPool;}
   var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword("");
   config.setMaximumPoolSize(4);config.setMinimumIdle(1);config.setConnectionTimeout(5000);config.setValidationTimeout(1000);config.setMaxLifetime(0);config.setIdleTimeout(0);config.setPoolName("fictional-b45-tests");
   var owned=new OwnedTestPool(config);isolatedPool=owned;isolatedUrl=url;
   Runtime.getRuntime().addShutdownHook(new Thread(owned::closeOwned,"fictional-b45-test-pool-close"));return isolatedPool;
  }catch(IllegalArgumentException malformed){throw new IllegalStateException("dedicated loopback schema required");}
 }
 @Override @BeforeEach void fixture(){
  String url=System.getenv("B45_MYSQL_URL");if(url==null)throw new IllegalStateException("isolated test database required");var endpoint=URI.create(url.substring(5));
  if(!"mysql".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()!=13336||!"/fictional_b45_inputs".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback schema required");
  var ds=isolatedSource(url);source=ds;jdbc=new JdbcTemplate(ds);assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));jdbc.execute("DROP TABLE IF EXISTS jobs");jdbc.execute("DROP TABLE IF EXISTS worker_lock");schema();writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source),()->25,n->{});store=new GenerationJdbcStore(source,clock,"fictional-owner",OWNER,command(false));
 }
 @Override void schema(){jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY) ENGINE=InnoDB");jdbc.update("INSERT INTO worker_lock VALUES(1)");
jdbc.execute(mysqlDdl("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) NOT NULL UNIQUE,"
                + "task_kind VARCHAR(32) NOT NULL,target_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "priority_no INT NOT NULL,eligible_at TIMESTAMP NOT NULL,revision BIGINT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,record_kind VARCHAR(24) NOT NULL,"
                + "record_identity_hash BINARY(32) NOT NULL UNIQUE,call_count INT NOT NULL,"
                + "max_attempts INT,logical_key CLOB,logical_key_hash BINARY(32),generation BIGINT,"
                + "stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,supersedes_job_id VARCHAR(64),"
                + "input_state VARCHAR(16),root_job_id VARCHAR(64),attempt_no INT,request_json CLOB,input_snapshot_json CLOB,business_input_hash BINARY(32),coverage_state VARCHAR(16),missing_inputs_json CLOB,input_cutoff_at TIMESTAMP,dependency_id VARCHAR(64),"
                + "snapshot_owner_id VARCHAR(64),input_hash BINARY(32),owner_job_id VARCHAR(64),"
                + "purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_json CLOB,"
                + "payload_hash BINARY(32),action_key_hash BINARY(32) UNIQUE,target_type VARCHAR(32),"
                + "target_key_json CLOB,target_key_hash BINARY(32),operator_id VARCHAR(64),"
                + "reason VARCHAR(2048),acted_at TIMESTAMP,action_payload CLOB,"
                + "action_payload_hash BINARY(32),applied_at TIMESTAMP,provider_request_id VARCHAR(256),"
                + "usage_json CLOB,cost_decimal DECIMAL(20,8),"
                + "UNIQUE(root_job_id,attempt_no),UNIQUE(owner_job_id,purpose,stamp_id,part_no),"
                + "CHECK ((record_kind='logical_task' AND max_attempts=3 AND call_count<=3"
                + " AND priority_no BETWEEN 1 AND 14) OR (record_kind<>'logical_task'"
                + " AND max_attempts IS NULL AND call_count=0 AND priority_no=0)),"
                + "CHECK (record_kind<>'ai_attempt' OR (root_job_id IS NOT NULL AND attempt_no BETWEEN 1 AND 3"
                + " AND request_json IS NOT NULL AND snapshot_owner_id IS NOT NULL AND input_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'snapshot_part' OR (owner_job_id IS NOT NULL"
                + " AND purpose IS NOT NULL AND stamp_id IS NOT NULL AND part_no IS NOT NULL"
                + " AND payload_json IS NOT NULL AND payload_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'disposition' OR (action_key_hash IS NOT NULL"
                + " AND target_type IS NOT NULL AND target_key_json IS NOT NULL"
                + " AND target_key_hash IS NOT NULL AND operator_id IS NOT NULL"
                + " AND reason IS NOT NULL AND acted_at IS NOT NULL AND action_payload IS NOT NULL"
                + " AND action_payload_hash IS NOT NULL)))"));
 }
 String mysqlDdl(String ddl){return ddl.replace("TIMESTAMP","DATETIME(6)").replace("CLOB","JSON").replace("logical_key JSON","logical_key TEXT")+" ENGINE=InnoDB";}
 @Override void dropReceiptConstraint(){jdbc.execute("ALTER TABLE jobs DROP CHECK fictional_receipt_failure");}
}
