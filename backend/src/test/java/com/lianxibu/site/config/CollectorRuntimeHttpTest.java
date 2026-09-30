package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.identity.NodeTokens;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.CanonicalJson.Domain;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import com.lianxibu.site.service.sources.RunManifestService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SITE_DB_URL=${B26_TEST_DB_URL:jdbc:h2:mem:collector-runtime-http;MODE=MySQL;DB_CLOSE_DELAY=-1}",
        "SITE_DB_USER=${B26_TEST_DB_USER:sa}",
        "SITE_DB_PASSWORD=${B26_TEST_DB_PASSWORD:fixture}",
        "SITE_NODE_TOKEN_SECRET_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "SITE_RUN_LEASE_KEY_B64=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=",
        "SITE_RUN_LEASE_KEY_ID=fixture",
        "site.collector.enabled=true"
})
class CollectorRuntimeHttpTest {
    private static final String TOKEN = "A".repeat(43);
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRunLeaseService leases;
    @LocalServerPort int port;

    @Test void realHttpBeanChainPersistsLeaseAndRevocationFencesReplay() throws Exception {
        String isolatedMySql = System.getenv("B26_TEST_DB_URL");
        if (isolatedMySql != null
                && !isolatedMySql.startsWith("jdbc:mysql://127.0.0.1:13307/b26_fixture_http_")) {
            throw new IllegalStateException("B26 MySQL verification requires an isolated loopback fixture");
        }
        schema();
        String config = "{\"adapter\":\"rss\",\"entry_urls\":[\"https://example.invalid/feed\"],"
                + "\"identity_rule\":\"guid\",\"window_rule\":\"daily\",\"parser_version\":\"fixture-1\","
                + "\"max_items\":10,\"max_response_bytes\":1024,\"allowed_hosts\":[\"example.invalid\"],"
                + "\"valid_until\":\"2027-01-01T00:00:00.000000Z\",\"auth_secret_ref\":null}";
        jdbc.update("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", "fixture:feed", "rss", "Fixture",
                config, config, config, 1, 1, "production", Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                "news_tech");
        jdbc.update("INSERT INTO collector_nodes VALUES(?,?,?,?,?,?,?,?)", "fixture-node", "Fixture",
                NodeTokens.digest(TOKEN), "[\"fixture:feed\"]", 1, null, 1,
                "[\"config:read\",\"run:claim\",\"run:read\",\"run:write\",\"observation:write\","
                        + "\"probe:claim\",\"probe:write\"]");
        leases.scheduleDaily("fixture:feed", new ActionContext("schedule-http", "fixture-http-run", "fixture"));
        String root = "http://127.0.0.1:" + port + "/api/collector/v1";
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(root + "/config")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            var configResponse = client.send(HttpRequest.newBuilder(URI.create(root + "/config"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, configResponse.statusCode());
            assertEquals("fixture:feed", CanonicalJson.parse(configResponse.body()).get("data")
                    .get("sources").get(0).get("source_id").asText());
            String claimBody = "{\"source_ids\":[\"fixture:feed\"],\"required_features\":[],\"max_runs\":10}";
            var claimed = client.send(HttpRequest.newBuilder(URI.create(root + "/run-claims"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Idempotency-Key", "http-claim")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(claimBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, claimed.statusCode());
            JsonNode item = CanonicalJson.parse(claimed.body()).get("data").get("runs").get(0);
            assertEquals("fixture-http-run", item.get("run").get("run_id").asText());
            String claimToken = item.get("claim_token").asText();
            String ackBody = "{\"business\":{\"run_id\":\"fixture-http-run\",\"action\":\"ack\"},"
                    + "\"attempt\":{\"claim_token\":\"" + claimToken + "\",\"expected_run_revision\":null}}";
            var ack = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/claim"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-ack")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(ackBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ack.statusCode());
            assertEquals("receiving", CanonicalJson.parse(ack.body()).get("data").get("delivery_state").asText());
            assertEquals("receiving", jdbc.queryForObject("SELECT delivery_state FROM source_runs WHERE id=?",
                    String.class, "fixture-http-run"));
            String partHash = RunManifestService.partHash("fixture-http-run", 0, List.of());
            String partBody = "{\"business\":{\"run_id\":\"fixture-http-run\",\"part_no\":0,"
                    + "\"part_hash\":\"" + partHash + "\",\"members\":[]},"
                    + "\"attempt\":{\"claim_token\":\"" + claimToken + "\",\"expected_run_revision\":null}}";
            var part = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/manifest"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-part")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(partBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, part.statusCode());
            var manifest = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/manifest"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, manifest.statusCode());
            assertEquals(partHash, CanonicalJson.parse(manifest.body()).get("data").get("parts")
                    .get(0).get("part_hash").asText());
            String manifestHash = RunManifestService.manifestHash("fixture-http-run", 0,
                    List.of(new RunManifestService.Part("fixture-http-run", 0, partHash, List.of())));
            long runRevision = jdbc.queryForObject("SELECT revision FROM source_runs WHERE id=?",
                    Long.class, "fixture-http-run");
            String resultBody = "{\"business\":{\"run_id\":\"fixture-http-run\","
                    + "\"manifest_hash\":\"" + manifestHash + "\",\"part_count\":1,\"expected_count\":0,"
                    + "\"fetch_outcome\":\"succeeded\",\"coverage\":{\"state\":\"complete\","
                    + "\"missing_inputs\":[],\"missing_dates\":[],\"no_update_dates\":[]}},"
                    + "\"attempt\":{\"claim_token\":\"" + claimToken + "\",\"expected_run_revision\":"
                    + runRevision + "}}";
            var result = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/result"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-result")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(resultBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode());
            assertEquals("empty_success", CanonicalJson.parse(result.body()).get("data")
                    .get("delivery_state").asText());
            assertEquals("empty_success", jdbc.queryForObject("SELECT delivery_state FROM source_runs WHERE id=?",
                    String.class, "fixture-http-run"));
            jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,"
                            + "window_end,config_version,config_snapshot_json,activation_stage,revision,"
                            + "delivery_state,fetch_mode,collection_reason,fetch_state,analysis_state,accepted_count,"
                            + "excluded_count,pending_count,coverage_json,job_refs_json)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,0,'receiving','daily_delta','daily','running',"
                            + "'waiting_input',0,0,0,'{}','[]')",
                    "metric-run", "fixture:feed", "daily", java.sql.Date.valueOf("2026-09-27"), 1,
                    Timestamp.from(Instant.parse("2026-09-26T16:00:00Z")),
                    Timestamp.from(Instant.parse("2026-09-27T16:00:00Z")), 1, config, "production");
            JsonNode examples = CanonicalJson.parse(Files.readAllBytes(Path.of("../contracts/wire-examples.json")));
            ObjectNode metric = null;
            for (JsonNode sample : examples.get("cases")) {
                if ("ObservationEnvelope".equals(sample.get("schema").asText())
                        && sample.get("valid").booleanValue()
                        && "metric".equals(sample.get("value").get("kind").asText())) {
                    metric = ((ObjectNode) sample.get("value")).deepCopy();
                    break;
                }
            }
            assertNotNull(metric);
            metric.put("run_id", "metric-run");
            metric.put("source_id", "fixture:feed");
            ObjectNode unhashed = metric.deepCopy();
            unhashed.remove("payload_hash");
            metric.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, unhashed));
            String observationBody = "{\"observations\":[" + new String(CanonicalJson.bytes(metric),
                    StandardCharsets.UTF_8) + "]}";
            var observed = client.send(HttpRequest.newBuilder(URI.create(root + "/observations"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(observationBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, observed.statusCode());
            assertEquals("accepted", CanonicalJson.parse(observed.body()).get("data").get("acks")
                    .get(0).get("status").asText());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
            var replay = client.send(HttpRequest.newBuilder(URI.create(root + "/observations"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(observationBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("duplicate", CanonicalJson.parse(replay.body()).get("data").get("acks")
                    .get(0).get("status").asText());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
            ObjectNode article = null;
            for (JsonNode sample : examples.get("cases")) {
                if ("ObservationEnvelope".equals(sample.get("schema").asText())
                        && sample.get("valid").booleanValue()
                        && "article".equals(sample.get("value").get("kind").asText())) {
                    article = ((ObjectNode) sample.get("value")).deepCopy();
                    break;
                }
            }
            assertNotNull(article);
            article.put("observation_id", "unreviewed-article");
            article.put("member_id", "unreviewed-member");
            article.put("run_id", "metric-run");
            article.put("source_id", "fixture:feed");
            article.put("source_revision", 1);
            article.put("source_item_id", "unreviewed-item");
            article.withObject("payload").put("parser_version", "fixture-1");
            article.withObject("payload").put("config_version", 1);
            ObjectNode articleUnhashed = article.deepCopy();
            articleUnhashed.remove("payload_hash");
            article.put("payload_hash", CanonicalJson.hash(Domain.OBSERVATION, articleUnhashed));
            var unreviewed = client.send(HttpRequest.newBuilder(URI.create(root + "/observations"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"observations\":["
                            + new String(CanonicalJson.bytes(article), StandardCharsets.UTF_8) + "]}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, unreviewed.statusCode());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE observation_id=?",
                    Long.class, "unreviewed-article"));
            jdbc.update("UPDATE sources SET activation_stage='disabled',config_revision=2 WHERE id=?",
                    "fixture:feed");
            leases.scheduleProbe("fixture:feed", new ActionContext("schedule-probe-http",
                    "fixture-http-probe", "fixture"));
            var probeClaim = client.send(HttpRequest.newBuilder(URI.create(root + "/probe-claims"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-probe-claim")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"source_ids\":[\"fixture:feed\"],\"required_features\":[]}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, probeClaim.statusCode());
            String probeToken = CanonicalJson.parse(probeClaim.body()).get("data")
                    .get("claim_token").asText();
            String probeBody = "{\"business\":{\"probe_id\":\"fixture-http-probe\",\"result\":{"
                    + "\"reachable\":true,\"http_status\":200,\"tls_valid\":true,"
                    + "\"parser_valid\":true,\"identity_valid\":true,"
                    + "\"checked_at\":\"2026-09-30T00:00:00.000000Z\",\"error_code\":null,"
                    + "\"evidence_refs\":[]}},\"attempt\":{\"claim_token\":\"" + probeToken
                    + "\",\"expected_run_revision\":null}}";
            var probeResult = client.send(HttpRequest.newBuilder(URI.create(root + "/probes/fixture-http-probe/result"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-probe-result")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(probeBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, probeResult.statusCode());
            assertEquals("complete", CanonicalJson.parse(probeResult.body()).get("data")
                    .get("delivery_state").asText());
            var probeReplay = client.send(HttpRequest.newBuilder(URI.create(root + "/probes/fixture-http-probe/result"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-probe-result")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(probeBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(CanonicalJson.parse(probeResult.body()).get("data").get("receipt_ref").asText(),
                    CanonicalJson.parse(probeReplay.body()).get("data").get("receipt_ref").asText());
            assertNotNull(jdbc.queryForObject("SELECT probe_trial_result_json FROM source_runs WHERE id=?",
                    String.class, "fixture-http-probe"));
            jdbc.update("UPDATE collector_nodes SET token_hash=? WHERE id=?",
                    NodeTokens.digest("B".repeat(43)), "fixture-node");
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            jdbc.update("UPDATE collector_nodes SET token_hash=? WHERE id=?",
                    NodeTokens.digest(TOKEN), "fixture-node");
            jdbc.update("UPDATE collector_nodes SET enabled=0 WHERE id=?", "fixture-node");
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            jdbc.execute("DROP TABLE collector_nodes");
            assertEquals(503, client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    private void schema() {
        ddl("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        ddl("CREATE TABLE collector_nodes(id VARCHAR(64) PRIMARY KEY,name VARCHAR(100),"
                + "token_hash BINARY(32),allowed_source_ids CLOB,enabled INT,last_seen_at TIMESTAMP,"
                + "config_revision BIGINT,permissions_json CLOB)");
        ddl("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),"
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
                + "exclusion_reason VARCHAR(128),UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
    }

    private void ddl(String statement) {
        if (System.getenv("B26_TEST_DB_URL") != null) {
            for (String column : List.of("allowed_source_ids", "permissions_json", "config_json",
                    "draft_config_json", "effective_config_json", "config_snapshot_json",
                    "target_version_json", "coverage_json", "job_refs_json", "result_json",
                    "probe_trial_result_json", "response_json", "ack_json", "result_ref_json",
                    "evidence_json", "source_order_json")) {
                statement = statement.replace(column + " CLOB", column + " JSON");
            }
            statement = statement.replace("CLOB", "LONGTEXT");
        }
        jdbc.execute(statement);
    }
}
