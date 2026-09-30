package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.Attempt;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.Cause;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.LateEvidence;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.Result;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.State;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.Store;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class UnknownCallRecoveryServiceTest {
    private final MutableClock clock = new MutableClock("2026-10-01T02:00:00+08:00");
    private JdbcTemplate jdbc;
    private StubStore store;
    private UnknownCallRecoveryService service;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:unknown-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(20),"
                + "slot_revision BIGINT,fencing BIGINT,attempt_id VARCHAR(64),token VARCHAR(64))");
        jdbc.update("INSERT INTO worker_lock VALUES(1,'occupied',1,1,'attempt-1','dispatch-1')");
        jdbc.execute("CREATE TABLE call_state(root_id VARCHAR(64) PRIMARY KEY,"
                + "attempt_id VARCHAR(64),dispatch_token VARCHAR(64),slot_revision BIGINT,"
                + "fencing BIGINT,lease_until TIMESTAMP,started_at TIMESTAMP,"
                + "owner_epoch VARCHAR(64),root_state VARCHAR(32),attempt_state VARCHAR(32),"
                + "root_revision BIGINT,call_count INT,usage_state VARCHAR(32),"
                + "release_reason VARCHAR(64),operator_id VARCHAR(64),close_reason VARCHAR(2048))");
        jdbc.update("INSERT INTO call_state VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "root-1", "attempt-1", "dispatch-1", 1L, 1L,
                timestamp("2026-10-01T02:02:30+08:00"),
                timestamp("2026-10-01T02:00:00+08:00"), "old-epoch", "running",
                "running", 0L, 1, "unknown", null, null, null);
        jdbc.execute("CREATE TABLE action_receipt(action_key VARCHAR(128) PRIMARY KEY,"
                + "action_hash VARCHAR(64),"
                + "state VARCHAR(32),slot_released BOOLEAN,call_count INT,"
                + "usage_state VARCHAR(32),reason VARCHAR(64))");
        jdbc.execute("CREATE TABLE late_evidence(action_key VARCHAR(128) PRIMARY KEY,"
                + "action_hash VARCHAR(64),"
                + "attempt_id VARCHAR(64),provider_id VARCHAR(128),prompt_tokens BIGINT,"
                + "completion_tokens BIGINT,result_hash VARCHAR(64),note VARCHAR(2048))");
        store = new StubStore();
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        service = new UnknownCallRecoveryService(writes, store, clock);
    }

    @Test void timeoutKeepsBudgetAndUnknownUsageAndRepeatedReleaseIsSafe() {
        assertThrows(UnknownCallRecoveryService.Rejected.class,
                () -> service.release("attempt-1", Cause.TIMEOUT, action("too-early")));
        clock.set("2026-10-01T02:01:30+08:00");
        Result first = service.release("attempt-1", Cause.TIMEOUT, action("timeout"));
        assertEquals(State.UNKNOWN_EXTERNAL, first.state());
        assertTrue(first.slotReleased());
        assertEquals(1, first.callCount());
        assertEquals("unknown", first.usageState());
        assertEquals("free", slot());
        assertEquals("unknown_external", rootState());
        assertEquals(1, jdbc.queryForObject("SELECT call_count FROM call_state", Integer.class));
        assertEquals(first, service.release("attempt-1", Cause.TIMEOUT, action("timeout")));
        assertThrows(IllegalStateException.class, () -> service.release("attempt-1",
                Cause.LEASE_EXPIRED, action("timeout")));
        assertFalse(service.release("attempt-1", Cause.TIMEOUT,
                action("another-release")).slotReleased());
    }

    @Test void oldTimeoutAndLateReplyCannotClearNewOccupantOrApply() {
        jdbc.update("UPDATE worker_lock SET attempt_id='attempt-2',token='dispatch-2',"
                + "slot_revision=2,fencing=2 WHERE id=1");
        clock.set("2026-10-01T02:01:31+08:00");
        Result released = service.release("attempt-1", Cause.TIMEOUT,
                action("old-timeout"));
        assertFalse(released.slotReleased());
        assertEquals("attempt-2", occupant());
        assertTrue(service.appendLate("attempt-1", new LateEvidence("provider-1", 8L,
                4L, "a".repeat(64), "late response"), action("late-1")));
        assertThrows(IllegalStateException.class, () -> service.appendLate("attempt-1",
                new LateEvidence("provider-1", 9L, 4L, "a".repeat(64),
                        "late response"), action("late-1")));
        assertEquals(8L, jdbc.queryForObject("SELECT prompt_tokens FROM late_evidence",
                Long.class));
        assertEquals("attempt-2", occupant());
        assertEquals("unknown_external", rootState());
        assertEquals(1, jdbc.queryForObject("SELECT call_count FROM call_state", Integer.class));
        assertTrue(service.appendLate("attempt-1", new LateEvidence("provider-1", 8L,
                4L, "a".repeat(64), "late response"), action("late-1")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM late_evidence", Integer.class));
    }

    @Test void stoppedProcessAndExpiredLeaseNeedRealProof() {
        assertThrows(UnknownCallRecoveryService.Rejected.class,
                () -> service.release("attempt-1", Cause.STOPPED_PROCESS,
                        action("unproven-restart")));
        assertEquals("occupied", slot());
        store.stopped.add("old-epoch");
        assertTrue(service.release("attempt-1", Cause.STOPPED_PROCESS,
                action("proven-restart")).slotReleased());

        setup();
        clock.set("2026-10-01T02:02:30+08:00");
        assertTrue(service.release("attempt-1", Cause.LEASE_EXPIRED,
                action("lease-expired")).slotReleased());
    }

    @Test void manualCloseChecksRoleRevisionAndDoesNotRefundOrClearNewSlot() {
        clock.set("2026-10-01T02:02:30+08:00");
        service.release("attempt-1", Cause.LEASE_EXPIRED, action("lease"));
        jdbc.update("UPDATE worker_lock SET slot_state='occupied',attempt_id='attempt-2',"
                + "token='dispatch-2',slot_revision=3,fencing=3 WHERE id=1");
        assertThrows(UnknownCallRecoveryService.Rejected.class,
                () -> service.closeUnresolved("attempt-1", 1, "admin", "verified unknown",
                        false, action("not-admin")));
        assertThrows(UnknownCallRecoveryService.Rejected.class,
                () -> service.closeUnresolved("attempt-1", 0, "admin", "verified unknown",
                        true, action("stale")));
        Result closed = service.closeUnresolved("attempt-1", 1, "admin",
                "verified unknown", true, action("close"));
        assertEquals(State.CLOSED_UNRESOLVED, closed.state());
        assertEquals("blocked", rootState());
        assertEquals("unknown_external", jdbc.queryForObject(
                "SELECT attempt_state FROM call_state", String.class));
        assertEquals("attempt-2", occupant());
        assertEquals(1, closed.callCount());
        assertEquals("unknown", closed.usageState());
    }

    @Test void releaseFailureRollsBackUnknownStateAndSlot() {
        store.failAfterUpdate = true;
        clock.set("2026-10-01T02:02:30+08:00");
        assertThrows(IllegalStateException.class,
                () -> service.release("attempt-1", Cause.LEASE_EXPIRED,
                        action("failing-release")));
        assertEquals("running", rootState());
        assertEquals("occupied", slot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM action_receipt", Integer.class));
    }

    private String slot() { return jdbc.queryForObject("SELECT slot_state FROM worker_lock", String.class); }
    private String occupant() { return jdbc.queryForObject("SELECT attempt_id FROM worker_lock", String.class); }
    private String rootState() { return jdbc.queryForObject("SELECT root_state FROM call_state", String.class); }
    private static Timestamp timestamp(String value) {
        return Timestamp.from(OffsetDateTime.parse(value).toInstant());
    }
    private static ActionContext action(String key) { return new ActionContext(key, key, null); }

    private final class StubStore implements Store {
        private final Set<String> stopped = new HashSet<>();
        private boolean failAfterUpdate;
        @Override public Result committedRelease(String key, String hash) {
            List<Object[]> rows = jdbc.query("SELECT action_hash,state,slot_released,call_count,"
                    + "usage_state,reason"
                    + " FROM action_receipt WHERE action_key=?", (rs, ignored) ->
                    new Object[]{rs.getString(1), new Result(State.valueOf(rs.getString(2)),
                            rs.getBoolean(3), rs.getInt(4), rs.getString(5),
                            rs.getString(6))}, key);
            if (rows.isEmpty()) return null;
            if (!hash.equals(rows.getFirst()[0])) throw new IllegalStateException("action conflict");
            return (Result) rows.getFirst()[1];
        }
        @Override public Boolean committedLateEvidence(String key, String hash) {
            List<String> rows = jdbc.query("SELECT action_hash FROM late_evidence"
                    + " WHERE action_key=?", (rs, ignored) -> rs.getString(1), key);
            if (rows.isEmpty()) return null;
            if (!hash.equals(rows.getFirst())) throw new IllegalStateException("action conflict");
            return true;
        }
        @Override public Attempt attempt(String id) {
            List<Attempt> rows = jdbc.query("SELECT root_id,attempt_id,dispatch_token,"
                    + "slot_revision,fencing,lease_until,started_at,owner_epoch,root_state,"
                    + "attempt_state,root_revision,call_count FROM call_state WHERE attempt_id=?",
                    (rs, ignored) -> new Attempt(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getLong(5),
                            rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(),
                            rs.getString(8), rs.getString(9), rs.getString(10),
                            rs.getLong(11), rs.getInt(12)), id);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        @Override public boolean stoppedOwner(String epoch) { return stopped.contains(epoch); }
        @Override public Result releaseUnknown(Attempt value, Cause cause, Instant now,
                                               String key, String hash) {
            if ("running".equals(value.attemptState())) {
                int changed = jdbc.update("UPDATE call_state SET root_state='unknown_external',"
                        + "attempt_state='unknown_external',root_revision=root_revision+1,"
                        + "release_reason=? WHERE root_id=? AND root_revision=?"
                        + " AND attempt_state='running'", cause.name(), value.rootId(),
                        value.rootRevision());
                if (changed != 1) throw new IllegalStateException("root conflict");
            }
            int slot = releaseMatching(value);
            if (failAfterUpdate) throw new IllegalStateException("fault after state update");
            Result result = new Result(State.UNKNOWN_EXTERNAL, slot == 1, value.callCount(),
                    "unknown", cause.name());
            receipt(key, hash, result);
            return result;
        }
        @Override public Result closeUnresolved(Attempt value, long revision,
                                               String operator, String reason, Instant now,
                                               String key, String hash) {
            int changed = jdbc.update("UPDATE call_state SET root_state='blocked',"
                    + "root_revision=root_revision+1,operator_id=?,close_reason=?"
                    + " WHERE root_id=? AND root_revision=? AND root_state='unknown_external'",
                    operator, reason, value.rootId(), revision);
            if (changed != 1) throw new IllegalStateException("close conflict");
            int slot = releaseMatching(value);
            Result result = new Result(State.CLOSED_UNRESOLVED, slot == 1,
                    value.callCount(), "unknown", "closed_unresolved");
            receipt(key, hash, result);
            return result;
        }
        @Override public Boolean appendLate(Attempt value, LateEvidence evidence,
                                            Instant now, String key, String hash) {
            jdbc.update("INSERT INTO late_evidence VALUES(?,?,?,?,?,?,?,?)", key, hash,
                    value.attemptId(), evidence.providerRequestId(), evidence.promptTokens(),
                    evidence.completionTokens(), evidence.resultHash(), evidence.note());
            return true;
        }
        private int releaseMatching(Attempt value) {
            return jdbc.update("UPDATE worker_lock SET slot_state='free',attempt_id=NULL,"
                    + "token=NULL,slot_revision=slot_revision+1,fencing=fencing+1 WHERE id=1"
                    + " AND slot_state='occupied' AND attempt_id=? AND token=?"
                    + " AND slot_revision=? AND fencing=?", value.attemptId(),
                    value.dispatchToken(), value.slotRevision(), value.fencing());
        }
        private void receipt(String key, String hash, Result value) {
            jdbc.update("INSERT INTO action_receipt VALUES(?,?,?,?,?,?,?)", key, hash,
                    value.state().name(), value.slotReleased(), value.callCount(),
                    value.usageState(), value.reason());
        }
    }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(String value) { set(value); }
        void set(String value) { now = OffsetDateTime.parse(value).toInstant(); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
