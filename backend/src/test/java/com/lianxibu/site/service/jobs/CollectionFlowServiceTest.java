package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.CollectionFlowService.Accepted;
import com.lianxibu.site.service.jobs.CollectionFlowService.Finished;
import com.lianxibu.site.service.jobs.CollectionFlowService.MemberCandidate;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CollectionFlowServiceTest {
    private JdbcTemplate jdbc;
    private CollectionFlowService service;
    private boolean failIntention;
    private int ensureCalls;
    private String nextState = "complete";
    private final List<MemberCandidate> ready = new ArrayList<>();

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:collection-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE collection_receipts(action_key VARCHAR(128) PRIMARY KEY,"
                + "run_id VARCHAR(64),receipt_ref VARCHAR(64),state VARCHAR(32))");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,delivery_state VARCHAR(32))");
        jdbc.update("INSERT INTO source_runs VALUES('run-a','waiting_items')");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "member_id VARCHAR(64),terminal_state VARCHAR(16))");
        jdbc.execute("CREATE TABLE jobs_intents(run_id VARCHAR(64),member_id VARCHAR(64),"
                + "source_id VARCHAR(64),evidence_ref VARCHAR(64),category VARCHAR(32),"
                + "target_business_date DATE, state VARCHAR(16),UNIQUE(run_id,member_id))");
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        var observation = new CollectionFlowService.ObservationPort() {
            @Override public Accepted committed(String actionKey) {
                var rows = jdbc.query("SELECT run_id,receipt_ref,state FROM collection_receipts"
                                + " WHERE action_key=?", (rs, ignored) -> new Accepted(actionKey,
                                rs.getString(2), rs.getString(1), rs.getString(3), ready), actionKey);
                return rows.isEmpty() ? null : rows.getFirst();
            }
            @Override public Accepted acceptJoined(byte[] raw, ActionContext action) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,?)", action.actionKey(),
                        "run-a", "member-new", "accepted");
                jdbc.update("INSERT INTO collection_receipts VALUES(?,?,?,?)", action.actionKey(),
                        "run-a", action.preallocatedResourceId(), nextState);
                return new Accepted(action.actionKey(), action.preallocatedResourceId(),
                        "run-a", nextState, List.copyOf(ready));
            }
        };
        var run = new CollectionFlowService.RunPort() {
            @Override public Finished committed(String actionKey) {
                var rows = jdbc.query("SELECT run_id,state FROM collection_receipts WHERE action_key=?",
                        (rs, ignored) -> new Finished(rs.getString(1), rs.getString(2), ready), actionKey);
                return rows.isEmpty() ? null : rows.getFirst();
            }
            @Override public Finished finishJoined(String runId, ActionContext action) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.update("UPDATE source_runs SET delivery_state=? WHERE id=?", nextState, runId);
                jdbc.update("INSERT INTO collection_receipts VALUES(?,?,?,?)", action.actionKey(),
                        runId, action.preallocatedResourceId(), nextState);
                return new Finished(runId, nextState, List.copyOf(ready));
            }
        };
        var intentions = (CollectionFlowService.IntentionPort) (runId, memberId, sourceId,
                evidenceRef, category, targetBusinessDate) -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (failIntention && ++ensureCalls == 5) throw new IllegalStateException("intent write failed");
            Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM jobs_intents"
                    + " WHERE run_id=? AND member_id=?", Long.class, runId, memberId);
            if (existing != null && existing > 0) return false;
            jdbc.update("INSERT INTO jobs_intents VALUES(?,?,?,?,?,?,'pending')", runId,
                    memberId, sourceId, evidenceRef, category,
                    targetBusinessDate == null ? null : java.sql.Date.valueOf(targetBusinessDate));
            return true;
        };
        service = new CollectionFlowService(writes, observation, run, intentions,
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test void nineAcceptedOneExcludedCreateOnlyNineUniquePendingIntentions() {
        nextState = "partial";
        for (int i = 0; i < 9; i++) ready.add(member("member-" + i, "accepted", null, null));
        ready.add(member("member-9", "excluded", null, null));
        assertEquals(9, service.finish("run-a", action("finish-a")).intentionsCreated());
        assertEquals(9L, count("jobs_intents"));
        assertEquals("partial", jdbc.queryForObject("SELECT delivery_state FROM source_runs", String.class));
        assertEquals(0, service.finish("run-a", action("finish-a")).intentionsCreated());
        assertEquals(9L, count("jobs_intents"));
    }

    @Test void intentionFailureRollsBackRunResultReceiptAndEarlierIntentions() {
        for (int i = 0; i < 9; i++) ready.add(member("member-" + i, "accepted", null, null));
        failIntention = true;
        assertThrows(IllegalStateException.class, () -> service.finish("run-a", action("finish-fail")));
        assertEquals("waiting_items", jdbc.queryForObject("SELECT delivery_state FROM source_runs", String.class));
        assertEquals(0L, count("collection_receipts"));
        assertEquals(0L, count("jobs_intents"));
        failIntention = false;
        assertEquals(9, service.finish("run-a", action("finish-fail")).intentionsCreated());
    }

    @Test void observationReceiptAndIntentionRollbackTogetherAndFutureCandidateWaits() {
        ready.add(member("member-new", "accepted", null, null));
        failIntention = true;
        ensureCalls = 4;
        assertThrows(IllegalStateException.class, () -> service.accept("{}".getBytes(StandardCharsets.UTF_8),
                action("observe-fail")));
        assertEquals(0L, count("collection_receipts"));
        assertEquals(0L, count("source_run_items"));
        failIntention = false;
        assertEquals(1, service.accept("{}".getBytes(StandardCharsets.UTF_8),
                action("observe-fail")).intentionsCreated());
        assertEquals(1L, count("jobs_intents"));
        assertEquals(0, service.accept("{}".getBytes(StandardCharsets.UTF_8),
                action("observe-fail")).intentionsCreated());
        assertEquals(1L, count("source_run_items"));
    }

    @Test void unqualifiedDisputedAndNextBusinessDayStayOutOfCurrentIntentions() {
        ready.add(member("pending", "pending", null, null));
        ready.add(member("disputed", "accepted", "needs_review", null));
        ready.add(member("tomorrow", "accepted", null, LocalDate.of(2026, 10, 2)));
        nextState = "partial";
        assertEquals(0, service.finish("run-a", action("not-ready")).intentionsCreated());
        assertEquals(0L, count("jobs_intents"));
    }

    private static MemberCandidate member(String id, String state, String projection,
                                          LocalDate targetDate) {
        return new MemberCandidate("run-a", id, "source-a", "evidence-" + id, "news_tech",
                state, projection, true, targetDate, "pending");
    }
    private static ActionContext action(String key) { return new ActionContext(key, "receipt-" + key, null); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
