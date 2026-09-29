package com.lianxibu.site.repository.identity;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parameterized JDBC adapter for the verified live users/user_sessions shape. */
public final class JdbcBrowserSessionStore implements BrowserSessionStore {
    private final JdbcTemplate jdbc;

    public JdbcBrowserSessionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public User findUser(String username) {
        return jdbc.query("SELECT id, password_hash FROM users WHERE username = ?", result ->
                result.next() ? new User(result.getString(1), result.getString(2)) : null, username);
    }

    @Override
    public StoredSession findSession(byte[] tokenHash) {
        return jdbc.query("SELECT user_id, csrf_hash, expires_at, revoked_at FROM user_sessions "
                        + "WHERE token_hash = ?", result -> {
            if (!result.next()) return null;
            Timestamp revoked = result.getTimestamp(4);
            return new StoredSession(result.getString(1), result.getBytes(2),
                    result.getTimestamp(3).toInstant(), revoked == null ? null : revoked.toInstant());
        }, tokenHash);
    }

    @Override
    public void createSession(String id, String userId, byte[] tokenHash, byte[] csrfHash, Instant expiresAt) {
        jdbc.update("INSERT INTO user_sessions (id, user_id, token_hash, csrf_hash, expires_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL)", id, userId, tokenHash, csrfHash,
                Timestamp.from(expiresAt));
    }

    @Override
    public boolean revokeSession(byte[] tokenHash, Instant revokedAt) {
        return jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ? "
                        + "AND revoked_at IS NULL", Timestamp.from(revokedAt), tokenHash) == 1;
    }
}
