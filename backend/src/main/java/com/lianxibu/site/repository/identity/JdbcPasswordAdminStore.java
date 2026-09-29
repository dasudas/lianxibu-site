package com.lianxibu.site.repository.identity;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/** Each host command commits password change and session revocation atomically. */
public final class JdbcPasswordAdminStore implements PasswordAdminStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcPasswordAdminStore(DataSource dataSource, Clock clock) {
        if (dataSource == null || clock == null) throw new IllegalArgumentException("admin store dependencies required");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = clock;
    }

    @Override
    public void initialize(String userId, String username, String encodedHash) {
        transactions.executeWithoutResult(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO users (id, username, password_hash, revision, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 1, ?, ?)", userId, username, encodedHash, now, now);
        });
    }

    @Override
    public void reset(String username, String encodedHash) {
        transactions.executeWithoutResult(status -> {
            String userId = jdbc.query("SELECT id FROM users WHERE username = ? FOR UPDATE", result ->
                    result.next() ? result.getString(1) : null, username);
            if (userId == null) throw new IllegalArgumentException("account not found");
            Timestamp now = Timestamp.from(clock.instant());
            int changed = jdbc.update("UPDATE users SET password_hash = ?, revision = revision + 1, "
                    + "updated_at = ? WHERE id = ?", encodedHash, now, userId);
            if (changed != 1) throw new IllegalStateException("account changed during reset");
            jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                    now, userId);
        });
    }
}
