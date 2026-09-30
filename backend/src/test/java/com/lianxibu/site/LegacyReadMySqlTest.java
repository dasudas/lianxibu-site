package com.lianxibu.site;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.articles.JdbcArticleReads;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.jobs.JdbcJobReads;
import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.repository.settings.JdbcSettingsReads;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Opt-in isolated MySQL verification; fixture values are fictional and never use live rows. */
@EnabledIfEnvironmentVariable(named = "V01_MYSQL", matches = "true")
class LegacyReadMySqlTest {
    @Test void currentReadersPreserveOldIdentityBodiesAndNoWriteCommands() throws Exception {
        String url = System.getenv("V01_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:")) {
            throw new IllegalStateException("V01 requires the isolated local MySQL endpoint");
        }
        URI endpoint = URI.create(url.substring(5));
        if (!"mysql".equals(endpoint.getScheme()) || endpoint.getPort() != 13307
                || endpoint.getHost() == null
                || !InetAddress.getByName(endpoint.getHost()).isLoopbackAddress()) {
            throw new IllegalStateException("V01 requires the isolated local MySQL endpoint");
        }
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("V01_MYSQL_USER", "root"),
                System.getenv().getOrDefault("V01_MYSQL_PASSWORD", ""))) {
            fixture(connection);
            var source = new SingleConnectionDataSource(connection, true);
            var contents = new JdbcContentReads(source);
            var learning = new JdbcLearningReads(source);
            var records = new JdbcRecordReads(source);
            var articles = new JdbcArticleReads(source);
            var sources = new JdbcSourceReads(source);
            var jobs = new JdbcJobReads(source);
            var settings = new JdbcSettingsReads(source);
            String before = identityDigest(connection);

            try (Statement sql = connection.createStatement()) {
                sql.execute("SET TRANSACTION READ ONLY");
                sql.execute("START TRANSACTION");
                Map<String, Long> commandsBefore = writeCommands(connection);
                var report = contents.current("report", "same");
                var agent = contents.current("agent", "same");
                assertEquals("report", report.kind());
                assertEquals("agent", agent.kind());
                assertEquals("legacy-report", report.naturalKey());
                assertNull(report.bodyRevision());
                assertNull(report.reviewHashV1());
                assertEquals(2L, report.effectiveBodyRevision());

                assertNotNull(learning.byId("pack-a", "question", "same"));
                assertNotNull(learning.byId("pack-b", "question", "same"));
                assertNull(learning.byId("pack-a", "question", "same").answerText());
                assertNull(learning.packageById("pack-draft"));
                assertEquals("note-a", records.current("alice", "question", "pack-a", "same").note());
                assertEquals("note-b", records.current("bob", "question", "pack-a", "same").note());
                assertNull(records.current("alice", "question", "", "same"));

                assertEquals("Independent summary", articles.article("fixture-article").summary());
                assertEquals("https://example.invalid/original", articles.article("fixture-article").sourceUrl());
                assertEquals("disabled", sources.byId("fixture-source").activationStage());
                assertNull(jobs.job("missing"));
                assertEquals("free", jobs.slot().state());
                assertNotNull(settings.setting("preferences"));
                assertFalse(settings.publishedPackage("pack-draft"));
                assertEquals(commandsBefore, writeCommands(connection));
                sql.execute("ROLLBACK");
            }
            assertEquals(before, identityDigest(connection));
        }
    }

    private static void fixture(Connection connection) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TEMPORARY TABLE contents(kind VARCHAR(16),id VARCHAR(64),"
                    + "natural_key VARCHAR(255),title VARCHAR(512),body_json JSON,state VARCHAR(16),"
                    + "revision BIGINT,body_revision BIGINT,published_at DATETIME(6),input_job_id VARCHAR(64),"
                    + "body_hash_v1 BINARY(32),review_hash_v1 BINARY(32),review_dependency_stamp JSON,"
                    + "review_stamp_hash BINARY(32),review_stamp_id VARCHAR(64),"
                    + "reviewed_input_hash BINARY(32),blocking_summary_json JSON,PRIMARY KEY(kind,id))");
            sql.execute("CREATE TEMPORARY TABLE learning_packages(id VARCHAR(64) PRIMARY KEY,"
                    + "title VARCHAR(200),state VARCHAR(16),revision BIGINT)");
            sql.execute("CREATE TEMPORARY TABLE learning_items(package_id VARCHAR(64),kind VARCHAR(16),"
                    + "id VARCHAR(64),title VARCHAR(200),position_no INT,body_json JSON,answer_text TEXT,"
                    + "answer_state VARCHAR(16),answer_generation_state VARCHAR(32),revision BIGINT,"
                    + "PRIMARY KEY(package_id,kind,id))");
            sql.execute("CREATE TEMPORARY TABLE user_records(user_id VARCHAR(64),object_kind VARCHAR(16),"
                    + "package_id VARCHAR(64),object_id VARCHAR(64),revision BIGINT,status VARCHAR(32),"
                    + "self_assessment VARCHAR(32),note TEXT,artifact_url VARCHAR(2000),response_text TEXT,"
                    + "confirmed_body_revision BIGINT,updated_at DATETIME(6),is_read BOOLEAN,"
                    + "is_favorite BOOLEAN,is_ignored BOOLEAN,"
                    + "PRIMARY KEY(user_id,object_kind,package_id,object_id))");
            sql.execute("CREATE TEMPORARY TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                    + "name VARCHAR(200),state VARCHAR(16),config_json JSON,draft_config_json JSON,"
                    + "effective_config_json JSON,revision BIGINT,config_revision BIGINT,"
                    + "activation_stage VARCHAR(16),config_valid_until DATETIME(6),category VARCHAR(16))");
            sql.execute("CREATE TEMPORARY TABLE articles(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),"
                    + "title VARCHAR(512),summary TEXT,url VARCHAR(2000),published_at DATETIME(6),"
                    + "updated_at DATETIME(6),revision BIGINT,body_text TEXT,source_item_id TEXT)");
            sql.execute("CREATE TEMPORARY TABLE jobs(id VARCHAR(64) PRIMARY KEY,record_kind VARCHAR(24),"
                    + "task_type VARCHAR(32),state VARCHAR(32),revision BIGINT,priority_no INT,"
                    + "eligible_at DATETIME(6),input_state VARCHAR(16),coverage_state VARCHAR(16),"
                    + "error_code VARCHAR(64),call_count INT,max_attempts INT,attempt_no INT,"
                    + "usage_json JSON,cost_decimal DECIMAL(20,8),slot_released_at DATETIME(6),"
                    + "release_reason VARCHAR(32),created_at DATETIME(6))");
            sql.execute("CREATE TEMPORARY TABLE worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(16),"
                    + "slot_revision BIGINT,job_id VARCHAR(64),lease_until DATETIME(6))");
            sql.execute("CREATE TEMPORARY TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,"
                    + "value_json JSON,revision BIGINT,updated_at DATETIME(6))");
            sql.execute("INSERT INTO contents(kind,id,natural_key,title,body_json,state,revision) VALUES "
                    + "('report','same','legacy-report','Fixture','{\"overview\":\"original\"}','draft',2),"
                    + "('agent','same','legacy-agent','Fixture','{\"name\":\"agent\"}','draft',1)");
            sql.execute("INSERT INTO learning_packages VALUES "
                    + "('pack-a','Fixture A','published',1),('pack-b','Fixture B','published',1),"
                    + "('pack-draft','Draft','draft',1)");
            sql.execute("INSERT INTO learning_items VALUES "
                    + "('pack-a','question','same','Question',1,'{}','hidden answer','draft',NULL,1),"
                    + "('pack-b','question','same','Question',1,'{}',NULL,'not_generated',NULL,1)");
            sql.execute("INSERT INTO user_records VALUES "
                    + "('alice','question','pack-a','same',1,'',NULL,'note-a',NULL,'answer-a',NULL,"
                    + "'2026-01-01 00:00:00',0,0,0),"
                    + "('bob','question','pack-a','same',1,'',NULL,'note-b',NULL,'answer-b',NULL,"
                    + "'2026-01-01 00:00:00',0,0,0)");
            sql.execute("INSERT INTO sources VALUES ('fixture-source','article','Fixture','enabled',"
                    + "'{\"url\":\"https://example.invalid/feed\"}',NULL,NULL,1,0,'disabled',NULL,NULL)");
            sql.execute("INSERT INTO articles VALUES ('fixture-article','fixture-source','Title',"
                    + "'Independent summary','https://example.invalid/original',NULL,"
                    + "'2026-01-01 00:00:00',1,'INTERNAL_BODY_SENTINEL',NULL)");
            sql.execute("INSERT INTO worker_lock VALUES (1,'free',0,NULL,NULL)");
            sql.execute("INSERT INTO runtime_settings VALUES ('preferences',"
                    + "'{\"pause_new_jobs\":0,\"watch_terms_raw\":\"fixture\"}',1,"
                    + "'2026-01-01 00:00:00')");
        }
    }

    private static String identityDigest(Connection connection) throws Exception {
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(
                "SELECT CONCAT((SELECT GROUP_CONCAT(CONCAT(kind,':',id,':',natural_key,':',"
                        + "SHA2(CAST(body_json AS CHAR),256)) ORDER BY kind,id SEPARATOR '|') FROM contents),"
                        + "'#',(SELECT GROUP_CONCAT(CONCAT(package_id,':',kind,':',id,':',"
                        + "SHA2(CAST(body_json AS CHAR),256)) ORDER BY package_id,kind,id SEPARATOR '|') "
                        + "FROM learning_items),'#',(SELECT GROUP_CONCAT(CONCAT(user_id,':',"
                        + "package_id,':',object_id,':',note) ORDER BY user_id SEPARATOR '|') "
                        + "FROM user_records),'#',(SELECT GROUP_CONCAT(CONCAT(id,':',"
                        + "IFNULL(source_item_id,'NULL')) SEPARATOR '|') FROM articles))")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static Map<String, Long> writeCommands(Connection connection) throws Exception {
        var result = new java.util.LinkedHashMap<String, Long>();
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(
                "SHOW SESSION STATUS WHERE Variable_name IN ('Com_insert','Com_update','Com_delete')")) {
            while (rs.next()) result.put(rs.getString(1), rs.getLong(2));
        }
        return result;
    }
}
