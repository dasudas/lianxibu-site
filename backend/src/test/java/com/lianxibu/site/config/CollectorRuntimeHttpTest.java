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
import java.util.concurrent.TimeUnit;
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
@org.springframework.context.annotation.Import({OperationalTestFixture.class,V3LeaseHttpFixture.class})
class CollectorRuntimeHttpTest {
    private static final String TOKEN = "A".repeat(43);
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRunLeaseService leases;
    @LocalServerPort int port;

    @Autowired V3LeaseHttpFixture.FixtureClock v3Clock;
    private Path v3Queue;

    @Test void realHttpBeanChainPersistsLeaseAndRevocationFencesReplay() throws Exception {
        String isolatedMySql = System.getenv("B26_TEST_DB_URL");
        if (isolatedMySql != null
                && !isolatedMySql.startsWith("jdbc:mysql://127.0.0.1:13307/b26_fixture_http_")) {
            throw new IllegalStateException("B26 MySQL verification requires an isolated loopback fixture");
        }
        schema();
        // Production daily scheduling is intentionally closed before 00:10.
        // Freeze a valid fixture instant so this HTTP test can run across midnight.
        Instant fixtureNow = Instant.now();
        if (java.time.LocalTime.ofInstant(fixtureNow, java.time.ZoneId.of("Asia/Shanghai"))
                .isBefore(java.time.LocalTime.of(0, 10))) fixtureNow = fixtureNow.plusSeconds(600);
        v3Clock.freeze(fixtureNow);
        String config = "{\"adapter\":\"rss\",\"entry_urls\":[\"https://example.invalid/feed\"],"
                + "\"identity_rule\":\"guid\",\"window_rule\":\"daily\",\"parser_version\":\"fixture-1\","
                + "\"max_items\":10,\"max_response_bytes\":1024,\"allowed_hosts\":[\"example.invalid\"],"
                + "\"valid_until\":\"2027-01-01T00:00:00.000000Z\",\"auth_secret_ref\":null}";
        utcUpdate("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", "fixture:feed", "rss", "Fixture",
                config, config, config, 1, 1, "production", Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                "news_tech");
        utcUpdate("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", "fixture:go", "rss", "Go Fixture",
                config, config, config, 1, 1, "production", Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                "news_tech");
        utcUpdate("INSERT INTO collector_nodes VALUES(?,?,?,?,?,?,?,?)", "fixture-node", "Fixture",
                NodeTokens.digest(TOKEN), "[\"fixture:feed\",\"fixture:go\"]", 1, null, 1,
                "[\"config:read\",\"run:claim\",\"run:read\",\"run:write\",\"observation:write\","
                        + "\"probe:claim\",\"probe:write\"]");
        leases.scheduleDaily("fixture:feed", new ActionContext("schedule-http", "fixture-http-run", "fixture"));
        leases.scheduleDaily("fixture:go", new ActionContext("schedule-go-http", "fixture-go-run", "fixture"));
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
            jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_handoff_failure CHECK(record_kind<>'disposition')");
            var rejectedHandoff = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/result"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-result")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(resultBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejectedHandoff.statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class));
            assertEquals(runRevision, jdbc.queryForObject("SELECT revision FROM source_runs WHERE id='fixture-http-run'", Long.class));
            jdbc.execute("ALTER TABLE jobs DROP " + (isolatedMySql == null ? "CONSTRAINT" : "CHECK") + " fictional_handoff_failure");
            var result = client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run/result"))
                    .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "http-result")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(resultBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode());
            assertEquals("empty_success", CanonicalJson.parse(result.body()).get("data")
                    .get("delivery_state").asText());
            assertEquals("empty_success", jdbc.queryForObject("SELECT delivery_state FROM source_runs WHERE id=?",
                    String.class, "fixture-http-run"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='generation_intent' AND task_type='news_dedup' AND state='waiting_input'", Integer.class));
            assertEquals(1, CanonicalJson.parse(jdbc.queryForObject("SELECT job_refs_json FROM source_runs WHERE id='fixture-http-run'", String.class)).size());
            utcUpdate("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,"
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
            goInterop("fresh");
            if (System.getenv("V02_GO_EXEC") != null) {
                assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
                assertEquals("receiving", jdbc.queryForObject(
                        "SELECT delivery_state FROM source_runs WHERE id=?", String.class, "fixture-go-run"));
                assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts"
                                + " WHERE run_id=? AND control_operation='renew'",
                        Long.class, "fixture-go-run"));
                byte[] originalClaimHash = jdbc.queryForObject(
                        "SELECT claim_token_hash FROM source_runs WHERE id=?", byte[].class, "fixture-go-run");
                assertNotNull(originalClaimHash);
                utcUpdate("UPDATE source_runs SET claim_token_hash=? WHERE id=?",
                        NodeTokens.digest("C".repeat(43)), "fixture-go-run");
                goInterop("stale");
                utcUpdate("UPDATE source_runs SET claim_token_hash=? WHERE id=?",
                        originalClaimHash, "fixture-go-run");
                goInterop("finalize");
                assertEquals("complete", jdbc.queryForObject(
                        "SELECT delivery_state FROM source_runs WHERE id=?", String.class, "fixture-go-run"));
                assertEquals(2L, jdbc.queryForObject(
                        "SELECT expected_count FROM source_runs WHERE id=?", Long.class, "fixture-go-run"));
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts"
                                + " WHERE run_id=? AND control_operation='manifest'",
                        Long.class, "fixture-go-run"));
                goInterop("negative");
                assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
                assertEquals(1, utcUpdate("UPDATE request_receipts SET created_at=? WHERE observation_id=?",
                        Timestamp.from(Instant.now().minusSeconds(91L * 24 * 3600)),
                        "go-metric-observation"));
                goInterop("replay");
                assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
                for (String fixture : List.of("partial", "pending", "excluded", "empty", "takeover")) {
                    String id = fixture.equals("takeover") ? "go-takeover" : "go-matrix-" + fixture;
                    utcUpdate("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,window_start,"
                                    + "window_end,config_version,config_snapshot_json,activation_stage,revision,"
                                    + "delivery_state,fetch_mode,collection_reason,fetch_state,analysis_state,accepted_count,"
                                    + "excluded_count,pending_count,coverage_json,job_refs_json,claim_node_id,claim_token_hash,lease_until)"
                                    + " SELECT ?,source_id,run_kind,business_date,?,window_start,window_end,config_version,"
                                    + "config_snapshot_json,activation_stage,0,'receiving',fetch_mode,collection_reason,"
                                    + "'running','waiting_input',0,0,0,'{}','[]','fixture-node',?,? FROM source_runs WHERE id=?",
                            id, 10 + List.of("partial", "pending", "excluded", "empty", "takeover").indexOf(fixture),
                            NodeTokens.digest("C".repeat(43)), Timestamp.from(Instant.now().plusSeconds(600)),
                            "fixture-go-run");
                }
                goInterop("matrix");
                assertEquals(15L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE observation_id LIKE 'negative-%'", Long.class));
                goInterop("takeover-prepare");
                utcUpdate("UPDATE source_runs SET lease_until=? WHERE id='go-takeover'",
                        Timestamp.from(v3Clock.instant().minusSeconds(1)));
                goInterop("takeover");
                assertEquals("empty_success", jdbc.queryForObject("SELECT delivery_state FROM source_runs WHERE id='go-takeover'", String.class));
                assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE run_id='go-takeover' AND control_operation IN ('manifest','result')", Long.class));
            }
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
            assertEquals(System.getenv("V02_GO_EXEC") == null ? 1L : 15L,
                    jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE observation_id=?",
                    Long.class, "unreviewed-article"));
            utcUpdate("UPDATE sources SET activation_stage='disabled',config_revision=2 WHERE id=?",
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
            if (System.getenv("V04_GO_EXEC") != null) {
                for (String id : List.of("v04-renew", "v04-release")) {
                    String date=id.equals("v04-renew")?"2026-09-28":"2026-09-29";
                    java.time.LocalDate day=java.time.LocalDate.parse(date);
                    utcUpdate("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,"
                            +"window_start,window_end,config_version,config_snapshot_json,activation_stage,revision,"
                            +"delivery_state,fetch_mode,collection_reason,fetch_state,analysis_state,accepted_count,excluded_count,pending_count,coverage_json,job_refs_json)"
                            +" VALUES(?,?,'daily',?,1,?,?,1,?,'production',0,'pending','daily_delta','daily','queued','waiting_input',0,0,0,'{}','[]')",
                            id,"fixture:go",java.sql.Date.valueOf(day),Timestamp.from(day.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()),
                            Timestamp.from(day.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()),config);
                }
                v3Queue=Files.createTempDirectory(Path.of("target"),"fictional-v04-").toAbsolutePath().resolve("queue.db");
                Instant base=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                v3Clock.freeze(base);goSQLiteInterop("fresh");
                assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM source_run_items WHERE run_id='v04-renew'",Long.class));
                assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM request_receipts WHERE observation_id='v04-observation'",Long.class));
                v3Clock.freeze(base.plusSeconds(20));goSQLiteInterop("lost-b");
                assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM request_receipts WHERE run_id='v04-renew' AND control_operation='renew'",Long.class));
                Timestamp bUntil=utcTimestamp("SELECT lease_until FROM source_runs WHERE id='v04-renew'");
                assertEquals(base.plusSeconds(170),bUntil.toInstant());
                v3Clock.freeze(base.plusSeconds(240));goSQLiteInterop("takeover");
                for(String id:List.of("v04-renew","v04-release")) {
                    assertEquals(base.plusSeconds(390),utcTimestamp("SELECT lease_until FROM source_runs WHERE id=?",id).toInstant());
                    assertEquals("receiving",jdbc.queryForObject("SELECT delivery_state FROM source_runs WHERE id=?",String.class,id));
                }
                assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM request_receipts WHERE run_id='v04-renew' AND control_operation='renew'",Long.class));
                assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM request_receipts WHERE run_id='v04-release' AND control_operation='release'",Long.class));
                assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM source_run_items WHERE run_id='v04-renew'",Long.class));
                assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM request_receipts WHERE observation_id='v04-observation'",Long.class));
            }
            utcUpdate("UPDATE collector_nodes SET token_hash=? WHERE id=?",
                    NodeTokens.digest("B".repeat(43)), "fixture-node");
            goInterop("revoked");
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(root + "/runs/fixture-http-run"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            utcUpdate("UPDATE collector_nodes SET token_hash=? WHERE id=?",
                    NodeTokens.digest(TOKEN), "fixture-node");
            utcUpdate("UPDATE collector_nodes SET enabled=0 WHERE id=?", "fixture-node");
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
        CollectionHandoffFixture.jobs(jdbc, System.getenv("B26_TEST_DB_URL") != null);
        ddl("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        utcUpdate("INSERT INTO worker_lock VALUES(1)");
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
                + "exclusion_reason VARCHAR(128),activation_state VARCHAR(16),UNIQUE(run_id,member_id),UNIQUE(run_id,source_item_hash))");
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

    // Fixture SQL uses the same physical UTC timestamp convention as the runtime.
    private int utcUpdate(String sql,Object...args) {
        return jdbc.update(sql,ps->{
            for(int i=0;i<args.length;i++) {
                if(args[i] instanceof Timestamp value) ps.setTimestamp(i+1,value,java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
                else if(args[i] instanceof java.sql.Date value) ps.setString(i+1,value.toLocalDate().toString());
                else ps.setObject(i+1,args[i]);
            }
        });
    }
    private Timestamp utcTimestamp(String sql,Object...args) {
        return jdbc.queryForObject(sql,(rs,index)->rs.getTimestamp(1,java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))),args);
    }

    private void goSQLiteInterop(String mode) throws Exception {
        String go=System.getenv("V04_GO_EXEC");
        ProcessBuilder command=new ProcessBuilder(go,"test","./internal/outbox","-run","^TestV3SQLiteJavaHTTP$","-count=1");
        command.directory(Path.of("../collector").toAbsolutePath().normalize().toFile());
        command.redirectErrorStream(true);
        Path log=Path.of("target/v04-go-"+mode+".log").toAbsolutePath();command.redirectOutput(log.toFile());
        command.environment().put("V04_BASE_URL","https://127.0.0.1:"+port);
        command.environment().put("V04_NODE_TOKEN",TOKEN);
        command.environment().put("V04_QUEUE_PATH",v3Queue.toString());
        command.environment().put("V04_MODE",mode);
        command.environment().put("V04_NOW",v3Clock.instant().toString());
        command.environment().put("GOROOT",Path.of(go).getParent().getParent().toString());
        command.environment().put("GOTOOLCHAIN","local");command.environment().put("GOPROXY","off");
        Process process=command.start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);fail("fictional v3 HTTP child timed out");}
        assertEquals(0,process.exitValue(),Files.readString(log,StandardCharsets.UTF_8));
    }

    private void goInterop(String mode) throws Exception {
        String go = System.getenv("V02_GO_EXEC");
        if (go == null) return;
        ProcessBuilder command = new ProcessBuilder(go, "test", "./internal/client", "-run",
                "^TestJavaInterop$", "-count=1");
        command.directory(Path.of("../collector").toAbsolutePath().normalize().toFile());
        command.redirectErrorStream(true);
        Path childLog = Path.of("target/v02-go-child.log").toAbsolutePath();
        command.redirectOutput(childLog.toFile());
        command.environment().put("V02_BASE_URL", "https://127.0.0.1:" + port);
        command.environment().put("V02_NODE_TOKEN", TOKEN);
        command.environment().put("V02_MODE", mode);
        command.environment().put("V02_FROZEN_FILE", Path.of("target/v02-frozen-observation.json")
                .toAbsolutePath().toString());
        command.environment().put("V02_CLAIM_FILE", Path.of("target/v02-frozen-claim.json")
                .toAbsolutePath().toString());
        command.environment().put("V02_CATALOG_FILE", Path.of("target/v02-frozen-catalog.json")
                .toAbsolutePath().toString());
        command.environment().put("GOTOOLCHAIN", "local");
        command.environment().put("GOPROXY", "off");
        command.environment().put("GOROOT", Path.of(go).getParent().getParent().toString());
        Process process = command.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Go interop timed out");
        }
        String output = Files.readString(childLog, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
