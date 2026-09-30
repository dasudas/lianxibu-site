package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.identity.NodeTokens;
import com.lianxibu.site.repository.articles.JdbcArticleProjection;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.articles.ArticleProjectionService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "SITE_DB_URL=${B26_TEST_DB_URL:jdbc:h2:mem:submission-fixture}",
        "SITE_DB_USER=${B26_TEST_DB_USER:sa}","SITE_DB_PASSWORD=${B26_TEST_DB_PASSWORD:fixture}",
        "SITE_NODE_TOKEN_SECRET_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "SITE_RUN_LEASE_KEY_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "SITE_RUN_LEASE_KEY_ID=fixture","site.collector.enabled=true"})
@Import({SubmissionTimeHttpTest.ReviewedFixture.class,OperationalTestFixture.class})
@EnabledIfEnvironmentVariable(named="V10_GO_EXEC",matches=".+")
class SubmissionTimeHttpTest {
    private static final String TOKEN="A".repeat(43);
    private static String config(String source) {
        return "{\"url\": \"https://example.invalid/"+("global-hn".equals(source)?"hn":"feed")+"\"}";
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ReviewedFixture {
        @Bean @Primary CollectorReviewCatalog fictionalReview() {
            return new CollectorReviewCatalog(List.of("fixture:feed","global-hn").stream().map(source->
                    new ReviewedMapping(source,"article",SourceIdentityMappingService.sha256(config(source)),
                            "https://example.invalid/"+(source.equals("global-hn")?"hn":"feed"),"news_tech")).toList(),List.of(),List.of());
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager tx;
    @LocalServerPort int port;

    @Test void mixedFrozenWireSurvivesRestartLostAckAndMySqlTimeStorage() throws Exception {
        String db=System.getenv("B26_TEST_DB_URL");
        if(db==null || !db.startsWith("jdbc:mysql://127.0.0.1:13307/b26_fixture_http_")) {
            throw new IllegalStateException("isolated loopback MySQL required");
        }
        schema();
        for(String source:List.of("fixture:feed","global-hn")) {
            jdbc.update("INSERT INTO sources(id,source_kind,name,state,config_json,revision,config_revision,activation_stage,category)"
                    +" VALUES(?,'article','Fictional','enabled',?,1,1,'production','news_tech')",source,config(source));
            jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,window_end,config_version,"
                    +"config_snapshot_json,activation_stage,revision,delivery_state,accepted_count,excluded_count,pending_count)"
                    +" VALUES(?,?,'daily','2026-01-02',1,?,?,1,?,'production',0,'receiving',0,0,0)",
                    "v10-"+source,source,Timestamp.from(Instant.parse("2026-01-01T16:00:00Z")),
                    Timestamp.from(Instant.parse("2026-01-02T16:00:00Z")),
                    "{\"parser_version\":\"fixture-parser\",\"valid_until\":\"2027-01-01T00:00:00Z\"}");
        }
        jdbc.update("INSERT INTO collector_nodes VALUES(?,?,?,?,?,?,?,?)","fixture-node","Fictional",NodeTokens.digest(TOKEN),
                "[\"fixture:feed\",\"global-hn\"]",1,null,1,"[\"observation:write\"]");
        ProcessBuilder child=new ProcessBuilder(System.getenv("V10_GO_EXEC"),"test","./internal/outbox","-run","^TestSubmissionTimeJavaHTTP$","-count=1");
        child.directory(Path.of("../collector").toAbsolutePath().normalize().toFile());
        child.environment().put("V10_BASE_URL","https://127.0.0.1:"+port);
        child.environment().put("V10_NODE_TOKEN",TOKEN);
        child.environment().put("GOTOOLCHAIN","local");child.environment().put("GOPROXY","off");
        child.environment().put("GOROOT",Path.of(System.getenv("V10_GO_EXEC")).getParent().getParent().toString());
        Path log=Path.of("target/v10-go-child.log");child.redirectErrorStream(true);child.redirectOutput(log.toFile());
        Process process=child.start();
        if(!process.waitFor(50,TimeUnit.SECONDS)) {process.destroyForcibly();fail("interop timeout");}
        assertEquals(0,process.exitValue(),Files.readString(log));
        assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM articles",Long.class));
        assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions",Long.class));
        assertEquals(4L,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Long.class));
        assertEquals(3L,jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE source_id='global-hn' AND published_at IS NULL",Long.class));
        assertEquals(3L,jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions WHERE time_basis='submitted' AND published_at IS NULL AND updated_at IS NULL AND submitted_at=source_time",Long.class));
        assertEquals("outside_window",jdbc.queryForObject("SELECT exclusion_reason FROM source_run_items WHERE observation_id='v10-outside'",String.class));
        assertEquals("excluded",jdbc.queryForObject("SELECT activation_state FROM source_run_items WHERE observation_id='v10-outside'",String.class));
        assertEquals(512,jdbc.queryForObject("SELECT CHAR_LENGTH(title) FROM articles WHERE source_item_id='fictional-unicode'",Integer.class));
        assertEquals(50000,jdbc.queryForObject("SELECT CHAR_LENGTH(body_text) FROM articles WHERE source_item_id='fictional-unicode'",Integer.class));
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"),jdbc.queryForObject("SELECT published_at FROM articles WHERE source_id='fixture:feed'",Timestamp.class).toInstant());
        assertEquals(Instant.parse("2026-01-02T01:00:00Z"),jdbc.queryForObject("SELECT source_time FROM article_revisions WHERE time_basis='updated'",Timestamp.class).toInstant());
        String articleId=jdbc.queryForObject("SELECT id FROM articles WHERE source_item_id='fictional-hn'",String.class);
        var store=new JdbcArticleProjection(dataSource);
        var service=new ArticleProjectionService(store,List.of(),Clock.systemUTC());
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource),tx);
        var result=writes.execute(new ActionContext("manual-hn","manual-hn",null),ignored->null,ignored->{
            var current=store.current(articleId);
            return service.confirmPromote("fixture-owner","manual-hn",articleId,current.selectedRevisionId(),current.revision(),"reviewed fixture");
        });
        assertNull(jdbc.queryForObject("SELECT published_at FROM articles WHERE id=?",Timestamp.class,articleId));
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"),store.byId(result.currentRevisionId()).submittedAt());
    }

    private void schema() {
        ddl("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        ddl("CREATE TABLE collector_nodes(id VARCHAR(64) PRIMARY KEY,name VARCHAR(100),"
                + "token_hash BINARY(32),allowed_source_ids CLOB,enabled INT,last_seen_at TIMESTAMP,"
                + "config_revision BIGINT,permissions_json CLOB)");
        ddl("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),state VARCHAR(16),"
                + "config_json CLOB,draft_config_json CLOB,effective_config_json CLOB,revision BIGINT,"
                + "config_revision BIGINT,activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        ddl("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,revision BIGINT,"
                + "source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,"
                + "window_start TIMESTAMP,window_end TIMESTAMP,base_run_id VARCHAR(64),config_version BIGINT,"
                + "config_snapshot_json CLOB,activation_stage VARCHAR(16),fetch_mode VARCHAR(32),"
                + "collection_reason VARCHAR(32),target_version_json CLOB,delivery_state VARCHAR(32),"
                + "fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,excluded_count BIGINT,"
                + "pending_count BIGINT,coverage_json CLOB,job_refs_json CLOB,claim_node_id VARCHAR(64),"
                + "claim_token_hash BINARY(32),claim_token_ciphertext BLOB,claim_key_id VARCHAR(64),"
                + "lease_until TIMESTAMP,node_id VARCHAR(64),started_at TIMESTAMP,finished_at TIMESTAMP,"
                + "barrier_closed_at TIMESTAMP,"
                + "result_json CLOB,probe_trial_result_json CLOB,manifest_hash BINARY(32),part_count BIGINT,"
                + "expected_count BIGINT,UNIQUE(source_id,run_kind,business_date,generation))");
        ddl("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(191),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),source_id VARCHAR(80),"
                + "member_id VARCHAR(64),observation_id VARCHAR(64),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),ack_json CLOB,result_ref_json CLOB,created_at TIMESTAMP,"
                + "revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),"
                + "control_operation VARCHAR(32),UNIQUE(actor_id,scope,key_hash))");
        ddl("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,run_id VARCHAR(64),member_id VARCHAR(64),source_id VARCHAR(80),"
                + "source_item_id CLOB,source_item_hash BINARY(32),category VARCHAR(16),"
                + "observation_id VARCHAR(64),node_id VARCHAR(64),payload_hash BINARY(32),"
                + "replaces_observation_id VARCHAR(64),evidence_json CLOB,evidence_hash BINARY(32),"
                + "article_id VARCHAR(64),article_revision_id VARCHAR(64),part_no BIGINT,terminal_state VARCHAR(16),"
                + "receipt_ref VARCHAR(64),state VARCHAR(32),projection_decision VARCHAR(16),"
                + "source_order_json CLOB,validity_revision BIGINT,effective_valid INT,"
                + "exclusion_reason VARCHAR(128),activation_state VARCHAR(16),UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
        ddl("CREATE TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80) NOT NULL,"
                + "external_id VARCHAR(255) NOT NULL,external_key BINARY(32) NOT NULL,"
                + "title VARCHAR(512) NOT NULL,url VARCHAR(2000) NOT NULL,published_at TIMESTAMP,"
                + "summary CLOB,body_text CLOB,facts_json CLOB NOT NULL,content_hash BINARY(32),"
                + "revision BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,source_item_id CLOB,"
                + "source_item_hash BINARY(32),latest_revision_id VARCHAR(64),"
                + "projection_order_json CLOB,UNIQUE(source_id,external_key),"
                + "UNIQUE(source_id,source_item_hash))");
        ddl("CREATE TABLE article_revisions(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,article_id VARCHAR(64),revision_no BIGINT,content_hash BINARY(32),"
                + "title VARCHAR(512),source_summary CLOB,body_text CLOB,source_time TIMESTAMP,published_at TIMESTAMP,updated_at TIMESTAMP,submitted_at TIMESTAMP,"
                + "time_basis VARCHAR(32),content_status VARCHAR(32),body_truncated INT,"
                + "parser_version VARCHAR(64),config_version BIGINT,qualification_events_json CLOB,"
                + "source_order_json CLOB,legacy_flag INT,UNIQUE(article_id,revision_no))");

    }

    private void ddl(String statement) {
        for(String column:List.of("allowed_source_ids","permissions_json","config_json","draft_config_json",
                "effective_config_json","config_snapshot_json","target_version_json","coverage_json","job_refs_json",
                "result_json","probe_trial_result_json","response_json","ack_json","result_ref_json","evidence_json",
                "source_order_json","qualification_events_json","facts_json","projection_order_json")) {
            statement=statement.replace(column+" CLOB",column+" JSON");
        }
        jdbc.execute(statement.replace("CLOB","LONGTEXT").replace("TIMESTAMP","DATETIME(6)"));
    }
}
