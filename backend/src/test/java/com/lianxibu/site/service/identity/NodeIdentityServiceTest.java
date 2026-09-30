package com.lianxibu.site.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.domain.identity.NodeTokens;
import com.lianxibu.site.repository.identity.JdbcNodeStore;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.identity.IdentityService.NodeActionCommand;
import com.lianxibu.site.service.identity.IdentityService.NodeCreateCommand;
import com.lianxibu.site.service.identity.IdentityService.Refusal;
import com.lianxibu.site.service.identity.IdentityService.Rejected;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NodeIdentityServiceTest {
    private static final Actor OWNER = new Actor("fixture-owner", "owner");
    private static final String CSRF = "fixture-csrf";
    private static final String ORIGIN = "https://site.example.invalid";

    @Test
    void createReplayRotateScopeChangeAndRevokeUseCurrentAuthorization() {
        Fixture fixture = new Fixture();
        var service = fixture.service;
        var command = new NodeCreateCommand("Fixture node", List.of("fixture-source"),
                List.of("config:read", "run:read"));
        var created = service.createNode(OWNER, command, "fixture-create-key", CSRF, ORIGIN);
        assertEquals(1, created.node().revision());
        assertEquals(created, service.createNode(OWNER, command, "fixture-create-key", CSRF, ORIGIN));
        assertEquals(1L, fixture.count("collector_nodes"));
        assertEquals(1L, fixture.count("request_receipts"));
        assertFalse(fixture.jdbc.queryForObject("SELECT response_json FROM request_receipts", String.class)
                .contains(created.token()));
        byte[] stableSecret = new byte[32];
        java.util.Arrays.fill(stableSecret, (byte) 7);
        var afterExpiry = new NodeIdentityService(new JdbcNodeStore(fixture.source),
                new NodeTokens(stableSecret),
                Clock.fixed(Instant.parse("2026-10-31T00:00:01Z"), ZoneOffset.UTC));
        Rejected expired = assertThrows(Rejected.class,
                () -> afterExpiry.createNode(OWNER, command, "fixture-create-key", CSRF, ORIGIN));
        assertEquals("idempotency_result_expired", expired.code());
        assertEquals(created.node().id(), expired.resultResourceRef());
        assertTrue(expired.operationRef() != null && !expired.operationRef().isBlank());
        rejected(Refusal.IDENTITY_CONFLICT, () -> service.createNode(OWNER,
                new NodeCreateCommand("Changed", command.sourceIds(), command.permissions()),
                "fixture-create-key", CSRF, ORIGIN));

        assertEquals("fixture-source", service.authorizeBearer(created.token(), "run:read", "fixture-source")
                .sourceIds().getFirst());
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(created.token(), "run:claim", null));
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(created.token(), "run:read", "other-source"));
        var changed = service.actOnNode(OWNER, created.node().id(),
                new NodeActionCommand(1, "set_permissions", "fixture update", List.of(),
                        List.of("config:read", "run:claim", "observation:write")),
                "fixture-scope-key", CSRF, ORIGIN);
        assertEquals(2, changed.node().revision());
        assertNull(changed.token());
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(created.token(), "run:read", null));
        service.authorizeBearer(created.token(), "config:read", null);
        service.authorizeBearer(created.token(), "run:claim", null);
        service.authorizeBearer(created.token(), "observation:write", null);

        var rotated = service.actOnNode(OWNER, created.node().id(),
                new NodeActionCommand(2, "rotate_token", "fixture rotate", null, null),
                "fixture-rotate-key", CSRF, ORIGIN);
        assertEquals(3, rotated.node().revision());
        assertNotEquals(created.token(), rotated.token());
        assertEquals(rotated, service.actOnNode(OWNER, created.node().id(),
                new NodeActionCommand(2, "rotate_token", "fixture rotate", null, null),
                "fixture-rotate-key", CSRF, ORIGIN));
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(created.token(), "config:read", null));
        service.authorizeBearer(rotated.token(), "config:read", null);

        var revoked = service.actOnNode(OWNER, created.node().id(),
                new NodeActionCommand(3, "revoke", "fixture revoke", null, null),
                "fixture-revoke-key", CSRF, ORIGIN);
        assertTrue(revoked.node().revoked());
        assertNull(revoked.token());
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(rotated.token(), "config:read", null));
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(rotated.token(), "run:claim", null));
        rejected(Refusal.FORBIDDEN, () -> service.authorizeBearer(rotated.token(), "observation:write", null));
        rejected(Refusal.REVISION_CONFLICT, () -> service.actOnNode(OWNER, created.node().id(),
                new NodeActionCommand(3, "rotate_token", "stale", null, null),
                "fixture-stale-key", CSRF, ORIGIN));
        assertEquals(1, service.listNodes(OWNER, 1, 20).items().size());
    }

    @Test
    void nullPermissionUnknownSourcesWrongOwnerAndFailedReceiptStayClosed() {
        Fixture fixture = new Fixture();
        rejected(Refusal.FORBIDDEN, () -> fixture.service.listNodes(new Actor("fixture-user", "user"), 1, 20));
        rejected(Refusal.NOT_FOUND, () -> fixture.service.createNode(OWNER,
                new NodeCreateCommand("Fixture", List.of("unknown-source"), List.of("config:read")),
                "fixture-missing", CSRF, ORIGIN));
        assertEquals(0L, fixture.count("collector_nodes"));
        fixture.jdbc.update("INSERT INTO collector_nodes (id, name, token_hash, allowed_source_ids, "
                        + "enabled, config_revision, permissions_json) VALUES (?, ?, ?, ?, 1, 1, NULL)",
                "fixture-legacy", "Legacy", NodeTokens.digest("f".repeat(43)), "[]");
        rejected(Refusal.FORBIDDEN, () -> fixture.service.authorizeBearer("f".repeat(43), "config:read", null));

        fixture.jdbc.execute("ALTER TABLE request_receipts ALTER COLUMN response_json VARCHAR(10)");
        assertThrows(RuntimeException.class, () -> fixture.service.createNode(OWNER,
                new NodeCreateCommand("Fixture", List.of(), List.of("config:read")),
                "fixture-failed", CSRF, ORIGIN));
        assertEquals(1L, fixture.count("collector_nodes"));
    }

    private static void rejected(Refusal expected, Runnable work) {
        assertEquals(expected, assertThrows(Rejected.class, work::run).refusal());
    }

    private static final class Fixture {
        final DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:b03-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(source);
        final NodeIdentityService service;

        Fixture() {
            jdbc.execute("CREATE TABLE users (id VARCHAR(64) PRIMARY KEY)");
            jdbc.execute("INSERT INTO users (id) VALUES ('fixture-owner')");
            jdbc.execute("CREATE TABLE sources (id VARCHAR(80) PRIMARY KEY)");
            jdbc.execute("INSERT INTO sources (id) VALUES ('fixture-source')");
            jdbc.execute("CREATE TABLE collector_nodes (id VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                    + "token_hash BINARY(32) NOT NULL UNIQUE, allowed_source_ids VARCHAR(10000) NOT NULL, "
                    + "enabled BOOLEAN NOT NULL, last_seen_at TIMESTAMP(6), config_revision BIGINT NOT NULL, "
                    + "permissions_json VARCHAR(1000))");
            jdbc.execute("CREATE TABLE request_receipts (principal VARCHAR(80) NOT NULL, operation VARCHAR(191) "
                    + "NOT NULL, request_key VARCHAR(128) NOT NULL, request_hash BINARY(32) NOT NULL, "
                    + "status_code INT NOT NULL, response_json VARCHAR(10000), expires_at TIMESTAMP(6), "
                    + "id VARCHAR(64) NOT NULL UNIQUE, receipt_kind VARCHAR(32) NOT NULL, actor_id VARCHAR(64) "
                    + "NOT NULL, scope VARCHAR(128) NOT NULL, key_hash BINARY(32) NOT NULL, "
                    + "original_status VARCHAR(32) NOT NULL, current_status VARCHAR(32) NOT NULL, "
                    + "result_ref_json VARCHAR(1000), created_at TIMESTAMP(6) NOT NULL, revision BIGINT NOT NULL, "
                    + "PRIMARY KEY (principal, operation, request_key), "
                    + "UNIQUE (actor_id, scope, key_hash))");
            byte[] secret = new byte[32];
            java.util.Arrays.fill(secret, (byte) 7);
            service = new NodeIdentityService(new JdbcNodeStore(source), new NodeTokens(secret),
                    Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
        }

        long count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        }
    }
}
