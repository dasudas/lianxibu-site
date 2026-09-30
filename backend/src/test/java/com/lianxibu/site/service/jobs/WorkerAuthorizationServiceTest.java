package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Authorization;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Candidate;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Identity;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.State;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.Store;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class WorkerAuthorizationServiceTest {
    private static final String REQUEST = "{\"provider\":\"deepseek\","
            + "\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\","
            + "\"content\":\"give json\"}],\"parameters\":{},"
            + "\"response_format\":{\"type\":\"json_object\"},"
            + "\"schema_version\":\"v1\",\"prompt_version\":\"v1\","
            + "\"input_hash\":\"" + "a".repeat(64) + "\"}";
    private final MutableClock clock = new MutableClock("2026-10-01T00:59:00+08:00");
    private JdbcTemplate jdbc;
    private SharedWriteExecutor writes;
    private StubStore store;
    private WorkerAuthorizationService service;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:worker-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(20),"
                + "slot_revision BIGINT,fencing BIGINT,attempt_id VARCHAR(64),token VARCHAR(64),"
                + "lease_until TIMESTAMP)");
        jdbc.update("INSERT INTO worker_lock VALUES(1,'free',0,0,NULL,NULL,NULL)");
        jdbc.execute("CREATE TABLE task(root_id VARCHAR(64) PRIMARY KEY,attempt_id VARCHAR(64),"
                + "priority INT,eligible_at TIMESTAMP,root_state VARCHAR(32),"
                + "attempt_state VARCHAR(32),dependency_ready BOOLEAN,call_count INT,"
                + "max_attempts INT,request_json CLOB,input_hash VARCHAR(64),revision BIGINT,"
                + "claim_action VARCHAR(128),auth_action VARCHAR(128),abort_action VARCHAR(128))");
        writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        store = new StubStore();
        service = new WorkerAuthorizationService(writes, store, clock, ignored -> 0);
    }

    @Test void onlyReadyMinimumPriorityClaimsAndPermitSendsOnce() {
        add("low", 14, true, 0);
        add("high", 1, true, 0);
        add("blocked", 1, false, 0);
        assertEquals(State.IDLE, service.claim(action("before")).state());
        assertEquals(0, count("high"));
        clock.set("2026-10-01T01:00:00+08:00");
        var claim = service.claim(action("claim-1"));
        assertEquals(State.CLAIMED, claim.state());
        assertEquals("high-attempt", claim.permit().attemptId());
        assertFalse(claim.permit().identity().toString().contains("give json"));
        assertSame(claim.permit(), service.claim(action("claim-1")).permit());
        assertEquals(1, count("high"));
        assertEquals(0, count("low"));
        AtomicInteger sent = new AtomicInteger();
        var first = service.dispatch(claim.permit(), request -> {
            sent.incrementAndGet();
            assertEquals(REQUEST, request);
            return "provider-result";
        });
        assertEquals(State.SENT, first.state());
        assertEquals("provider-result", first.outcome());
        assertEquals(State.LOST, service.dispatch(claim.permit(), ignored -> {
            sent.incrementAndGet(); return "bad";
        }).state());
        assertEquals(1, sent.get());
        assertEquals(State.IDLE, service.claim(action("other-while-busy")).state());
        var restarted = new WorkerAuthorizationService(writes, store, clock, ignored -> 0);
        var replay = restarted.claim(action("claim-1"));
        assertEquals(State.UNKNOWN, restarted.dispatch(replay.permit(), ignored -> {
            sent.incrementAndGet(); return "bad";
        }).state());
        assertEquals(1, sent.get());
    }

    @Test void pauseBeforeSendRefundsOnlyUnsentReservation() {
        add("root", 2, true, 0);
        clock.set("2026-10-01T06:59:00+08:00");
        var claim = service.claim(action("pause-claim"));
        store.paused = true;
        AtomicInteger sent = new AtomicInteger();
        assertEquals(State.NOT_SENT, service.dispatch(claim.permit(), ignored -> {
            sent.incrementAndGet(); return "sent";
        }).state());
        assertEquals(0, sent.get());
        assertEquals(0, count("root"));
        assertEquals("free", slotState());
        assertEquals("prepared", state("root", "attempt_state"));
        assertEquals("queued", state("root", "root_state"));
        assertEquals(State.LOST, service.dispatch(claim.permit(), ignored -> "sent").state());
    }

    @Test void crossingSevenBeforeAuthorizationRefundsAndDefersToNextDay() {
        add("root", 3, true, 0);
        clock.set("2026-10-01T06:59:59+08:00");
        var claim = service.claim(action("cross-claim"));
        clock.set("2026-10-01T07:00:00+08:00");
        assertEquals(State.NOT_SENT,
                service.dispatch(claim.permit(), ignored -> "must-not-send").state());
        assertEquals(0, count("root"));
        assertEquals("free", slotState());
        assertEquals(State.IDLE, service.claim(action("after-seven")).state());
        assertEquals(OffsetDateTime.parse("2026-10-02T01:00:00+08:00").toInstant(),
                jdbc.queryForObject("SELECT eligible_at FROM task WHERE root_id='root'",
                        Timestamp.class).toInstant());
    }

    @Test void lostOwnershipCannotSendOrClearAnotherSlotAndUnknownRetainsBudget() {
        add("root", 4, true, 0);
        clock.set("2026-10-01T02:00:00+08:00");
        var claim = service.claim(action("lost-claim"));
        jdbc.update("UPDATE worker_lock SET token='replacement',attempt_id='other-attempt',"
                + "slot_revision=slot_revision+1,fencing=fencing+1 WHERE id=1");
        AtomicInteger sent = new AtomicInteger();
        assertEquals(State.LOST, service.dispatch(claim.permit(), ignored -> {
            sent.incrementAndGet(); return "bad";
        }).state());
        assertEquals(0, sent.get());
        assertEquals("occupied", slotState());
        assertEquals(1, count("root"));
        assertEquals("other-attempt", jdbc.queryForObject(
                "SELECT attempt_id FROM worker_lock WHERE id=1", String.class));

        jdbc.update("UPDATE worker_lock SET slot_state='free',attempt_id=NULL,token=NULL WHERE id=1");
        add("second", 5, true, 0);
        var second = service.claim(action("unknown-claim"));
        assertEquals(State.UNKNOWN, service.dispatch(second.permit(), ignored -> {
            sent.incrementAndGet(); throw new IllegalStateException("network uncertain");
        }).state());
        assertEquals(1, sent.get());
        assertEquals(1, count("second"));
        assertEquals("occupied", slotState());
    }

    @Test void exhaustedAndNotYetEligibleAreNeverSelected() {
        add("exhausted", 1, true, 3);
        add("not-ready", 2, true, 0);
        add("incomplete", 1, true, 0);
        jdbc.update("UPDATE task SET request_json='{}' WHERE root_id='incomplete'");
        jdbc.update("UPDATE task SET eligible_at=? WHERE root_id='not-ready'",
                Timestamp.from(OffsetDateTime.parse("2026-10-02T01:00:00+08:00").toInstant()));
        clock.set("2026-10-01T02:00:00+08:00");
        assertEquals(State.IDLE, service.claim(action("none")).state());
        assertEquals("free", slotState());
    }

    @Test void localCancellationBeforeSendRefundsWithoutHttp() {
        add("root", 1, true, 0);
        clock.set("2026-10-01T02:00:00+08:00");
        var claim = service.claim(action("cancel-claim"));
        claim.permit().cancelBeforeSend();
        assertEquals(State.NOT_SENT, service.dispatch(claim.permit(), ignored -> {
            fail("cancelled attempt must not send"); return "bad";
        }).state());
        assertEquals(0, count("root"));
        assertEquals("free", slotState());
    }

    private void add(String id, int priority, boolean dependency, int callCount) {
        jdbc.update("INSERT INTO task VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
                id, id + "-attempt", priority,
                Timestamp.from(OffsetDateTime.parse("2026-10-01T01:00:00+08:00").toInstant()),
                "queued", "prepared", dependency, callCount, 3,
                REQUEST, "a".repeat(64), 0);
    }
    private int count(String id) {
        return jdbc.queryForObject("SELECT call_count FROM task WHERE root_id=?", Integer.class, id);
    }
    private String state(String id, String column) {
        if (!List.of("root_state", "attempt_state").contains(column)) throw new AssertionError();
        return jdbc.queryForObject("SELECT " + column + " FROM task WHERE root_id=?",
                String.class, id);
    }
    private String slotState() {
        return jdbc.queryForObject("SELECT slot_state FROM worker_lock WHERE id=1", String.class);
    }
    private static ActionContext action(String key) { return new ActionContext(key, key, null); }

    private final class StubStore implements Store {
        private boolean paused;
        @Override public Identity committedClaim(String key) {
            var rows = jdbc.query("SELECT task.root_id,task.attempt_id,worker_lock.token,"
                            + "worker_lock.slot_revision,worker_lock.fencing,worker_lock.lease_until,"
                            + "task.request_json,task.input_hash FROM task JOIN worker_lock"
                            + " ON worker_lock.id=1 WHERE claim_action=?",
                    (rs, ignored) -> identity(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getLong(5), rs.getTimestamp(6),
                            rs.getString(7), rs.getString(8)), key);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        @Override public Authorization committedAuthorization(String key) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE auth_action=?",
                    Integer.class, key);
            return found == 1 ? new Authorization(false, "already_authorized") : null;
        }
        @Override public Boolean committedAbort(String key) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE abort_action=?",
                    Integer.class, key);
            return found == 1 ? true : null;
        }
        @Override public boolean paused() { return paused; }
        @Override public boolean slotFree() { return "free".equals(slotState()); }
        @Override public List<Candidate> candidates(Instant now) {
            return jdbc.query("SELECT root_id,attempt_id,priority,eligible_at,root_state,"
                    + "attempt_state,dependency_ready,call_count,max_attempts,request_json,"
                    + "input_hash,revision FROM task", (rs, ignored) -> new Candidate(
                    rs.getString(1), rs.getString(2), rs.getInt(3),
                    rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6),
                    rs.getBoolean(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
                    rs.getString(11), rs.getLong(12)));
        }
        @Override public Identity reserve(Candidate chosen, String token, Instant lease,
                                          Instant now, String actionKey) {
            int root = jdbc.update("UPDATE task SET call_count=call_count+1,"
                    + "root_state='running',attempt_state='running',revision=revision+1,"
                    + "claim_action=? WHERE root_id=? AND revision=? AND root_state='queued'"
                    + " AND call_count<max_attempts", actionKey, chosen.rootId(),
                    chosen.rootRevision());
            int slot = jdbc.update("UPDATE worker_lock SET slot_state='occupied',"
                    + "slot_revision=slot_revision+1,fencing=fencing+1,attempt_id=?,token=?,"
                    + "lease_until=? WHERE id=1 AND slot_state='free'", chosen.attemptId(),
                    token, Timestamp.from(lease));
            if (root != 1 || slot != 1) throw new IllegalStateException("claim conflict");
            return jdbc.queryForObject("SELECT slot_revision,fencing FROM worker_lock WHERE id=1",
                    (rs, ignored) -> new Identity(chosen.rootId(), chosen.attemptId(), token,
                            rs.getLong(1), rs.getLong(2), lease,
                            chosen.frozenRequestJson(), chosen.inputHash()));
        }
        @Override public Authorization authorize(Identity id, Instant now, String actionKey) {
            if (!owns(id, now)) return new Authorization(false, "lost_ownership");
            jdbc.update("UPDATE task SET auth_action=? WHERE root_id=? AND root_state='running'",
                    actionKey, id.rootId());
            return new Authorization(true, null);
        }
        @Override public Boolean refundNotSent(Identity id, Instant eligible, String reason,
                                               String actionKey) {
            if (!owns(id, clock.instant())) return false;
            int task = jdbc.update("UPDATE task SET call_count=call_count-1,"
                    + "root_state='queued',attempt_state='prepared',eligible_at=?,"
                    + "abort_action=?,revision=revision+1 WHERE root_id=? AND call_count>0"
                    + " AND root_state='running'", Timestamp.from(eligible), actionKey, id.rootId());
            int slot = jdbc.update("UPDATE worker_lock SET slot_state='free',attempt_id=NULL,"
                    + "token=NULL,lease_until=NULL,slot_revision=slot_revision+1,"
                    + "fencing=fencing+1 WHERE id=1 AND token=? AND attempt_id=?",
                    id.dispatchToken(), id.attemptId());
            if (task != 1 || slot != 1) throw new IllegalStateException("refund conflict");
            return true;
        }
        private boolean owns(Identity id, Instant now) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM worker_lock WHERE id=1"
                    + " AND slot_state='occupied' AND attempt_id=? AND token=?"
                    + " AND slot_revision=? AND fencing=? AND lease_until>?",
                    Integer.class, id.attemptId(), id.dispatchToken(), id.slotRevision(),
                    id.fencing(), Timestamp.from(now));
            return count == 1;
        }
        private Identity identity(String root, String attempt, String token, long revision,
                                  long fencing, Timestamp lease, String request, String hash) {
            return new Identity(root, attempt, token, revision, fencing,
                    lease.toInstant(), request, hash);
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
