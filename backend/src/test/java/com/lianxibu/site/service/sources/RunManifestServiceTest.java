package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcRunManifestStore;
import com.lianxibu.site.service.identity.NodeIdentityService.Principal;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.sources.RunManifestService.Member;
import com.lianxibu.site.service.sources.RunManifestService.Part;
import com.lianxibu.site.service.sources.RunManifestService.Result;
import com.lianxibu.site.service.sources.RunManifestService.RunFrozen;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RunManifestServiceTest {
    private JdbcTemplate jdbc;
    private RunManifestService service;
    private TransactionTemplate tx;
    private final String token = "A".repeat(43);
    private boolean revoked;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:manifest-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),"
                + "delivery_state VARCHAR(32),revision BIGINT,claim_node_id VARCHAR(64),"
                + "claim_token_hash BINARY(32),lease_until TIMESTAMP,part_count BIGINT,"
                + "manifest_hash BINARY(32),expected_count BIGINT,fetch_state VARCHAR(32),"
                + "coverage_json CLOB,result_json CLOB,accepted_count BIGINT,excluded_count BIGINT,"
                + "pending_count BIGINT,finished_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,run_id VARCHAR(64),member_id VARCHAR(64),source_id VARCHAR(80),"
                + "source_item_id CLOB,source_item_hash BINARY(32),observation_id VARCHAR(64),"
                + "node_id VARCHAR(64),payload_hash BINARY(32),replaces_observation_id VARCHAR(64),"
                + "part_no BIGINT,terminal_state VARCHAR(16),state VARCHAR(32),"
                + "validity_revision BIGINT,effective_valid INT,receipt_ref VARCHAR(64),"
                + "exclusion_reason VARCHAR(128),UNIQUE(run_id,member_id))");
        jdbc.execute("CREATE TABLE request_receipts(principal VARCHAR(80),operation VARCHAR(32),"
                + "request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json CLOB,"
                + "expires_at TIMESTAMP,id VARCHAR(64),receipt_kind VARCHAR(32),actor_id VARCHAR(64),"
                + "scope VARCHAR(128),key_hash BINARY(32),run_id VARCHAR(64),"
                + "original_status VARCHAR(32),current_status VARCHAR(32),ack_json CLOB,"
                + "created_at TIMESTAMP,revision BIGINT,request_key_raw VARCHAR(128),"
                + "business_hash BINARY(32),control_operation VARCHAR(32),"
                + "UNIQUE(actor_id,scope,key_hash))");
        jdbc.update("INSERT INTO source_runs(id,source_id,delivery_state,revision,claim_node_id,"
                        + "claim_token_hash,lease_until,coverage_json,accepted_count,excluded_count,"
                        + "pending_count) VALUES(?,?,?,?,?,?,?,?,0,0,0)",
                "run-a", "source-a", "claimed", 0, "node-a",
                RunLeaseTokenCodec.digest(token), Timestamp.from(Instant.parse("2026-10-01T00:03:00Z")), "{}");
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        SharedWriteExecutor writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                manager, () -> 25, ignored -> { });
        service = new RunManifestService(new JdbcRunManifestStore(source), writes,
                (bearer, sourceId) -> {
                    if (revoked || !"bearer-a".equals(bearer) || !"source-a".equals(sourceId))
                        throw new SecurityException("revoked");
                    return new Principal("node-a", List.of("source-a"), List.of("run:write"), 1);
                }, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void resultFirstThenPendingThenNineAcceptedOneExcludedIsPartial() {
        List<Member> members = members(10);
        Part part = part(0, members);
        Result result = result(List.of(part), 10);
        var waiting = service.submitResult("bearer-a", result, token, 0, action("result"));
        assertEquals("waiting_items", waiting.deliveryState());
        assertEquals(10, waiting.pending());
        assertEquals("waiting_items", service.putPart("bearer-a", part, token, action("part")).deliveryState());
        assertEquals(10, count("source_run_items"));
        assertEquals(10, service.putPart("bearer-a", part, token, action("part-again")).pending());
        for (int i = 0; i < 9; i++)
            jdbc.update("UPDATE source_run_items SET terminal_state='accepted',receipt_ref=?"
                    + " WHERE run_id='run-a' AND member_id=?", "receipt-" + i, "member-" + i);
        tx.executeWithoutResult(ignored -> service.recomputeAfterMember("run-a"));
        assertEquals(1, jdbc.queryForObject("SELECT pending_count FROM source_runs", Long.class));
        service.sealMember("bearer-a", "run-a", "member-9", 0, runRevision(),
                "invalid_payload", token, action("seal-nine"));
        assertEquals("partial", jdbc.queryForObject("SELECT delivery_state FROM source_runs", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT accepted_count FROM source_runs", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT excluded_count FROM source_runs", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT pending_count FROM source_runs", Long.class));
        assertThrows(RunFrozen.class, () -> service.putPart("bearer-a",
                part(0, members(1)), token, action("mutated")));
        revoked = true;
        assertThrows(SecurityException.class, () -> service.putPart("bearer-a", part, token, action("part")));
    }

    @Test void allExcludedAndEmptySuccessHaveDifferentOutcomes() {
        Part part = part(0, members(1));
        service.putPart("bearer-a", part, token, action("one-part"));
        service.submitResult("bearer-a", result(List.of(part), 1), token, 1, action("one-result"));
        service.sealMember("bearer-a", "run-a", "member-0", 0, runRevision(),
                "bad_payload", token, action("seal-one"));
        assertEquals("no_usable_input", jdbc.queryForObject("SELECT delivery_state FROM source_runs", String.class));

        jdbc.update("INSERT INTO source_runs(id,source_id,delivery_state,revision,claim_node_id,"
                        + "claim_token_hash,lease_until,coverage_json,accepted_count,excluded_count,"
                        + "pending_count) VALUES(?,?,?,?,?,?,?,?,0,0,0)",
                "run-empty", "source-a", "claimed", 0, "node-a",
                RunLeaseTokenCodec.digest(token), Timestamp.from(Instant.parse("2026-10-01T00:03:00Z")), "{}");
        Result empty = new Result("run-empty", RunManifestService.manifestHash("run-empty", 0, List.of()),
                0, 0, "succeeded", "{}");
        assertEquals("empty_success", service.submitResult("bearer-a", empty, token, 0,
                action("empty-result")).deliveryState());
    }

    @Test void rejectsBadHashDuplicateMemberAndStaleLease() {
        Part part = part(0, members(1));
        assertThrows(RunFrozen.class, () -> service.putPart("bearer-a",
                new Part(part.runId(), 0, "0".repeat(64), part.members()), token, action("bad-hash")));
        Member one = members(1).getFirst();
        assertThrows(RunFrozen.class, () -> service.putPart("bearer-a",
                new Part("run-a", 0, "0".repeat(64), List.of(one, one)), token, action("duplicate")));
        assertThrows(RunFrozen.class, () -> service.putPart("bearer-a", part,
                "B".repeat(43), action("stale")));
        assertEquals(0, count("source_run_items"));
    }

    @Test void outOfOrderPartsWaitForGapWithoutDroppingExpectedMembers() {
        Part first = part(0, List.of(members(2).get(0)));
        Part second = part(1, List.of(members(2).get(1)));
        service.submitResult("bearer-a", result(List.of(first, second), 2), token, 0,
                action("result-gap"));
        assertEquals("waiting_items", service.putPart("bearer-a", second, token,
                action("second-first")).deliveryState());
        assertEquals(2, jdbc.queryForObject("SELECT pending_count FROM source_runs", Long.class));
        assertEquals("waiting_items", service.putPart("bearer-a", first, token,
                action("first-second")).deliveryState());
        assertEquals(2L, count("source_run_items"));
    }

    private Part part(long number, List<Member> members) {
        return new Part("run-a", number, RunManifestService.partHash("run-a", number, members), members);
    }
    private Result result(List<Part> parts, long expected) {
        return new Result("run-a", RunManifestService.manifestHash("run-a", expected, parts),
                parts.size(), expected, "succeeded", "{}");
    }
    private static List<Member> members(int n) {
        List<Member> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new Member("member-" + i, "item-" + i,
                "observation-" + i, "%064x".formatted(i + 1), null));
        return rows;
    }
    private static ActionContext action(String key) { return new ActionContext(key, "receipt-" + key, null); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private long runRevision() { return jdbc.queryForObject("SELECT revision FROM source_runs WHERE id='run-a'", Long.class); }
}
