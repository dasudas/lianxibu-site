package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceRunReceipts;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.SourceRunLeaseService.LeaseRejected;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SourceRunLeaseServiceTest {
    private final MutableClock time = new MutableClock("2026-01-02T16:09:00Z");
    private JdbcTemplate jdbc;
    private SourceRunLeaseService service;
    private JdbcSourceRunStore runs;
    private final TestAuthorizer nodes = new TestAuthorizer();
    private final String sourceId = "fixture:feed";
    private boolean newRunAllowed = true;
    private SourceRunLeaseService unconfigured;

    @BeforeEach void setup() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:lease-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,source_kind VARCHAR(16),"
                + "name VARCHAR(200),config_json CLOB,draft_config_json CLOB,effective_config_json CLOB,"
                + "revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(16),"
                + "config_valid_until TIMESTAMP,category VARCHAR(16))");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,"
                + "generation BIGINT,window_start TIMESTAMP,window_end TIMESTAMP,base_run_id VARCHAR(64),"
                + "config_version BIGINT,config_snapshot_json CLOB,activation_stage VARCHAR(16),"
                + "fetch_mode VARCHAR(32),collection_reason VARCHAR(32),delivery_state VARCHAR(32),"
                + "fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,"
                + "excluded_count BIGINT,pending_count BIGINT,coverage_json CLOB,job_refs_json CLOB,"
                + "claim_node_id VARCHAR(64),claim_token_hash BINARY(32),claim_token_ciphertext BLOB,"
                + "claim_key_id VARCHAR(64),lease_until TIMESTAMP,node_id VARCHAR(64),"
                + "started_at TIMESTAMP,"
                + "UNIQUE(source_id,run_kind,business_date,generation))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(32),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),"
                + "original_status VARCHAR(32),current_status VARCHAR(32),ack_json CLOB,"
                + "result_ref_json CLOB,created_at TIMESTAMP,revision BIGINT,"
                + "request_key_raw VARCHAR(128),business_hash BINARY(32),control_operation VARCHAR(32),"
                + "UNIQUE(actor_id,scope,key_hash))");
        String config = "{\"valid_until\":\"2027-01-01T00:00:00Z\"}";
        jdbc.update("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", sourceId, "rss", "Fixture",
                config, config, config, 1, 1, "production", java.sql.Timestamp.from(
                        Instant.parse("2027-01-01T00:00:00Z")), "news_tech");
        runs = new JdbcSourceRunStore(db);
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(db),
                new DataSourceTransactionManager(db), () -> 25, ignored -> { });
        var codec = new RunLeaseTokenCodec(new SecretKeySpec(new byte[32], "AES"),
                "fixture-key", new SecureRandom());
        service = new SourceRunLeaseService(runs, new JdbcSourceAdmission(db), writes, codec,
                nodes, new JdbcSourceRunReceipts(db, time), time, ignored -> newRunAllowed);
        unconfigured = new SourceRunLeaseService(runs, new JdbcSourceAdmission(db), writes, codec,
                nodes, new JdbcSourceRunReceipts(db, time), time);
    }

    @Test void capacityStopsOnlyNewRunAllocationAndKeepsPermanentReceiptAndLeaseControls() {
        time.set("2026-01-02T16:10:00Z");
        assertThrows(SourceRunLeaseService.CapacityGuarded.class, () -> unconfigured.scheduleDaily(sourceId, action("unconfigured")));
        var scheduled = service.scheduleDaily(sourceId, action("before-guard"));
        newRunAllowed = false;
        assertEquals(scheduled, service.scheduleDaily(sourceId, action("before-guard")));
        assertEquals(scheduled, service.scheduleDaily(sourceId, action("same-existing-run")));
        long receipts = count("request_receipts");
        assertThrows(SourceRunLeaseService.CapacityGuarded.class, () -> service.scheduleBackfill(sourceId,"historical_backfill",scheduled.runId(),action("guarded-new")));
        assertEquals(1L,count("source_runs"));assertEquals(receipts,count("request_receipts"));
        var claim=service.claimNext("node-a-bearer",false,action("existing-claim"));
        assertEquals(scheduled.runId(),claim.runId());
        service.acknowledge("node-a-bearer",claim.runId(),claim.claimToken(),action("existing-ack"));
        service.renew("node-a-bearer",claim.runId(),claim.claimToken(),action("existing-renew"));
        newRunAllowed=true;
        assertNotEquals(scheduled.runId(),service.scheduleBackfill(sourceId,"historical_backfill",scheduled.runId(),action("guarded-new")).runId());
    }

    @Test void dailyWindowClaimReplayAndDistinctControls() {
        assertThrows(LeaseRejected.class, () -> service.scheduleDaily(sourceId, action("too-early")));
        time.set("2026-01-02T16:10:00Z");
        var scheduled = service.scheduleDaily(sourceId, action("daily-one"));
        assertEquals("2026-01-02", scheduled.businessDate().toString());
        assertEquals(scheduled, service.scheduleDaily(sourceId, action("daily-one")));
        assertEquals(scheduled, service.scheduleDaily(sourceId, action("daily-same-window")));
        assertEquals(1L, count("source_runs"));
        assertThrows(LeaseRejected.class, () -> service.scheduleBackfill(sourceId,
                "historical_backfill", scheduled.runId(), action("daily-one")));

        var claim = service.claimNext("node-a-bearer", false, action("claim-one"));
        assertEquals(scheduled.runId(), claim.runId());
        assertEquals(Instant.parse("2026-01-01T16:00:00Z"), claim.windowStart());
        assertEquals(Instant.parse("2026-01-02T16:00:00Z"), claim.windowEnd());
        assertNotNull(claim.claimToken());
        assertEquals(claim.claimToken(), service.claimNext("node-a-bearer", false,
                action("claim-one")).claimToken());
        assertThrows(LeaseRejected.class, () -> service.claimNext("node-a-bearer", true,
                action("claim-one")));
        assertFalse(claim.toString().contains(claim.claimToken()));
        assertFalse(runs.byId(claim.runId()).toString().contains(claim.claimToken()));
        assertFalse(java.util.Arrays.equals(runs.byId(claim.runId()).claimTokenCiphertext(),
                claim.claimToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var ack = service.acknowledge("node-a-bearer", claim.runId(), claim.claimToken(), action("ack"));
        assertEquals(ack, service.acknowledge("node-a-bearer", claim.runId(),
                claim.claimToken(), action("ack")));
        assertEquals("running", jdbc.queryForObject("SELECT fetch_state FROM source_runs WHERE id=?",
                String.class, claim.runId()));
        var renewed1 = service.renew("node-a-bearer", claim.runId(), claim.claimToken(), action("renew-1"));
        time.set("2026-01-02T16:11:00Z");
        var renewed2 = service.renew("node-a-bearer", claim.runId(), claim.claimToken(), action("renew-2"));
        assertTrue(renewed2.originalLeaseUntil().isAfter(renewed1.originalLeaseUntil()));
        assertEquals(renewed1.resultRevision(), service.renew("node-a-bearer", claim.runId(),
                claim.claimToken(), action("renew-1")).resultRevision());
        assertThrows(LeaseRejected.class, () -> service.release("node-a-bearer", claim.runId(),
                claim.claimToken(), action("ack")));
        service.release("node-a-bearer", claim.runId(), claim.claimToken(), action("release"));
        assertNull(runs.byId(claim.runId()).claimTokenHash());
    }

    @Test void expiryRevocationAndWindowsAreFenced() {
        time.set("2026-01-02T16:10:00Z");
        var first = service.scheduleDaily(sourceId, action("day-one"));
        var claim = service.claimNext("node-a-bearer", false, action("claim-one"));
        nodes.revoked = true;
        assertThrows(SecurityException.class, () -> service.claimNext("node-a-bearer", false,
                action("claim-one")));
        assertThrows(SecurityException.class, () -> service.acknowledge("node-a-bearer", first.runId(),
                claim.claimToken(), action("revoked-ack")));
        nodes.revoked = false;
        time.set("2026-01-02T16:13:00Z");
        assertThrows(LeaseRejected.class, () -> service.renew("node-a-bearer", first.runId(),
                claim.claimToken(), action("expired-renew")));
        var replacement = service.claimNext("node-b-bearer", false, action("claim-two"));
        assertNotEquals(claim.claimToken(), replacement.claimToken());
        assertThrows(LeaseRejected.class, () -> service.acknowledge("node-a-bearer", first.runId(),
                claim.claimToken(), action("old-ack")));
        assertNull(service.claimNext("node-a-bearer", false, action("claim-one")).claimToken());
        time.set("2026-01-03T16:10:00Z");
        var second = service.scheduleDaily(sourceId, action("day-two"));
        assertNotEquals(first.runId(), second.runId());
        assertEquals(2L, count("source_runs"));
        assertThrows(LeaseRejected.class, () -> service.scheduleDaily(sourceId, action("day-one")));
    }

    @Test void explicitClaimScopeCannotClaimOrReplayOutsideRequestedSources() {
        time.set("2026-01-02T16:10:00Z");
        var run = service.scheduleDaily(sourceId, action("scoped-daily"));
        assertNull(service.claimNext("node-a-bearer", false, List.of(), 10,
                action("empty-claim")).runId());
        assertThrows(LeaseRejected.class, () -> service.claimNext("node-a-bearer", false,
                List.of("unassigned"), 10, action("foreign-source")));
        var claim = service.claimNext("node-a-bearer", false, List.of(sourceId), 10,
                action("scoped-claim"));
        assertEquals(run.runId(), claim.runId());
        assertEquals(claim.claimToken(), service.claimNext("node-a-bearer", false,
                List.of(sourceId), 10, action("scoped-claim")).claimToken());
        assertThrows(LeaseRejected.class, () -> service.claimNext("node-a-bearer", false,
                List.of(sourceId), 1, action("scoped-claim")));
    }

    @Test void backfillGenerationProbeAndConfigEdit() {
        time.set("2026-01-02T16:10:00Z");
        var base = service.scheduleDaily(sourceId, action("base"));
        var backfill1 = service.scheduleBackfill(sourceId, "historical_backfill", base.runId(),
                action("backfill-one"));
        var backfill2 = service.scheduleBackfill(sourceId, "historical_backfill", base.runId(),
                action("backfill-two"));
        assertEquals(1, backfill1.generation());
        assertEquals(2, backfill2.generation());
        var narrow1 = service.scheduleHistoricalWindow(sourceId, base.businessDate(),
                Instant.parse("2026-01-01T16:00:00Z"), Instant.parse("2026-01-01T17:00:00Z"),
                action("narrow-one"));
        var narrow2 = service.scheduleHistoricalWindow(sourceId, base.businessDate(),
                Instant.parse("2026-01-01T17:00:00Z"), Instant.parse("2026-01-01T18:00:00Z"),
                action("narrow-two"));
        assertNotEquals(narrow1.runId(), narrow2.runId());
        assertEquals(3, narrow1.generation());
        assertEquals(4, narrow2.generation());
        jdbc.update("UPDATE sources SET activation_stage='disabled',config_revision=2,"
                + "config_valid_until=NULL WHERE id=?", sourceId);
        var probe = service.scheduleProbe(sourceId, action("probe-one"));
        assertEquals("probe", probe.runKind());
        assertNull(service.claimNext("node-a-bearer", false, action("old-config-claim")).runId());
        assertNotNull(service.claimNext("node-a-bearer", true, action("probe-claim")).claimToken());
        assertEquals(2, service.scheduleProbe(sourceId, action("probe-two")).generation());
    }

    @Test void ciphertextCannotReopenForAnotherRunOrAfterTampering() {
        var codec = new RunLeaseTokenCodec(new SecretKeySpec(new byte[32], "AES"),
                "fixture-key", new SecureRandom());
        var issued = codec.issue("run-a", "node-a");
        assertEquals(issued.token(), codec.reopen("run-a", "node-a", issued.keyId(),
                issued.ciphertext(), issued.digest()));
        assertNull(codec.reopen("run-b", "node-a", issued.keyId(),
                issued.ciphertext(), issued.digest()));
        byte[] tampered = issued.ciphertext().clone();
        tampered[tampered.length - 1] ^= 1;
        assertNull(codec.reopen("run-a", "node-a", issued.keyId(), tampered, issued.digest()));
        assertFalse(issued.toString().contains(issued.token()));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
    private static ActionContext action(String key) {
        return new ActionContext(key, "run-" + key, null);
    }
    private static final class TestAuthorizer implements SourceRunLeaseService.NodeAuthorizer {
        boolean revoked;
        @Override public Principal authorize(String bearer, String permission, String sourceId) {
            if (revoked) throw new SecurityException("node revoked");
            if (bearer == null || !bearer.matches("node-[ab]-bearer")
                    || (sourceId != null && !sourceId.equals("fixture:feed"))) {
                throw new SecurityException("node permission denied");
            }
            return new Principal(bearer.substring(0, 6), List.of("fixture:feed"),
                    List.of("run:claim", "run:write", "probe:claim", "probe:write"), 1);
        }
    }
    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(String initial) { set(initial); }
        void set(String value) { now = Instant.parse(value); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
