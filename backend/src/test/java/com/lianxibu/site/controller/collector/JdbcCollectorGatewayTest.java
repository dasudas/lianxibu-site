package com.lianxibu.site.controller.collector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianxibu.site.controller.collector.CollectorGateway.Operation;
import com.lianxibu.site.controller.collector.CollectorGateway.Request;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceRunReceipts;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.repository.sources.JdbcRunManifestStore;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.RunLeaseTokenCodec;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import com.lianxibu.site.service.sources.RunManifestService;
import com.lianxibu.site.service.sources.ProbeResultService;
import com.lianxibu.site.service.articles.ObservationIngressService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.mockito.ArgumentCaptor;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

class JdbcCollectorGatewayTest {
    private static final Instant NOW = Instant.parse("2026-01-02T16:10:00Z");
    private static final String SOURCE = "fixture:feed";
    private static final Principal NODE = new Principal("fixture-node", List.of(SOURCE),
            List.of("config:read", "run:claim", "run:read", "run:write", "heartbeat:write"), 1);
    private JdbcCollectorGateway gateway;
    private SourceRunLeaseService leases;
    private JdbcTemplate jdbc;
    private ObservationIngressService observationService;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:collector-gateway-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),name VARCHAR(200),"
                + "config_json CLOB,draft_config_json CLOB,effective_config_json CLOB,revision BIGINT,"
                + "config_revision BIGINT,activation_stage VARCHAR(16),config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,revision BIGINT,"
                + "source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,"
                + "window_start TIMESTAMP,window_end TIMESTAMP,base_run_id VARCHAR(64),config_version BIGINT,"
                + "config_snapshot_json CLOB,activation_stage VARCHAR(16),fetch_mode VARCHAR(32),"
                + "collection_reason VARCHAR(32),target_version_json CLOB,delivery_state VARCHAR(32),"
                + "fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,excluded_count BIGINT,"
                + "pending_count BIGINT,coverage_json CLOB,job_refs_json CLOB,claim_node_id VARCHAR(64),"
                + "claim_token_hash BINARY(32),claim_token_ciphertext BLOB,claim_key_id VARCHAR(64),"
                + "lease_until TIMESTAMP,node_id VARCHAR(64),started_at TIMESTAMP,finished_at TIMESTAMP,"
                + "barrier_closed_at TIMESTAMP,"
                + "result_json CLOB,probe_trial_result_json CLOB,manifest_hash BINARY(32),"
                + "part_count BIGINT,expected_count BIGINT,"
                + "UNIQUE(source_id,run_kind,business_date,generation))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(32),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),original_status VARCHAR(32),"
                + "current_status VARCHAR(32),ack_json CLOB,result_ref_json CLOB,created_at TIMESTAMP,"
                + "revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),"
                + "control_operation VARCHAR(32),UNIQUE(actor_id,scope,key_hash))");
        jdbc.execute("CREATE TABLE source_run_items(run_id VARCHAR(64),part_no BIGINT,terminal_state VARCHAR(32))");
        String config = "{\"adapter\":\"rss\",\"entry_urls\":[\"https://example.invalid/feed\"],"
                + "\"identity_rule\":\"guid\",\"window_rule\":\"daily\",\"parser_version\":\"v1\","
                + "\"max_items\":10,\"max_response_bytes\":1024,\"allowed_hosts\":[\"example.invalid\"],"
                + "\"valid_until\":\"2027-01-01T00:00:00.000000Z\",\"auth_secret_ref\":null}";
        jdbc.update("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", SOURCE, "rss", "Fixture", config,
                config, config, 1, 1, "production", java.sql.Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                "news_tech");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NodeIdentityService nodes = mock(NodeIdentityService.class);
        when(nodes.authorizeBearer(anyString(), anyString(), nullable(String.class))).thenReturn(NODE);
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        leases = new SourceRunLeaseService(new JdbcSourceRunStore(source),
                new JdbcSourceAdmission(source), writes,
                new RunLeaseTokenCodec(new SecretKeySpec(new byte[32], "AES"), "fixture", new SecureRandom()),
                (bearer, permission, sourceId) -> NODE,
                new JdbcSourceRunReceipts(source, clock), clock, ignored -> true,com.lianxibu.site.service.sources.SourceDayClaimTestDouble.allow(source));
        var manifests = new RunManifestService(new JdbcRunManifestStore(source), writes,
                (bearer, sourceId) -> NODE, clock);
        observationService = mock(ObservationIngressService.class);
        var probes = new ProbeResultService(source, writes, nodes, clock);
        gateway = new JdbcCollectorGateway(source, nodes, leases, manifests,
                observationService, probes, clock);
    }

    @Test void configClaimRunAndPermanentLeaseReceiptUseCurrentDatabaseRows() {
        var config = gateway.execute(NODE, "fixture-bearer", Operation.CONFIG,
                request(null, null, null));
        assertEquals(SOURCE, config.data().get("sources").get(0).get("source_id").asText());
        assertEquals(304, gateway.execute(NODE, "fixture-bearer", Operation.CONFIG,
                request(null, null, config.etag())).status());
        var scheduled = leases.scheduleDaily(SOURCE, new ActionContext("schedule", "fixture-run", "fixture"));
        assertEquals("fixture-run", scheduled.runId());
        JsonNode claimBody = CanonicalJson.parse("{\"source_ids\":[\"fixture:feed\"],"
                + "\"required_features\":[],\"max_runs\":10}");
        var claim = gateway.execute(NODE, "fixture-bearer", Operation.RUN_CLAIMS,
                request(null, claimBody, null));
        JsonNode item = claim.data().get("runs").get(0);
        assertEquals("fixture-run", item.get("run").get("run_id").asText());
        assertEquals("no_input", item.get("run").get("coverage").get("state").asText());
        assertEquals(SOURCE, item.get("config").get("source_id").asText());
        assertEquals(1, gateway.execute(NODE, "fixture-bearer", Operation.RUNS,
                request(null, null, null)).data().get("items").size());
        String token = item.get("claim_token").asText();
        JsonNode ack = CanonicalJson.parse("{\"business\":{\"run_id\":\"fixture-run\",\"action\":\"ack\"},"
                + "\"attempt\":{\"claim_token\":\"" + token + "\",\"expected_run_revision\":null}}");
        var receipt = gateway.execute(NODE, "fixture-bearer", Operation.CLAIM_ACTION,
                new Request("fixture-run", null, null, null, null, null, null, "ack-key", ack));
        assertTrue(receipt.data().get("receipt_ref").asText().length() > 10);
        assertEquals("receiving", receipt.data().get("delivery_state").asText());
        String partHash = RunManifestService.partHash("fixture-run", 0, List.of());
        JsonNode part = CanonicalJson.parse("{\"business\":{\"run_id\":\"fixture-run\",\"part_no\":0,"
                + "\"part_hash\":\"" + partHash + "\",\"members\":[]},"
                + "\"attempt\":{\"claim_token\":\"" + token + "\",\"expected_run_revision\":null}}");
        var partReceipt = gateway.execute(NODE, "fixture-bearer", Operation.MANIFEST_WRITE,
                new Request("fixture-run", null, null, null, null, null, null, "part-key", part));
        assertEquals(64, partReceipt.data().get("business_hash").asText().length());
        var readback = gateway.execute(NODE, "fixture-bearer", Operation.MANIFEST_READ,
                request("fixture-run", null, null));
        assertEquals(partHash, readback.data().get("parts").get(0).get("part_hash").asText());
        String manifestHash = RunManifestService.manifestHash("fixture-run", 0,
                List.of(new RunManifestService.Part("fixture-run", 0, partHash, List.of())));
        long revision = jdbc.queryForObject("SELECT revision FROM source_runs WHERE id='fixture-run'", Long.class);
        JsonNode result = CanonicalJson.parse("{\"business\":{\"run_id\":\"fixture-run\","
                + "\"manifest_hash\":\"" + manifestHash + "\",\"part_count\":1,\"expected_count\":0,"
                + "\"fetch_outcome\":\"succeeded\",\"coverage\":{\"state\":\"complete\","
                + "\"missing_inputs\":[],\"missing_dates\":[],\"no_update_dates\":[]}},"
                + "\"attempt\":{\"claim_token\":\"" + token + "\",\"expected_run_revision\":" + revision + "}}");
        var terminal = gateway.execute(NODE, "fixture-bearer", Operation.RUN_RESULT,
                new Request("fixture-run", null, null, null, null, null, null, "result-key", result));
        assertEquals("empty_success", terminal.data().get("delivery_state").asText());
        assertEquals(terminal.data().get("business_hash").asText(),
                gateway.execute(NODE, "fixture-bearer", Operation.RUN,
                        request("fixture-run", null, null)).data().get("result_business_hash").asText());
    }

    @Test void unsupportedWritesAndEmptyClaimFailClosedWithoutCreatingRun() {
        JsonNode body = CanonicalJson.parse("{\"source_ids\":[],\"required_features\":[],\"max_runs\":10}");
        assertEquals(0, gateway.execute(NODE, "fixture-bearer", Operation.RUN_CLAIMS,
                request(null, body, null)).data().get("runs").size());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM source_runs", Long.class));
        assertThrows(CollectorGateway.Refused.class,
                () -> gateway.execute(NODE, "fixture-bearer", Operation.OBSERVATIONS, request(null, body, null)));
    }

    @Test void observationDispatchPreservesFrozenItemBytesAndAckIdentity() {
        String item = " { \"source_id\" : \"fixture:feed\", \"observation_id\":\"ob-one\","
                + "\"member_id\":\"member-one\" }";
        String batch = "{\"observations\":[" + item + "]}";
        when(observationService.accept(anyString(), any(byte[].class), any(ActionContext.class)))
                .thenReturn(new ObservationIngressService.Ack("ob-one", "member-one", "accepted",
                        "accepted", "receipt-one", NOW, null, null));
        var result = gateway.execute(NODE, "fixture-bearer", Operation.OBSERVATIONS,
                new Request(null, null, null, null, null, null, null, null,
                        CanonicalJson.parse(batch), batch.getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<byte[]> raw = ArgumentCaptor.forClass(byte[].class);
        verify(observationService).accept(anyString(), raw.capture(), any(ActionContext.class));
        assertEquals(item, new String(raw.getValue(), StandardCharsets.UTF_8));
        assertEquals("receipt-one", result.data().get("acks").get(0).get("receipt_ref").asText());
    }

    @Test void probeResultPersistsOnceAndCannotBeChangedByKeyReuse() {
        jdbc.update("UPDATE sources SET activation_stage='disabled',config_revision=2 WHERE id=?", SOURCE);
        var scheduled = leases.scheduleProbe(SOURCE, new ActionContext("probe-schedule", "fixture-probe", "fixture"));
        assertEquals("fixture-probe", scheduled.runId());
        JsonNode claimBody = CanonicalJson.parse("{\"source_ids\":[\"fixture:feed\"],\"required_features\":[]}");
        var claim = gateway.execute(NODE, "fixture-bearer", Operation.PROBE_CLAIMS,
                new Request(null, null, null, null, null, null, null, "probe-claim-key", claimBody));
        String token = claim.data().get("claim_token").asText();
        JsonNode result = CanonicalJson.parse("{\"business\":{\"probe_id\":\"fixture-probe\","
                + "\"result\":{\"reachable\":true,\"http_status\":200,\"tls_valid\":true,"
                + "\"parser_valid\":true,\"identity_valid\":true,"
                + "\"checked_at\":\"2026-01-02T16:10:00.000000Z\",\"error_code\":null,"
                + "\"evidence_refs\":[]}},\"attempt\":{\"claim_token\":\"" + token
                + "\",\"expected_run_revision\":null}}");
        Request request = new Request(null, null, "fixture-probe", null, null, null, null,
                "probe-result-key", result);
        var first = gateway.execute(NODE, "fixture-bearer", Operation.PROBE_RESULT, request);
        assertEquals("complete", first.data().get("delivery_state").asText());
        assertEquals(first.data().get("receipt_ref").asText(), gateway.execute(NODE,
                "fixture-bearer", Operation.PROBE_RESULT, request).data().get("receipt_ref").asText());
        assertEquals(first.data().get("receipt_ref").asText(), gateway.execute(NODE,
                "fixture-bearer", Operation.PROBE_RESULT,
                new Request(null, null, "fixture-probe", null, null, null, null,
                        "second-probe-result-key", result)).data().get("receipt_ref").asText());
        JsonNode changed = CanonicalJson.parse(result.toString().replace("\"reachable\":true", "\"reachable\":false"));
        assertThrows(CollectorGateway.Refused.class, () -> gateway.execute(NODE,
                "fixture-bearer", Operation.PROBE_RESULT,
                new Request(null, null, "fixture-probe", null, null, null, null,
                        "probe-result-key", changed)));
        assertNotNull(jdbc.queryForObject("SELECT probe_trial_result_json FROM source_runs WHERE id=?",
                String.class, "fixture-probe"));
    }

    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void physicalUtcWindowsLeaseAndConfigDeadlineSurviveHostZone(String zone) {
        var original=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
            leases.scheduleDaily(SOURCE,new ActionContext("utc-schedule","utc-run","fixture"));
            jdbc.update("UPDATE sources SET config_valid_until=TIMESTAMP '2026-01-02 16:10:01'");
            assertEquals(1,gateway.execute(NODE,"fixture-bearer",Operation.CONFIG,request(null,null,null)).data().get("sources").size());
            var run=gateway.execute(NODE,"fixture-bearer",Operation.RUN,request("utc-run",null,null)).data();
            assertEquals("2026-01-02",run.get("business_date").asText());
            assertEquals("2026-01-01T16:00:00.000000Z",run.get("window_start").asText());
            assertEquals("2026-01-02T16:00:00.000000Z",run.get("window_end").asText());
            var claim=leases.claimNext("fixture-bearer",false,new ActionContext("utc-claim","utc-claim-resource","fixture"));
            var ack=CanonicalJson.parse("{\"business\":{\"run_id\":\"utc-run\",\"action\":\"ack\"},\"attempt\":{\"claim_token\":\""+claim.claimToken()+"\",\"expected_run_revision\":null}}");
            var outcome=gateway.execute(NODE,"fixture-bearer",Operation.CLAIM_ACTION,new Request("utc-run",null,null,null,null,null,null,"utc-ack",ack)).data();
            assertTrue(outcome.get("current_lease_valid").booleanValue());
            assertEquals("2026-01-02T16:12:30.000000Z",outcome.get("lease_until").asText());
            jdbc.update("UPDATE source_runs SET barrier_closed_at=TIMESTAMP '2026-01-02 16:10:00' WHERE id='utc-run'");
            assertEquals("2026-01-02T16:10:00.000000Z",gateway.execute(NODE,"fixture-bearer",Operation.RUN,request("utc-run",null,null)).data().get("barrier_closed_at").asText());
            jdbc.update("UPDATE sources SET config_valid_until=TIMESTAMP '2026-01-02 16:10:00'");
            assertEquals(0,gateway.execute(NODE,"fixture-bearer",Operation.CONFIG,request(null,null,null)).data().get("sources").size());
        } finally {java.util.TimeZone.setDefault(original);}
    }

    private static Request request(String runId, JsonNode body, String etag) {
        return new Request(runId, null, null, null, null, null, etag, "fixture-key", body);
    }
}
