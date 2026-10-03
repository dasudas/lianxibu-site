package com.lianxibu.site.service.identity;

import com.lianxibu.site.domain.identity.SessionSecrets;
import com.lianxibu.site.repository.identity.BrowserSessionStore;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.LoginCommand;
import com.lianxibu.site.service.identity.IdentityService.Refusal;
import com.lianxibu.site.service.identity.IdentityService.Rejected;
import com.lianxibu.site.service.identity.IdentityService.SessionOutcome;
import com.lianxibu.site.service.identity.IdentityService.SessionState;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.annotation.Transactional;

/** Browser-only identity use cases. Composition and trusted role/password providers follow in B02/B03. */
public class BrowserSessionService {
    private static final Duration ANONYMOUS_TTL = Duration.ofMinutes(15);
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final Duration LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_CHALLENGES = 10_000;
    private final BrowserSessionStore store;
    private final PasswordVerifier passwords;
    private final RolePolicy roles;
    private final Set<String> allowedOrigins;
    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, FailureWindow> failures = new ConcurrentHashMap<>();

    public interface PasswordVerifier {
        boolean matches(String password, String argon2Hash);
        String dummyHash();
    }

    /** Must be backed by trusted server configuration; unknown identities are denied. */
    public interface RolePolicy { String roleFor(String userId); }

    private record Challenge(byte[] csrfHash, Instant expiresAt) { }
    private record FailureWindow(int attempts, Instant startedAt) { }
    private record Authenticated(Actor actor, String csrf) { }

    public BrowserSessionService(BrowserSessionStore store, PasswordVerifier passwords, RolePolicy roles,
                                 Set<String> allowedOrigins, Clock clock, SecureRandom random) {
        if (store == null || passwords == null || roles == null || allowedOrigins == null
                || allowedOrigins.isEmpty() || clock == null || random == null) {
            throw new IllegalArgumentException("browser identity dependencies required");
        }
        this.store = store;
        this.passwords = passwords;
        this.roles = roles;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.clock = clock;
        this.random = random;
    }

    public SessionOutcome session(String sessionCookie, String anonymousCookie) {
        Authenticated authenticated = resolve(sessionCookie);
        if (authenticated != null) {
            return new SessionOutcome(new SessionState(true, authenticated.actor.userId(),
                    authenticated.actor.role(), authenticated.csrf), null, null, false, true);
        }
        Challenge existing = challenge(anonymousCookie);
        if (existing != null) {
            return new SessionOutcome(new SessionState(false, null, null,
                    SessionSecrets.embeddedCsrf(anonymousCookie)), null, null,
                    sessionCookie != null, false);
        }
        return anonymous(sessionCookie != null);
    }

