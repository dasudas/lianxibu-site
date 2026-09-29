package com.lianxibu.site.service.identity;

import java.time.Instant;
import java.util.List;

/** Business port for the six identity HTTP operations. Implementations own persistence and secret handling. */
public interface IdentityService {
    enum Refusal { UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, REVISION_CONFLICT,
        IDENTITY_CONFLICT, RATE_LIMITED, TEMPORARILY_UNAVAILABLE }

    /** A stable business refusal; the HTTP layer supplies public error text and status. */
    final class Rejected extends RuntimeException {
        private final Refusal refusal;
        private final String retryAfter;
        public Rejected(Refusal refusal, String retryAfter) {
            super(refusal.name());
            this.refusal = refusal;
            this.retryAfter = retryAfter;
        }
        public Refusal refusal() { return refusal; }
        public String retryAfter() { return retryAfter; }
    }

    record Actor(String userId, String role) { }
    record SessionState(boolean authenticated, String userId, String role, String csrfToken) { }
    record SessionOutcome(SessionState session, String sessionToken, String anonymousToken,
                          boolean clearSession, boolean clearAnonymous) { }
    record LoginCommand(String username, String password) { }
    record NodeCreateCommand(String name, List<String> sourceIds, List<String> permissions) { }
    record NodeActionCommand(long expectedRevision, String action, String reason,
                             List<String> sourceIds, List<String> permissions) { }
    record CollectorNode(String id, String name, List<String> sourceIds, List<String> permissions,
                         long revision, boolean revoked, Instant lastHeartbeatAt) { }
    record CollectorNodePage(List<CollectorNode> items, long page, int pageSize, long total) { }
    /** A token is present only for create/rotate, and must never enter a list response or log. */
    record NodeCredential(CollectorNode node, String token) { }
    record NodeActionOutcome(CollectorNode node, String token) { }

    SessionOutcome session(String sessionCookie, String anonymousCookie);
    SessionOutcome login(LoginCommand command, String anonymousCookie, String csrfToken, String origin);
    Actor authenticate(String sessionCookie);
    SessionOutcome logout(Actor actor, String sessionCookie, String csrfToken, String origin);
    CollectorNodePage listNodes(Actor actor, long page, int pageSize);
    NodeCredential createNode(Actor actor, NodeCreateCommand command, String idempotencyKey,
                              String csrfToken, String origin);
    NodeActionOutcome actOnNode(Actor actor, String nodeId, NodeActionCommand command,
                                String idempotencyKey, String csrfToken, String origin);
}
