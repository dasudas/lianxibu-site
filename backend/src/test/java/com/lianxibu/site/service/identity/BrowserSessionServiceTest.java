package com.lianxibu.site.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.domain.identity.SessionSecrets;
import com.lianxibu.site.repository.identity.BrowserSessionStore;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.LoginCommand;
import com.lianxibu.site.service.identity.IdentityService.Refusal;
import com.lianxibu.site.service.identity.IdentityService.Rejected;
import com.lianxibu.site.service.identity.IdentityService.SessionOutcome;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrowserSessionServiceTest {
    private static final String ORIGIN = "https://site.example.invalid";
    private static final LoginCommand OWNER = new LoginCommand("fixture-owner", "correct");
    private static final String HASH = "$argon2id$fictional-hash";

    @Test
    void anonymousChallengeLoginSessionAndLogoutRotateAndRevoke() {
        Fixture fixture = new Fixture();
        SessionOutcome anon = fixture.service.session(null, null);
        assertFalse(anon.session().authenticated());
        assertNotNull(anon.anonymousToken());
        SessionOutcome login = fixture.login(OWNER, anon);
        assertTrue(login.session().authenticated());
        assertEquals("owner", login.session().role());
        assertNotEquals(anon.anonymousToken(), login.sessionToken());
        assertTrue(login.clearAnonymous());
        assertEquals(new Actor("fixture-owner-id", "owner"), fixture.service.authenticate(login.sessionToken()));
        assertEquals(login.session().csrfToken(), fixture.service.session(login.sessionToken(), null)
                .session().csrfToken());
        SessionOutcome logout = fixture.service.logout(new Actor("fixture-owner-id", "owner"),
                login.sessionToken(), login.session().csrfToken(), ORIGIN);
        assertFalse(logout.session().authenticated());
        assertTrue(logout.clearSession());
        assertNotNull(logout.anonymousToken());
        rejected(Refusal.UNAUTHENTICATED, () -> fixture.service.authenticate(login.sessionToken()));
        rejected(Refusal.FORBIDDEN, () -> fixture.login(OWNER, anon));
    }

    @Test
    void anotherLoginRevokesPreviousCookieBeforeIssuingNewOne() {
        Fixture fixture = new Fixture();
        SessionOutcome first = fixture.login(OWNER, fixture.service.session(null, null));
        SessionOutcome second = fixture.service.login(OWNER, first.sessionToken(), null,
                first.session().csrfToken(), ORIGIN);
        assertNotEquals(first.sessionToken(), second.sessionToken());
        rejected(Refusal.UNAUTHENTICATED, () -> fixture.service.authenticate(first.sessionToken()));
        assertEquals(new Actor("fixture-owner-id", "owner"), fixture.service.authenticate(second.sessionToken()));
    }

    @Test
    void wrongAndUnknownAccountSharePublicRefusalAndVerifierPath() {
        Fixture fixture = new Fixture();
        SessionOutcome knownChallenge = fixture.service.session(null, null);
        rejected(Refusal.UNAUTHENTICATED,
                () -> fixture.login(new LoginCommand("fixture-owner", "wrong"), knownChallenge));
        int afterKnown = fixture.verifier.calls;
        SessionOutcome secondChallenge = fixture.service.session(null, null);
        rejected(Refusal.UNAUTHENTICATED,
                () -> fixture.login(new LoginCommand("fixture-missing", "wrong"), secondChallenge));
        assertEquals(afterKnown + 1, fixture.verifier.calls);
        assertEquals(HASH, fixture.verifier.lastHash);
        fixture.store.users.put("fixture-legacy", new BrowserSessionStore.User("legacy-id", "legacy-hash"));
        SessionOutcome thirdChallenge = fixture.service.session(null, null);
        rejected(Refusal.UNAUTHENTICATED,
                () -> fixture.login(new LoginCommand("fixture-legacy", "correct"), thirdChallenge));
    }

    @Test
    void csrfOriginRoleAndNodeTokenCannotBypassBrowserSession() {
        Fixture fixture = new Fixture();
        SessionOutcome anon = fixture.service.session(null, null);
        rejected(Refusal.FORBIDDEN, () -> fixture.service.login(OWNER, null, anon.anonymousToken(),
                "wrong-csrf", ORIGIN));
        rejected(Refusal.FORBIDDEN, () -> fixture.service.login(OWNER, null, anon.anonymousToken(),
                anon.session().csrfToken(), "https://elsewhere.example.invalid"));
        rejected(Refusal.UNAUTHENTICATED, () -> fixture.service.authenticate("fixture-node-bearer"));
        fixture.store.users.put("fixture-user", new BrowserSessionStore.User("fixture-user-id", HASH));
        SessionOutcome user = fixture.login(new LoginCommand("fixture-user", "correct"), anon);
        assertEquals("user", fixture.service.authenticate(user.sessionToken()).role());
        assertEquals(new Actor("fixture-user-id", "user"), fixture.service.authorizeWrite(
                user.sessionToken(), user.session().csrfToken(), ORIGIN));
        rejected(Refusal.FORBIDDEN, () -> fixture.service.authorizeWrite(
                user.sessionToken(), "wrong-csrf", ORIGIN));
        rejected(Refusal.FORBIDDEN, () -> fixture.service.authorizeWrite(
                user.sessionToken(), user.session().csrfToken(), "https://elsewhere.example.invalid"));
        rejected(Refusal.UNAUTHENTICATED, () -> fixture.service.logout(new Actor("fixture-user-id", "owner"),
                user.sessionToken(), user.session().csrfToken(), ORIGIN));
        rejected(Refusal.FORBIDDEN, () -> fixture.service.logout(new Actor("fixture-user-id", "user"),
                user.sessionToken(), "wrong-csrf", ORIGIN));
        assertEquals("user", fixture.service.authenticate(user.sessionToken()).role());
    }

    @Test
    void fiveFailuresThrottleWithoutPersistingRawBrowserSecrets() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 5; i++) {
            SessionOutcome anon = fixture.service.session(null, null);
            rejected(Refusal.UNAUTHENTICATED,
                    () -> fixture.login(new LoginCommand("fixture-owner", "wrong"), anon));
        }
        SessionOutcome anon = fixture.service.session(null, null);
        rejected(Refusal.RATE_LIMITED, () -> fixture.login(OWNER, anon));
        assertTrue(fixture.store.sessions.isEmpty());
        Fixture fresh = new Fixture();
        SessionOutcome issued = fresh.login(OWNER, fresh.service.session(null, null));
        assertFalse(fresh.store.sessions.containsKey(issued.sessionToken()));
        assertTrue(fresh.store.sessions.containsKey(HexFormat.of().formatHex(
                SessionSecrets.digest(issued.sessionToken()))));
    }

    private static void rejected(Refusal expected, Runnable action) {
        assertEquals(expected, assertThrows(Rejected.class, action::run).refusal());
    }

    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final FakeVerifier verifier = new FakeVerifier();
        final BrowserSessionService service = new BrowserSessionService(store, verifier,
                id -> id.equals("fixture-owner-id") ? "owner" : id.equals("fixture-user-id") ? "user" : null,
                Set.of(ORIGIN), Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC),
                new SecureRandom());

        Fixture() {
            store.users.put("fixture-owner", new BrowserSessionStore.User("fixture-owner-id", HASH));
        }

        SessionOutcome login(LoginCommand command, SessionOutcome anonymous) {
            return service.login(command, null, anonymous.anonymousToken(), anonymous.session().csrfToken(), ORIGIN);
        }
    }

    private static final class FakeVerifier implements BrowserSessionService.PasswordVerifier {
        int calls;
        String lastHash;
        @Override public boolean matches(String password, String hash) {
            calls++;
            lastHash = hash;
            return "correct".equals(password) && HASH.equals(hash);
        }
        @Override public String dummyHash() { return HASH; }
    }

    private static final class MemoryStore implements BrowserSessionStore {
        final Map<String, User> users = new HashMap<>();
        final Map<String, StoredSession> sessions = new HashMap<>();
        @Override public User findUser(String username) { return users.get(username); }
        @Override public StoredSession findSession(byte[] tokenHash) {
            return sessions.get(HexFormat.of().formatHex(tokenHash));
        }
        @Override public void createSession(String id, String userId, byte[] tokenHash,
                                            byte[] csrfHash, Instant expiresAt) {
            sessions.put(HexFormat.of().formatHex(tokenHash),
                    new StoredSession(userId, csrfHash, expiresAt, null));
        }
        @Override public boolean revokeSession(byte[] tokenHash, Instant revokedAt) {
            String key = HexFormat.of().formatHex(tokenHash);
            StoredSession value = sessions.get(key);
            if (value == null || value.revokedAt() != null) return false;
            sessions.put(key, new StoredSession(value.userId(), value.csrfHash(), value.expiresAt(), revokedAt));
            return true;
        }
    }
}
