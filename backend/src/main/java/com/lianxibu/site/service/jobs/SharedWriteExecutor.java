package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns a complete short database transaction; no network operation belongs in Work. */
public final class SharedWriteExecutor {
    public record ActionContext(String actionKey, String preallocatedResourceId,
                                String frozenSnapshotHash) {
        public ActionContext {
            if (actionKey == null || actionKey.isBlank() || actionKey.length() > 128
                    || preallocatedResourceId == null || preallocatedResourceId.isBlank()
                    || preallocatedResourceId.length() > 64) {
                throw new IllegalArgumentException("stable write identity required");
            }
        }
    }
    @FunctionalInterface public interface CommittedResult<T> {
        /** Query a durable action receipt by its stable key; null means no committed result. */
        T find(String actionKey);
    }
    @FunctionalInterface public interface Work<T> {
        /** Database-only work; downstream module writers join this exact transaction. */
        T apply(ActionContext context);
    }
    @FunctionalInterface public interface Sleeper { void pause(int millis) throws InterruptedException; }

    public static final class CommitUnknown extends RuntimeException {
        public CommitUnknown(Throwable cause) { super("write commit result is unknown", cause); }
    }
    public static final class WriteUnavailable extends RuntimeException {
        public WriteUnavailable(Throwable cause) { super("shared write unavailable", cause); }
    }

    private final JdbcSharedWriteEntry entry;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final IntSupplier delay;
    private final Sleeper sleeper;

    public SharedWriteExecutor(JdbcSharedWriteEntry entry, PlatformTransactionManager tx) {
        this(entry, tx, () -> ThreadLocalRandom.current().nextInt(25, 101), Thread::sleep);
    }

    public SharedWriteExecutor(JdbcSharedWriteEntry entry, PlatformTransactionManager tx,
                               IntSupplier delay, Sleeper sleeper) {
        if (entry == null || tx == null || delay == null || sleeper == null) {
            throw new IllegalArgumentException("shared write dependencies required");
        }
        this.entry = entry;
        this.delay = delay;
        this.sleeper = sleeper;
        writes = new TransactionTemplate(tx);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(1);
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(ActionContext context, CommittedResult<T> receipt, Work<T> work) {
        if (context == null || receipt == null || work == null) {
            throw new IllegalArgumentException("shared write action required");
        }
        return executePrepared(context,receipt,()->work);
    }

    /** Resolve the durable receipt first, then prepare once outside every transaction.
     * Each writer retry still rechecks its receipt and all native permissions. */
    public <T> T executePrepared(ActionContext context,CommittedResult<T> receipt,
                                 java.util.function.Supplier<Work<T>> preparation) {
        if(context==null||receipt==null||preparation==null)throw new IllegalArgumentException("shared prepared write action required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("shared writer must own the outer transaction");
        }
        T existing = lookup(context, receipt);
        if (existing != null) return existing;
        Work<T> work=preparation.get();
        if(work==null)throw new IllegalStateException("prepared writer work required");
        for (int attempt = 0; attempt < 3; attempt++) {
            try (var admission = java.util.Objects.requireNonNull(entry.admit())) {
                T value = writes.execute(status -> {
                    try {
                    entry.lock();
                    // Another caller may have committed this key while we waited for the writer lock.
                    T committed = receipt.find(context.actionKey());
                    if (committed != null) return committed;
                    T result = work.apply(context);
                    if (result == null) throw new IllegalStateException("write result required");
                    entry.recordMutation(context.actionKey(), context.preallocatedResourceId());
                    return result;
                    } finally { entry.releaseMutation(); }
                });
                return value;
            } catch (RuntimeException failure) {
                if (retryable(failure)) {
                    if (attempt == 2) throw new WriteUnavailable(failure);
                    pause();
                    continue;
                }
                if (commitUncertain(failure)) {
                    try {
                        T committed = lookup(context, receipt);
                        if (committed != null) return committed;
                    } catch (RuntimeException unavailable) {
                        failure.addSuppressed(unavailable);
                    }
                    throw new CommitUnknown(failure);
                }
                throw failure;
            }
        }
        throw new IllegalStateException("unreachable write attempt state");
    }

    private <T> T lookup(ActionContext context, CommittedResult<T> receipt) {
        return reads.execute(status -> receipt.find(context.actionKey()));
    }

    private void pause() {
        int millis = delay.getAsInt();
        if (millis < 25 || millis > 100) throw new IllegalStateException("invalid lock retry delay");
        try {
            sleeper.pause(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WriteUnavailable(interrupted);
        }
    }

    private static boolean retryable(Throwable failure) {
        if (failure instanceof CannotAcquireLockException) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1205
                    || sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    private static boolean commitUncertain(Throwable failure) {
        if (failure instanceof TransactionException || failure instanceof DataAccessResourceFailureException) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) return true;
        }
        return false;
    }
}
