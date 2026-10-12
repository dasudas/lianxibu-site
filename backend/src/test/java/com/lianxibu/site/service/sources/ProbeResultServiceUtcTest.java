package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Fictional persistence test only. Node authorization is an explicit double. */
class ProbeResultServiceUtcTest {
    private static final Instant NOW=Instant.parse("2026-01-02T16:10:00Z");
    private static final String TOKEN="p".repeat(43);
    private JdbcTemplate jdbc;
    private ProbeResultService service;

    private void setup() {
        var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:utc-probe-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(64),config_revision BIGINT,activation_stage VARCHAR(16))");
        jdbc.update("INSERT INTO sources VALUES('source-a',1,'disabled')");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64),source_id VARCHAR(64),run_kind VARCHAR(16),revision BIGINT,delivery_state VARCHAR(32),claim_node_id VARCHAR(64),claim_token_hash BINARY(32),lease_until TIMESTAMP,config_version BIGINT,activation_stage VARCHAR(16),probe_trial_result_json CLOB,fetch_state VARCHAR(32),coverage_json CLOB,finished_at TIMESTAMP,barrier_closed_at TIMESTAMP)");
        jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,revision,delivery_state,claim_node_id,claim_token_hash,lease_until,config_version,activation_stage) VALUES('probe-a','source-a','probe',0,'claimed','node-a',?,TIMESTAMP '2026-01-02 16:12:30',1,'disabled')",RunLeaseTokenCodec.digest(TOKEN));
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(32),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),original_status VARCHAR(32),current_status VARCHAR(32),ack_json CLOB,created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),control_operation VARCHAR(32),UNIQUE(actor_id,scope,key_hash))");
        var nodes=mock(NodeIdentityService.class);
        when(nodes.authorizeBearer(anyString(),anyString(),anyString())).thenReturn(new NodeIdentityService.Principal("node-a",List.of("source-a"),List.of("probe:write"),1));
        service=new ProbeResultService(db,new SharedWriteExecutor(new JdbcSharedWriteEntry(db),new DataSourceTransactionManager(db),()->25,ignored->{}),nodes,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private ProbeResultService.Outcome submit(String key) {
        var business=CanonicalJson.parse("{\"probe_id\":\"probe-a\",\"result\":{\"reachable\":true,\"http_status\":200,\"tls_valid\":true,\"parser_valid\":true,\"identity_valid\":true,\"checked_at\":\"2026-01-02T16:10:00.000000Z\",\"error_code\":null,\"evidence_refs\":[]}}");
        var attempt=CanonicalJson.parse("{\"claim_token\":\""+TOKEN+"\",\"expected_run_revision\":null}");
        return service.submit("fictional-bearer","probe-a",business,attempt,new SharedWriteExecutor.ActionContext(key,"receipt-"+key,"fixture"));
    }

    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void utcLeaseAndOriginalReceiptSurviveHostZone(String zone) {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));setup();
            var outcome=submit("utc-result");assertEquals("complete",outcome.deliveryState());
            assertEquals(Instant.parse("2026-01-02T16:12:30Z"),outcome.originalLeaseUntil());
            assertEquals(NOW,readUtc("SELECT finished_at FROM source_runs"));
            assertEquals(NOW,readUtc("SELECT barrier_closed_at FROM source_runs"));
            assertEquals(NOW,readUtc("SELECT created_at FROM request_receipts"));
            assertEquals(outcome.receiptRef(),submit("utc-result").receiptRef());
            assertEquals(outcome.receiptRef(),submit("new-key").receiptRef());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        } finally {TimeZone.setDefault(original);}
    }

    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void physicalExpiredLeaseWritesNothing(String zone) {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));setup();
            jdbc.update("UPDATE source_runs SET lease_until=TIMESTAMP '2026-01-02 16:09:59'");
            assertThrows(ProbeResultService.ProbeRejected.class,()->submit("expired"));
            assertNull(jdbc.queryForObject("SELECT probe_trial_result_json FROM source_runs",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
        } finally {TimeZone.setDefault(original);}
    }

    private Instant readUtc(String sql) {return jdbc.queryForObject(sql,(rs,index)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());}
}
