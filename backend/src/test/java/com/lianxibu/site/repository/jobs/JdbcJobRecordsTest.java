package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcJobRecords.Attempt;
import com.lianxibu.site.repository.jobs.JdbcJobRecords.Conflict;
import com.lianxibu.site.repository.jobs.JdbcJobRecords.Disposition;
import com.lianxibu.site.repository.jobs.JdbcJobRecords.Intent;
import com.lianxibu.site.repository.jobs.JdbcJobRecords.Part;
import com.lianxibu.site.repository.jobs.JdbcJobRecords.Root;
import java.time.Instant;
import java.time.LocalDate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcJobRecordsTest {
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcJobRecords records;
    private TransactionTemplate tx;
    private JdbcSharedWriteEntry entry;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:job-records-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) NOT NULL UNIQUE,"
                + "task_kind VARCHAR(32) NOT NULL,target_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "priority_no INT NOT NULL,eligible_at TIMESTAMP NOT NULL,revision BIGINT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,record_kind VARCHAR(24) NOT NULL,"
                + "record_identity_hash BINARY(32) NOT NULL UNIQUE,call_count INT NOT NULL,"
                + "max_attempts INT,logical_key CLOB,logical_key_hash BINARY(32),generation BIGINT,"
                + "stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,"
                + "input_state VARCHAR(16),root_job_id VARCHAR(64),attempt_no INT,request_json CLOB,"
                + "snapshot_owner_id VARCHAR(64),input_hash BINARY(32),owner_job_id VARCHAR(64),"
                + "purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_json CLOB,"
                + "payload_hash BINARY(32),action_key_hash BINARY(32),target_type VARCHAR(32),"
                + "target_key_json CLOB,target_key_hash BINARY(32),operator_id VARCHAR(64),"
                + "reason VARCHAR(2048),acted_at TIMESTAMP,action_payload CLOB,"
                + "action_payload_hash BINARY(32),applied_at TIMESTAMP,provider_request_id VARCHAR(256),"
                + "usage_json CLOB,cost_decimal DECIMAL(20,8),"
                + "UNIQUE(root_job_id,attempt_no),UNIQUE(owner_job_id,purpose,stamp_id,part_no),"
                + "CHECK ((record_kind='logical_task' AND max_attempts=3 AND call_count<=3"
                + " AND priority_no BETWEEN 1 AND 14) OR (record_kind<>'logical_task'"
                + " AND max_attempts IS NULL AND call_count=0 AND priority_no=0)),"
                + "CHECK (record_kind<>'ai_attempt' OR (root_job_id IS NOT NULL AND attempt_no BETWEEN 1 AND 3"
                + " AND request_json IS NOT NULL AND snapshot_owner_id IS NOT NULL AND input_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'snapshot_part' OR (owner_job_id IS NOT NULL"
                + " AND purpose IS NOT NULL AND stamp_id IS NOT NULL AND part_no IS NOT NULL"
                + " AND payload_json IS NOT NULL AND payload_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'disposition' OR (action_key_hash IS NOT NULL"
                + " AND target_type IS NOT NULL AND target_key_json IS NOT NULL"
                + " AND target_key_hash IS NOT NULL AND operator_id IS NOT NULL"
                + " AND reason IS NOT NULL AND acted_at IS NOT NULL AND action_payload IS NOT NULL"
                + " AND action_payload_hash IS NOT NULL)))");
        records = new JdbcJobRecords(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        entry = new JdbcSharedWriteEntry(source);
    }

    @Test void fiveKindsRemainTypedAndOnlyRootOwnsBudget() {
        assertThrows(IllegalStateException.class,
                () -> records.ensureIntent(intent("intent-a"), now));
        inTx(() -> {
            assertEquals("intent-a", records.ensureIntent(intent("intent-a"), now));
            assertEquals("intent-a", records.ensureIntent(new Intent("other-id", "daily:source-a",
                    1, "source_dedup", LocalDate.of(2026, 9, 30)), now));
            assertEquals("root-a", records.createRoot(root("root-a", 0), now));
            assertEquals("attempt-a", records.prepareAttempt(attempt("attempt-a", 1), now));
            assertEquals("part-a", records.putPart(new Part("part-a", "root-a", "input", "input",
                    0, "{\"members\":[]}", "a".repeat(64)), now));
            assertEquals("disposition-a", records.appendDisposition(new Disposition("disposition-a",
                    "action-a", "article", "{\"id\":\"fixture\"}", "operator-a",
                    "reviewed", "{}"), now));
        });
        assertEquals(5L, count());
        assertEquals(0, records.byId("root-a").callCount());
        assertEquals(0, records.byId("attempt-a").callCount());
        for (String id : new String[]{"intent-a", "root-a", "attempt-a", "part-a", "disposition-a"})
            assertFalse(records.modelSendable(id));
        jdbc.update("UPDATE jobs SET state='queued' WHERE id='root-a'");
        inTx(() -> assertEquals(1, records.reserveCall("root-a", "attempt-a", 0, now)));
        assertEquals(1, records.byId("root-a").callCount());
        assertEquals(0, records.byId("attempt-a").callCount());
        assertTrue(records.modelSendable("attempt-a"));
        assertFalse(records.modelSendable("root-a"));
    }

    @Test void preparedDoesNotConsumeBudgetAndRetriesCannotExceedRootThreeCalls() {
        inTx(() -> {
            records.ensureIntent(intent("intent-a"), now);
            records.createRoot(root("root-a", 0), now);
            records.prepareAttempt(attempt("attempt-a", 1), now);
        });
        assertEquals(0, records.byId("root-a").callCount());
        jdbc.update("UPDATE jobs SET state='queued' WHERE id='root-a'");
        inTx(() -> records.reserveCall("root-a", "attempt-a", 0, now));
        for (int number = 2; number <= 3; number++) {
            int current = number;
            jdbc.update("UPDATE jobs SET state='queued' WHERE id='root-a'");
            inTx(() -> {
                String id = "attempt-" + current;
                records.prepareAttempt(attempt(id, current), now);
                records.reserveCall("root-a", id, current - 1, now);
            });
        }
        assertEquals(3, records.byId("root-a").callCount());
        inTx(() -> assertThrows(Conflict.class,
                () -> records.prepareAttempt(attempt("attempt-four", 3), now)));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='ai_attempt'",
                Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM jobs"
                + " WHERE record_kind<>'logical_task' AND call_count>0", Long.class));
    }

    @Test void immutablePartAndAttemptIdentityRejectChangedPayload() {
        inTx(() -> {
            records.ensureIntent(intent("intent-a"), now);
            records.createRoot(root("root-a", 0), now);
            records.prepareAttempt(attempt("attempt-a", 1), now);
            records.putPart(new Part("part-a", "root-a", "input", "input", 0,
                    "{\"members\":[]}", "a".repeat(64)), now);
        });
        inTx(() -> {
            assertThrows(Conflict.class, () -> records.ensureIntent(new Intent("bad-intent",
                    "daily:source-a", 1, "different_type", LocalDate.of(2026, 9, 30)), now));
            assertThrows(Conflict.class, () -> records.createRoot(new Root("bad-root", "intent-a",
                    "daily:source-a", 1, 0, "source_dedup", 4), now));
            assertEquals("attempt-a", records.prepareAttempt(
                    new Attempt("other-attempt", "root-a", 1, "{\"request\":1}",
                            "root-a", "a".repeat(64)), now));
            assertThrows(Conflict.class, () -> records.prepareAttempt(
                    new Attempt("changed", "root-a", 1, "{\"request\":2}",
                            "root-a", "a".repeat(64)), now));
            assertThrows(Conflict.class, () -> records.prepareAttempt(
                    new Attempt("changed-hash", "root-a", 1, "{\"request\":1}",
                            "root-a", "b".repeat(64)), now));
            assertThrows(Conflict.class, () -> records.putPart(new Part("changed-part", "root-a",
                    "input", "input", 0, "{\"members\":[1]}", "b".repeat(64)), now));
            assertThrows(Conflict.class, () -> records.putPart(new Part("changed-part-hash", "root-a",
                    "input", "input", 0, "{\"members\":[]}", "b".repeat(64)), now));
        });
        assertEquals(4L, count());
    }

    @Test void shardAndSummaryRootsHoldSeparateThreeCallBudgets() {
        inTx(() -> {
            records.ensureIntent(intent("intent-a"), now);
            records.createRoot(root("root-a", 0), now);
            records.createRoot(root("summary-a", 1), now);
            records.prepareAttempt(attempt("shard-one", 1), now);
            records.prepareAttempt(new Attempt("summary-one", "summary-a", 1,
                    "{\"request\":2}", "summary-a", "b".repeat(64)), now);
        });
        assertEquals(0, records.byId("root-a").callCount());
        assertEquals(0, records.byId("summary-a").callCount());
        jdbc.update("UPDATE jobs SET state='queued' WHERE id IN ('root-a','summary-a')");
        inTx(() -> records.reserveCall("root-a", "shard-one", 0, now));
        assertEquals(1, records.byId("root-a").callCount());
        assertEquals(0, records.byId("summary-a").callCount());
        inTx(() -> records.reserveCall("summary-a", "summary-one", 0, now));
        assertEquals(1, records.byId("summary-a").callCount());
        assertEquals(1, records.byId("root-a").callCount());
    }

    private Intent intent(String id) {
        return new Intent(id, "daily:source-a", 1, "source_dedup", LocalDate.of(2026, 9, 30));
    }
    private Root root(String id, long part) {
        return new Root(id, "intent-a", "daily:source-a", 1, part, "source_dedup", 3);
    }
    private Attempt attempt(String id, int number) {
        return new Attempt(id, "root-a", number, "{\"request\":1}", "root-a", "a".repeat(64));
    }
    private void inTx(Runnable operation) {
        tx.executeWithoutResult(ignored -> { entry.lock(); operation.run(); });
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Long.class); }
}