    @Transactional
    public SessionOutcome login(LoginCommand command, String sessionCookie, String anonymousCookie,
                                String csrfToken, String origin) {
        checkOrigin(origin);
        if (command == null || command.username() == null || command.password() == null) {
            throw reject(Refusal.UNAUTHENTICATED);
        }
        String key = command.username().trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty() || key.length() > 64) throw reject(Refusal.UNAUTHENTICATED);
        limit(key);
        Authenticated previous = resolve(sessionCookie);
        if (previous != null) {
            requireCsrf(sessionCookie, csrfToken);
        } else {
            consumeChallenge(anonymousCookie, csrfToken);
        }
        BrowserSessionStore.User user = store.findUser(command.username());
        String hash = user != null && user.passwordHash() != null
                && user.passwordHash().startsWith("$argon2id$") ? user.passwordHash() : passwords.dummyHash();
        boolean matched = passwords.matches(command.password(), hash);
        if (user == null || user.passwordHash() == null || !user.passwordHash().startsWith("$argon2id$")
                || !matched) {
            throw reject(Refusal.UNAUTHENTICATED);
        }
        String role = roles.roleFor(user.id());
        if (role == null || !Set.of("owner", "user").contains(role)) throw reject(Refusal.FORBIDDEN);
        if (previous != null && !store.revokeSession(SessionSecrets.digest(sessionCookie), clock.instant())) {
            throw reject(Refusal.UNAUTHENTICATED);
        }
        SessionSecrets.Pair issued = SessionSecrets.create(random);
        store.createSession(UUID.randomUUID().toString(), user.id(), SessionSecrets.digest(issued.cookie()),
                SessionSecrets.digest(issued.csrf()), clock.instant().plus(SESSION_TTL));
        failures.remove(key);
        return new SessionOutcome(new SessionState(true, user.id(), role, issued.csrf()), issued.cookie(),
                null, false, true);
    }

    public Actor authenticate(String sessionCookie) {
        Authenticated value = resolve(sessionCookie);
        if (value == null) throw reject(Refusal.UNAUTHENTICATED);
        return value.actor;
    }

    /** Common server-side write guard for later business services. */
    public Actor authorizeWrite(String sessionCookie, String csrfToken, String origin) {
        checkOrigin(origin);
        Actor actor = authenticate(sessionCookie);
        requireCsrf(sessionCookie, csrfToken);
        return actor;
    }

    @Transactional
    public SessionOutcome logout(Actor actor, String sessionCookie, String csrfToken, String origin) {
        checkOrigin(origin);
        Authenticated actual = resolve(sessionCookie);
        if (actual == null || !actual.actor.equals(actor)) throw reject(Refusal.UNAUTHENTICATED);
        requireCsrf(sessionCookie, csrfToken);
        if (!store.revokeSession(SessionSecrets.digest(sessionCookie), clock.instant())) {
            throw reject(Refusal.UNAUTHENTICATED);
        }
        return anonymous(true);
    }

    private SessionOutcome anonymous(boolean clearSession) {
        if (challenges.size() >= MAX_CHALLENGES) {
            Instant now = clock.instant();
            challenges.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
            if (challenges.size() >= MAX_CHALLENGES) throw reject(Refusal.RATE_LIMITED);
        }
        SessionSecrets.Pair pair = SessionSecrets.create(random);
        challenges.put(hashKey(pair.cookie()), new Challenge(SessionSecrets.digest(pair.csrf()),
                clock.instant().plus(ANONYMOUS_TTL)));
        return new SessionOutcome(new SessionState(false, null, null, pair.csrf()), null,
                pair.cookie(), clearSession, false);
    }

    private Challenge challenge(String cookie) {
        if (!SessionSecrets.validCookie(cookie)) return null;
        Challenge value = challenges.get(hashKey(cookie));
        return value != null && value.expiresAt.isAfter(clock.instant()) ? value : null;
    }

    private void consumeChallenge(String cookie, String csrf) {
        Challenge value = challenge(cookie);
        if (value == null || !SessionSecrets.sameDigest(value.csrfHash, csrf)
                || !challenges.remove(hashKey(cookie), value)) throw reject(Refusal.FORBIDDEN);
    }

    private Authenticated resolve(String cookie) {
        if (!SessionSecrets.validCookie(cookie)) return null;
        BrowserSessionStore.StoredSession value = store.findSession(SessionSecrets.digest(cookie));
        String csrf = SessionSecrets.embeddedCsrf(cookie);
        if (value == null || value.revokedAt() != null || !value.expiresAt().isAfter(clock.instant())
                || !SessionSecrets.sameDigest(value.csrfHash(), csrf)) return null;
        String role = roles.roleFor(value.userId());
        if (role == null || !Set.of("owner", "user").contains(role)) return null;
        return new Authenticated(new Actor(value.userId(), role), csrf);
    }

    private void requireCsrf(String cookie, String csrf) {
        if (csrf == null || !csrf.equals(SessionSecrets.embeddedCsrf(cookie)) || resolve(cookie) == null) {
            throw reject(Refusal.FORBIDDEN);
        }
    }

    private void checkOrigin(String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) throw reject(Refusal.FORBIDDEN);
    }

    private synchronized void limit(String key) {
        Instant now = clock.instant();
        FailureWindow value = failures.get(key);
        if (value != null && !now.isBefore(value.startedAt.plus(LIMIT_WINDOW))) value = null;
        if (value == null && failures.size() >= MAX_CHALLENGES) {
            failures.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt.plus(LIMIT_WINDOW)));
            if (failures.size() >= MAX_CHALLENGES) throw new Rejected(Refusal.RATE_LIMITED, "900");
        }
        if (value != null && value.attempts >= 5) throw new Rejected(Refusal.RATE_LIMITED, "900");
        failures.put(key, value == null ? new FailureWindow(1, now)
                : new FailureWindow(value.attempts + 1, value.startedAt));
    }

    private static String hashKey(String cookie) {
        return java.util.HexFormat.of().formatHex(SessionSecrets.digest(cookie));
    }

    private static Rejected reject(Refusal refusal) { return new Rejected(refusal, null); }
}
