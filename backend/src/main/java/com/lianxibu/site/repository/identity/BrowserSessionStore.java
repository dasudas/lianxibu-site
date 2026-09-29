package com.lianxibu.site.repository.identity;

import java.time.Instant;

/** Session persistence boundary. Only SHA-256 digests of browser secrets enter the database. */
public interface BrowserSessionStore {
    record User(String id, String passwordHash) { }
    record StoredSession(String userId, byte[] csrfHash, Instant expiresAt, Instant revokedAt) { }

    User findUser(String username);
    StoredSession findSession(byte[] tokenHash);
    void createSession(String id, String userId, byte[] tokenHash, byte[] csrfHash, Instant expiresAt);
    boolean revokeSession(byte[] tokenHash, Instant revokedAt);
}
