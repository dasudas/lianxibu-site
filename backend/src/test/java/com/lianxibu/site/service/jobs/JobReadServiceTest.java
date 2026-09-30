package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcJobReads;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JobReadServiceTest {
    private JdbcTemplate jdbc;
    private JobReadService reads;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:jobs-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,record_kind VARCHAR(24),"
                + "task_type VARCHAR(32),state VARCHAR(32),revision BIGINT,priority_no INT,"
                + "eligible_at TIMESTAMP,input_state VARCHAR(16),coverage_state VARCHAR(16),"
                + "error_code VARCHAR(64),call_count INT,max_attempts INT,attempt_no INT,"
                + "usage_json CLOB,cost_decimal DECIMAL(20,8),slot_released_at TIMESTAMP,"
                + "release_reason VARCHAR(32),created_at TIMESTAMP,request_json CLOB,payload_json CLOB)");
        jdbc.execute("CREATE TABLE request_receipts(id VARCHAR(64) PRIMARY KEY,receipt_kind VARCHAR(32),"
                + "original_status VARCHAR(32),current_status VARCHAR(32),created_at TIMESTAMP,"
                + "expires_at TIMESTAMP,response_json CLOB,request_key VARCHAR(128))");
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(16),"
                + "slot_revision BIGINT,job_id VARCHAR(64),lease_until TIMESTAMP,holder VARCHAR(64))");
        reads = new JobReadService(new JdbcJobReads(source), new DataSourceTransactionManager(source),
                Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void knownLogicalTaskCanBeCandidateUnknownKindCannotRun() {
        job("safe", "logical_task", "queued", null, 1, null);
        job("mystery", "alien_task", "queued", null, 1, null);
        assertTrue(reads.job("safe").orElseThrow().workerCandidate());
        var unknown = reads.job("mystery").orElseThrow();
        assertEquals(JobReadService.Kind.UNKNOWN, unknown.kind());
        assertFalse(unknown.workerCandidate());
        assertNull(unknown.taskType());
        assertEquals(2, reads.jobs(null, 1, 10).total());
        assertThrows(IllegalArgumentException.class, () -> reads.jobs("alien_task", 1, 10));
    }

    @Test void usageAndWaitingSummaryExcludePromptsAndRawRequest() {
        job("attempt", "ai_attempt", "waiting_retry",
                "{\"input_tokens\":12,\"output_tokens\":4,\"prompt\":\"SECRET_PROMPT_SENTINEL\"}",
                0, 1);
        var value = reads.job("attempt").orElseThrow();
        assertEquals(12L, value.usage().inputTokens());
        assertEquals(4L, value.usage().outputTokens());
        assertEquals("1.50000000", value.usage().cost());
        assertEquals(java.util.List.of("waiting_retry"), value.waitingReasons());
        assertFalse(value.toString().contains("SECRET_PROMPT_SENTINEL"));
        assertFalse(value.toString().contains("SECRET_REQUEST_SENTINEL"));
        assertFalse(value.toString().contains("SECRET_PAYLOAD_SENTINEL"));
    }

    @Test void receiptAndSlotExposeOnlySafeMetadata() {
        jdbc.update("INSERT INTO request_receipts VALUES (?,?,?,?,?,?,?,?)", "receipt", "admin_action",
                "accepted", "accepted", time("2026-01-01T00:00:00Z"),
                time("2026-01-01T12:00:00Z"), "SECRET_RESPONSE_SENTINEL", "SECRET_KEY_SENTINEL");
        jdbc.update("INSERT INTO worker_lock VALUES (?,?,?,?,?,?)", 1, "occupied", 3,
                "attempt", time("2026-01-02T00:01:00Z"), "SECRET_HOLDER_SENTINEL");
        var receipt = reads.receipt("receipt").orElseThrow();
        assertTrue(receipt.expired());
        assertFalse(receipt.toString().contains("SECRET_RESPONSE_SENTINEL"));
        var slot = reads.slot().orElseThrow();
        assertEquals("occupied", slot.state());
        assertEquals("attempt", slot.occupiedJobId());
        assertFalse(slot.toString().contains("SECRET_HOLDER_SENTINEL"));
    }

    private void job(String id, String kind, String state, String usage, int priority, Integer attempt) {
        jdbc.update("INSERT INTO jobs(id,record_kind,task_type,state,revision,priority_no,"
                        + "eligible_at,input_state,coverage_state,error_code,call_count,max_attempts,"
                        + "attempt_no,usage_json,cost_decimal,slot_released_at,release_reason,"
                        + "created_at,request_json,payload_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, kind, "fixture", state, 1, priority, time("2026-01-01T00:00:00Z"),
                null, null, "fixture_error", 0, "logical_task".equals(kind) ? 3 : null,
                attempt, usage, usage == null ? null : "1.50000000", null, null,
                time("2026-01-01T00:00:00Z"), "SECRET_REQUEST_SENTINEL", "SECRET_PAYLOAD_SENTINEL");
    }

    private static Timestamp time(String value) { return Timestamp.from(Instant.parse(value)); }
}
