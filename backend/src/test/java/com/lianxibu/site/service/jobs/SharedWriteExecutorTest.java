package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.CommitUnknown;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.WriteUnavailable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SharedWriteExecutorTest {
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private JdbcSharedWriteEntry entry;
    private final ActionContext context = new ActionContext("fixture-action", "preallocated-resource", "fixture-hash");

    @BeforeEach void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:shared-write-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY,slot_state VARCHAR(16),slot_revision BIGINT)");
        jdbc.update("INSERT INTO worker_lock VALUES(?,?,?)", 1, "occupied", 7);
        jdbc.execute("CREATE TABLE business(id INT PRIMARY KEY,value_no INT)");
        jdbc.update("INSERT INTO business VALUES(?,?)", 1, 0);
        jdbc.execute("CREATE TABLE receipts(action_key VARCHAR(128) PRIMARY KEY,result_text VARCHAR(64))");
        tx = new DataSourceTransactionManager(source);
        entry = new JdbcSharedWriteEntry(source);
    }

    @Test void entryMustJoinOuterTransactionAndStableReceiptPreventsDuplicateWork() {
        assertThrows(IllegalStateException.class, entry::lock);
        var executor = new SharedWriteExecutor(entry, tx, () -> 25, ignored -> { });
        AtomicInteger calls = new AtomicInteger();
        String first = executor.execute(context, this::receipt, action -> {
            calls.incrementAndGet();
            assertEquals(context, action);
            jdbc.update("UPDATE business SET value_no=value_no+1 WHERE id=1");
            jdbc.update("INSERT INTO receipts VALUES(?,?)", action.actionKey(), "committed");
            return "committed";
        });
        assertEquals("committed", first);
        assertEquals("committed", executor.execute(context, this::receipt,
                ignored -> { throw new AssertionError("must not rerun committed action"); }));
        assertEquals(1, calls.get());
        assertEquals(1, value());
        assertEquals("occupied", jdbc.queryForObject("SELECT slot_state FROM worker_lock", String.class));
        assertEquals(7L, jdbc.queryForObject("SELECT slot_revision FROM worker_lock", Long.class));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tx).execute(status ->
                executor.execute(context, this::receipt, ignored -> "no")));
    }

    @Test void deadlockRetriesWholeTransactionWithSameIdentityAndNoPartialWrites() {
        List<Integer> pauses = new ArrayList<>();
        var executor = new SharedWriteExecutor(entry, tx, () -> 25, pauses::add);
        AtomicInteger attempts = new AtomicInteger();
        String result = executor.execute(context, this::receipt, action -> {
            assertEquals(context, action);
            jdbc.update("UPDATE business SET value_no=value_no+1 WHERE id=1");
            if (attempts.getAndIncrement() == 0) throw new CannotAcquireLockException("fixture deadlock");
            jdbc.update("INSERT INTO receipts VALUES(?,?)", action.actionKey(), "ok");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, attempts.get());
        assertEquals(List.of(25), pauses);
        assertEquals(1, value());
    }

    @Test void retriesAreBoundedAndFailedAttemptsRollBack() {
        List<Integer> pauses = new ArrayList<>();
        var executor = new SharedWriteExecutor(entry, tx, () -> 100, pauses::add);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(WriteUnavailable.class, () -> executor.execute(context, this::receipt, action -> {
            attempts.incrementAndGet();
            jdbc.update("UPDATE business SET value_no=value_no+1 WHERE id=1");
            throw new CannotAcquireLockException("fixture lock timeout");
        }));
        assertEquals(3, attempts.get());
        assertEquals(List.of(100, 100), pauses);
        assertEquals(0, value());
        assertFalse(jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Long.class) > 0);
    }

    @Test void uncertainCommitQueriesDurableReceiptWithoutReexecutingWork() {
        var uncertainTx = new CommitAfterThrowManager(source);
        var executor = new SharedWriteExecutor(entry, uncertainTx, () -> 25, ignored -> { });
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute(context, this::receipt, action -> {
            calls.incrementAndGet();
            jdbc.update("UPDATE business SET value_no=value_no+1 WHERE id=1");
            jdbc.update("INSERT INTO receipts VALUES(?,?)", action.actionKey(), "committed");
            uncertainTx.failNextCommit = true;
            return "committed";
        });
        assertEquals("committed", result);
        assertEquals(1, calls.get());
        assertEquals(1, value());
    }

    @Test void uncertainCommitWithoutReceiptStopsInsteadOfBlindRetry() {
        var uncertainTx = new CommitAfterThrowManager(source);
        var executor = new SharedWriteExecutor(entry, uncertainTx, () -> 25, ignored -> { });
        AtomicInteger calls = new AtomicInteger();
        assertThrows(CommitUnknown.class, () -> executor.execute(context, this::receipt, action -> {
            calls.incrementAndGet();
            jdbc.update("UPDATE business SET value_no=value_no+1 WHERE id=1");
            uncertainTx.failNextCommit = true;
            return "unconfirmed";
        }));
        assertEquals(1, calls.get());
        assertEquals(1, value());
    }

    private String receipt(String key) {
        List<String> rows = jdbc.query("SELECT result_text FROM receipts WHERE action_key=?",
                (rs, ignored) -> rs.getString(1), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private int value() { return jdbc.queryForObject("SELECT value_no FROM business WHERE id=1", Integer.class); }

    private static final class CommitAfterThrowManager extends DataSourceTransactionManager {
        private boolean failNextCommit;
        CommitAfterThrowManager(JdbcDataSource source) { super(source); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (failNextCommit) {
                failNextCommit = false;
                throw new TransactionSystemException("fixture commit acknowledgment lost");
            }
        }
    }
}
