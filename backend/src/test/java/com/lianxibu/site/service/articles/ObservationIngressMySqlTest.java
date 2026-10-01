package com.lianxibu.site.service.articles;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import java.net.InetAddress;import java.net.URI;import java.time.*;import java.util.*;
import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.node.ObjectNode;
/** Real JDBC observation races in one dedicated disposable loopback database. */
@EnabledIfEnvironmentVariable(named="V03_MYSQL",matches="true")
class ObservationIngressMySqlTest {
 private DataSource db;private JdbcTemplate jdbc;private ObservationIngressService service;
 private final AtomicReference<Wave> holding=new AtomicReference<>();
 private final MutableClock time=new MutableClock();private final TestNodes nodes=new TestNodes();
 private static final List<String> TABLES=List.of("request_receipts","source_run_items","article_revisions","articles","source_runs","sources","worker_lock");
 @BeforeEach void fixture() throws Exception {
  String url=System.getenv("V13_MYSQL_URL");if(url==null||!url.startsWith("jdbc:"))throw new IllegalStateException("isolated database required");
  URI endpoint=URI.create(url.substring(5));if(!"mysql".equals(endpoint.getScheme())||endpoint.getPort()!=13336||endpoint.getHost()==null||!InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()||!"/fictional_v03_observations".equals(endpoint.getPath()))throw new IllegalStateException("dedicated loopback database required");
  db=new DriverManagerDataSource(url,System.getenv().getOrDefault("V03_MYSQL_USER","root"),System.getenv().getOrDefault("V03_MYSQL_PASSWORD",""));jdbc=new JdbcTemplate(db);
  assertEquals("8.0.36",jdbc.queryForObject("SELECT VERSION()",String.class));assertEquals("utf8mb4_0900_ai_ci",jdbc.queryForObject("SELECT @@collation_database",String.class));assertEquals("READ-COMMITTED",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
  for(String table:TABLES)jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),state VARCHAR(16),config_json JSON,draft_config_json JSON,"
                + "effective_config_json JSON,revision BIGINT,config_revision BIGINT,"
                + "activation_stage VARCHAR(16),config_valid_until DATETIME(6),category VARCHAR(16))");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),"
                + "run_kind VARCHAR(32),business_date DATE,generation BIGINT,window_start DATETIME(6),"
                + "window_end DATETIME(6),config_version BIGINT,config_snapshot_json JSON,"
                + "activation_stage VARCHAR(16),revision BIGINT,delivery_state VARCHAR(32),"
                + "claim_node_id VARCHAR(64),claim_token_hash BINARY(32),"
                + "claim_token_ciphertext BLOB,claim_key_id VARCHAR(64),lease_until DATETIME(6),"
                + "manifest_hash BINARY(32),part_count BIGINT)");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json JSON,"
                + "expires_at DATETIME(6),id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "source_id VARCHAR(80),scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),"
                + "member_id VARCHAR(64),observation_id VARCHAR(64),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),ack_json JSON,created_at DATETIME(6),revision BIGINT,"
                + "PRIMARY KEY(principal,operation,request_key),UNIQUE(actor_id,scope,key_hash))");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,created_at DATETIME(6),"
                + "revision BIGINT,run_id VARCHAR(64),member_id VARCHAR(64),source_id VARCHAR(80),"
                + "source_item_id LONGTEXT,source_item_hash BINARY(32),category VARCHAR(16),"
                + "observation_id VARCHAR(64),node_id VARCHAR(64),payload_hash BINARY(32),"
                + "replaces_observation_id VARCHAR(64),evidence_json JSON,evidence_hash BINARY(32),"
                + "article_id VARCHAR(64),article_revision_id VARCHAR(64),part_no BIGINT,terminal_state VARCHAR(16),"
                + "receipt_ref VARCHAR(64),state VARCHAR(32),projection_decision VARCHAR(16),"
                + "source_order_json JSON,validity_revision BIGINT,effective_valid INT,"
                + "exclusion_reason VARCHAR(128),activation_state VARCHAR(16),"
                + "UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
        jdbc.execute("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80) NOT NULL,"
                + "external_id VARCHAR(255) NOT NULL,external_key BINARY(32) NOT NULL,"
                + "title VARCHAR(512) NOT NULL,url VARCHAR(2000) NOT NULL,published_at DATETIME(6),"
                + "summary LONGTEXT,body_text LONGTEXT,facts_json JSON NOT NULL,content_hash BINARY(32),"
                + "revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL,source_item_id LONGTEXT,"
                + "source_item_hash BINARY(32),latest_revision_id VARCHAR(64),"
                + "projection_order_json JSON,UNIQUE(source_id,external_key),"
                + "UNIQUE(source_id,source_item_hash))");
        jdbc.execute("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,created_at DATETIME(6),"
                + "revision BIGINT,article_id VARCHAR(64),revision_no BIGINT,content_hash BINARY(32),"
                + "title VARCHAR(512),source_summary LONGTEXT,body_text LONGTEXT,source_time DATETIME(6),published_at DATETIME(6),updated_at DATETIME(6),submitted_at DATETIME(6),"
                + "time_basis VARCHAR(32),content_status VARCHAR(32),body_truncated INT,"
                + "parser_version VARCHAR(64),config_version BIGINT,qualification_events_json JSON,"
                + "source_order_json JSON,legacy_flag INT,UNIQUE(article_id,revision_no))");
  reset();
  var manager=new HoldingCommitManager(db,holding);
  var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(db),manager,()->25,ignored->{});
  var tx=new DataSourceTransactionManager(db);
  String storedConfig=jdbc.queryForObject("SELECT config_json FROM sources WHERE id='fixture:feed'",String.class);
  var identity=new SourceIdentityMappingService(new JdbcSourceReads(db),tx,List.of(new ReviewedMapping("fixture:feed","article",SourceIdentityMappingService.sha256(storedConfig),"https://feeds.example.invalid/news","article")));
  var writer=new ArticleCompatibilityWriter(new JdbcArticleWrites(db),identity,tx,time,List.of());var runs=new JdbcSourceRunStore(db);
  var projection=new ArticleProjectionService(new JdbcArticleProjection(db),List.of(new ArticleProjectionService.TrustedOrderRule("fixture:feed","fixture-parser","revision_time","fixture-domain")),time);
  service=new ObservationIngressService(new JdbcObservationStore(db),id->{var row=runs.locked(id);return row==null?null:new ObservationIngressService.RunSnapshot(row.id(),row.sourceId(),row.businessDate(),row.configVersion(),row.configSnapshotJson(),row.activationStage(),row.deliveryState(),row.windowStart(),row.windowEnd());},writes,nodes,(input,day)->{var compatible=writer.accept(input);var p=projection.decide(input,compatible,day);return new ObservationIngressService.ArticleOutcome(p.articleId(),p.evidenceRevisionId(),p.projectionDecision(),p.articleRevision());},time,()->true);
 }
 private void reset(){holding.set(null);nodes.revoked=false;time.now=Instant.parse("2026-01-03T00:00:00Z");for(String table:TABLES)jdbc.update("DELETE FROM "+table);jdbc.update("INSERT INTO worker_lock VALUES(1)");
  jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,revision,config_revision,activation_stage,category) VALUES(?,?,?,?,?,?,?,?,?)","fixture:feed","article","Fictional","enabled","{\"url\":\"https://feeds.example.invalid/news\"}",1,1,"production","article");
  jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,window_end,config_version,config_snapshot_json,activation_stage,revision,delivery_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)","run-fixture","fixture:feed","daily",java.sql.Date.valueOf("2026-01-02"),1,java.sql.Timestamp.from(Instant.parse("2026-01-01T16:00:00Z")),java.sql.Timestamp.from(Instant.parse("2026-01-02T16:00:00Z")),1,"{\"parser_version\":\"fixture-parser\",\"valid_until\":\"2027-01-01T00:00:00Z\"}","production",0,"receiving");
 }
    private byte[] article(String observationId, String title, String runId, String memberId) {
        return article(observationId, title, runId, memberId, null);
    }
    private byte[] article(String observationId, String title, String runId, String memberId,
                           String replaces) {
        ObjectNode root = JsonMapper.shared().createObjectNode();
        root.put("protocol_revision", "0.11");
        root.put("observation_id", observationId);
        root.put("member_id", memberId);
        root.put("run_id", runId);
        root.put("business_date", "2026-01-02");
        root.put("source_id", "fixture:feed");
        root.put("source_revision", 1);
        root.put("source_item_id", "fixture-item");
        root.put("kind", "article");
        root.put("retrieved_at", "2026-01-02T00:00:00.000000Z");
        if (replaces == null) root.putNull("replaces_observation_id");
        else root.put("replaces_observation_id", replaces);
        ObjectNode payload = root.putObject("payload");
        payload.put("title", title);
        payload.put("source_url", "https://example.invalid/article");
        payload.putNull("published_at");
        payload.putNull("updated_at");
        payload.put("time_basis", "unknown");
        payload.putNull("source_summary");
        payload.put("body_text", "Fixture body");
        payload.put("content_status", "full");
        payload.put("body_truncated", false);
        payload.put("parser_version", "fixture-parser");
        payload.put("config_version", 1);
        ObjectNode order = payload.putObject("source_order");
        order.put("basis", "unknown");
        order.putNull("domain");
        order.putNull("value");
        order.putNull("evidence_ref");
        root.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, root));
        return CanonicalJson.bytes(root);
    }
    private byte[] orderedArticle(String observationId, String title, String runId,
                                  String memberId, String replaces, String sourceTime) {
        ObjectNode root = ((ObjectNode) CanonicalJson.parse(
                article(observationId, title, runId, memberId, replaces))).deepCopy();
        ObjectNode order = (ObjectNode) root.get("payload").get("source_order");
        order.put("basis", "revision_time");
        order.put("domain", "fixture-domain");
        order.put("value", sourceTime);
        order.put("evidence_ref", "fixture-proof");
        root.remove("payload_hash");
        root.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, root));
        return CanonicalJson.bytes(root);
    }
 @Test void committedOrRolledBackFirstUploadLeavesExactlyOneRevisionMemberAndReceiptFor100Seeds() throws Exception {
  for(int seed=0;seed<100;seed++){
   reset();final String preferred=seed%2==0?"fictional-a":"fictional-b",other=seed%2==0?"fictional-b":"fictional-a";
   var wave=new Wave(preferred,seed%4>=2);holding.set(wave);String id="obs-seed-"+seed;
   byte[] raw=orderedArticle(id,"Fictional stable title","run-fixture","member-fixture",null,"2026-01-02T00:00:00.000000Z");
   try(var pool=Executors.newFixedThreadPool(2)){
    var first=pool.submit(()->{Thread.currentThread().setName(preferred);return service.accept("node-fixture",raw,action(id));});
    assertTrue(wave.ready.await(5,TimeUnit.SECONDS),"first commit barrier seed "+seed);
    var second=pool.submit(()->{Thread.currentThread().setName(other);return service.accept("node-fixture",raw,action(id));});
    try{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800);boolean waiting=false;
     while(System.nanoTime()<deadline){if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE r.OBJECT_SCHEMA='fictional_v03_observations' AND r.OBJECT_NAME='worker_lock'",Integer.class)>0){waiting=true;break;}Thread.sleep(5);}
     assertTrue(waiting,"actual InnoDB writer wait seed "+seed);
    }finally{wave.release.countDown();}
    if(wave.rollback){var failure=assertThrows(ExecutionException.class,()->first.get(5,TimeUnit.SECONDS));assertInstanceOf(InjectedRollback.class,failure.getCause());assertEquals("accepted",second.get(5,TimeUnit.SECONDS).status());}
    else{var a=first.get(5,TimeUnit.SECONDS);var b=second.get(5,TimeUnit.SECONDS);assertEquals("accepted",a.status());assertEquals("duplicate",b.status());assertEquals(a.receiptRef(),b.receiptRef());assertEquals(a.acceptedAt(),b.acceptedAt());}
   }catch(Throwable failure){throw new AssertionError("V13 seed="+seed+", first="+preferred+", rollback="+wave.rollback,failure);}
   finally{wave.release.countDown();holding.set(null);}
   for(String table:List.of("articles","article_revisions","source_run_items","request_receipts"))assertEquals(1L,count(table),"seed "+seed+" "+table);
   byte[] originalHash=jdbc.queryForObject("SELECT request_hash FROM request_receipts",byte[].class);
   assertThrows(ObservationIngressService.IdentityConflict.class,()->service.accept("node-fixture",orderedArticle(id,"Fictional changed title","run-fixture","member-fixture",null,"2026-01-02T00:00:00.000000Z"),action(id)));
   assertArrayEquals(originalHash,jdbc.queryForObject("SELECT request_hash FROM request_receipts",byte[].class));
   assertEquals("Fictional stable title",jdbc.queryForObject("SELECT title FROM articles",String.class));
   assertEquals(1L,count("article_revisions"));
  }
 }
 @Test void terminalRunAndNinetyDayClockKeepOriginalAckButRevocationStillWins(){
  byte[] raw=orderedArticle("obs-long-lived","Fictional permanent ACK","run-fixture","member-fixture",null,"2026-01-02T00:00:00.000000Z");
  var ack=service.accept("node-fixture",raw,action("obs-long-lived"));assertEquals("accepted",ack.status());
  time.now=time.now.plus(Duration.ofDays(95));jdbc.update("UPDATE source_runs SET delivery_state='complete'");
  assertNull(jdbc.queryForObject("SELECT expires_at FROM request_receipts",java.sql.Timestamp.class));
  var replay=service.accept("node-fixture",raw,action("obs-long-lived"));assertEquals("duplicate",replay.status());assertEquals(ack.receiptRef(),replay.receiptRef());assertEquals(ack.acceptedAt(),replay.acceptedAt());
  nodes.revoked=true;assertThrows(SecurityException.class,()->service.accept("node-fixture",raw,action("obs-long-lived")));
  nodes.revoked=false;var unknown=service.accept("node-fixture",orderedArticle("obs-new-after-close","Fictional unknown","run-fixture","member-new",null,"2026-01-02T00:00:00.000000Z"),action("obs-new-after-close"));assertEquals("rejected",unknown.status());assertEquals("run_closed_unknown_observation",unknown.code());assertEquals(1L,count("source_run_items"));assertEquals(1L,count("article_revisions"));
 }
 private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 private static ActionContext action(String id){return new ActionContext(id,"receipt-"+id,null);}
 private static final class Wave{final String first;final boolean rollback;final CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);Wave(String first,boolean rollback){this.first=first;this.rollback=rollback;}}
 private static final class InjectedRollback extends RuntimeException{}
 private static final class HoldingCommitManager extends DataSourceTransactionManager{
  final AtomicReference<Wave> holding;HoldingCommitManager(DataSource source,AtomicReference<Wave> holding){super(source);this.holding=holding;}
  @Override protected void doCommit(DefaultTransactionStatus status){var wave=holding.get();if(wave!=null&&!status.isReadOnly()&&Thread.currentThread().getName().equals(wave.first)){wave.ready.countDown();try{if(!wave.release.await(5,TimeUnit.SECONDS))throw new AssertionError("commit barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}if(wave.rollback){super.doRollback(status);throw new InjectedRollback();}}super.doCommit(status);}
 }
 private static final class TestNodes implements ObservationIngressService.NodeAuthorizer{boolean revoked;public Principal authorize(String bearer,String source){if(revoked||!"node-fixture".equals(bearer)||!"fixture:feed".equals(source))throw new SecurityException("fictional revoked node");return new Principal("node-fixture",List.of(source),List.of("observation:write"),1);}}
 private static final class MutableClock extends Clock{volatile Instant now;public ZoneId getZone(){return ZoneId.of("UTC");}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
}
