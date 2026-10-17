package com.lianxibu.site.repository.jobs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Acquires the sole shared writer entry row without changing AI slot state. */
public final class JdbcSharedWriteEntry {
    private final DataSource source;
    private final ThreadLocal<JdbcBacklogMutationWitness.Scope> mutation = new ThreadLocal<>();
    private final JdbcTemplate jdbc;
    private final java.util.function.LongSupplier monotonic;
    private final java.util.function.LongConsumer observer;

    public JdbcSharedWriteEntry(DataSource source) {
        this(source, System::nanoTime, nanos -> ProcessApiRuntimeTelemetry.nativeProcess().completedLockWait(nanos));
    }

    public JdbcSharedWriteEntry(DataSource source, java.util.function.LongSupplier monotonic, java.util.function.LongConsumer observer) {
        if (source == null || monotonic == null || observer == null) throw new IllegalArgumentException("shared entry dependencies required");
        this.source = source;
        jdbc = new JdbcTemplate(source);
        this.monotonic = monotonic;
        this.observer = observer;
    }

    /** Process-local admission only. The native row must still be locked in the transaction. */
    public interface Admission extends AutoCloseable { @Override void close(); }
    private static final java.util.Map<DataSource,java.util.concurrent.locks.ReentrantLock> LOCAL = new java.util.WeakHashMap<>();
    private static synchronized java.util.concurrent.locks.ReentrantLock local(DataSource source) {
        return LOCAL.computeIfAbsent(source, ignored -> new java.util.concurrent.locks.ReentrantLock(true));
    }
    public Admission admit() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("local admission must precede the transaction");
        var lock = local(source);
        if (lock.isHeldByCurrentThread()) throw new IllegalStateException("local admission is not reentrant");
        try {
            if (!lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                throw new org.springframework.dao.CannotAcquireLockException("local writer admission busy");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new org.springframework.dao.CannotAcquireLockException("local writer admission interrupted", interrupted);
        }
        var owner = Thread.currentThread();
        return new Admission() {
            private boolean closed;
            @Override public void close() {
                if (Thread.currentThread() != owner) throw new IllegalStateException("local admission owner required");
                if (!closed) { closed = true; lock.unlock(); }
            }
        };
    }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("shared entry requires an active transaction");
        }
        mutation.set(JdbcBacklogMutationWitness.capture(source));
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            if (connection.getMetaData().getDatabaseProductName().contains("MySQL")) {
                lockMySql();
            } else {
                lockRow();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("shared entry connection unavailable", error);
        }
    }

    public void recordMutation(String action, String allocated) { var scope = mutation.get(); if (scope != null) scope.record(action, allocated); }
    public void releaseMutation() { mutation.remove(); }

    private void lockMySql() {
        Integer previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        if (previous == null) throw new IllegalStateException("lock wait timeout unavailable");
        try {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
            lockRow();
        } finally {
            // Values are read from this same connection, never from user input.
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
        }
    }

    private void lockRow() {
        long began;
        boolean started;
        try { began = monotonic.getAsLong(); started = true; } catch (RuntimeException unavailable) { began = 0; started = false; }
        try {
            List<Integer> rows = jdbc.query("SELECT id FROM worker_lock FOR UPDATE", (rs, ignored) -> rs.getInt(1));
            if (rows.size() != 1) throw new IllegalStateException("shared entry row is not unique");
        } finally {
            long elapsed;
            try { elapsed = started ? Math.subtractExact(monotonic.getAsLong(), began) : -1; } catch (RuntimeException unavailable) { elapsed = -1; }
            try { observer.accept(elapsed); } catch (RuntimeException unavailable) { /* Observation does not change the original transaction outcome. */ }
        }
    }
}
