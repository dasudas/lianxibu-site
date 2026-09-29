package com.lianxibu.site.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianxibu.site.repository.identity.JdbcPasswordAdminStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PasswordAdministrationTest {
    private static final String OLD_PASSWORD = "fictional-old-password";
    private static final String NEW_PASSWORD = "fictional-new-password";

    @Test
    void hostInitializationAndResetUseArgon2AndRevokeSessions() {
        Fixture fixture = new Fixture();
        fixture.admin.initialize("fixture-owner-id", "fixture-owner", OLD_PASSWORD);
        String original = fixture.hash();
        assertTrue(original.startsWith("$argon2id$"));
        assertTrue(fixture.passwords.matches(OLD_PASSWORD, original));
        assertFalse(fixture.passwords.matches(NEW_PASSWORD, original));
        fixture.jdbc.update("INSERT INTO user_sessions (id, user_id, revoked_at) VALUES (?, ?, NULL)",
                "fixture-session", "fixture-owner-id");
        fixture.admin.reset("fixture-owner", NEW_PASSWORD);
        String updated = fixture.hash();
        assertNotEquals(original, updated);
        assertTrue(fixture.passwords.matches(NEW_PASSWORD, updated));
        assertFalse(fixture.passwords.matches(OLD_PASSWORD, updated));
        assertEquals(2L, fixture.jdbc.queryForObject("SELECT revision FROM users WHERE id = ?",
                Long.class, "fixture-owner-id"));
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions "
                + "WHERE user_id = ? AND revoked_at IS NOT NULL", Long.class, "fixture-owner-id"));
    }

    @Test
    void failedResetRollsBackHashAndDoesNotCreateUnknownAccount() {
        Fixture fixture = new Fixture();
        fixture.admin.initialize("fixture-owner-id", "fixture-owner", OLD_PASSWORD);
        String original = fixture.hash();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.admin.reset("fixture-missing", NEW_PASSWORD));
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        fixture.jdbc.execute("DROP TABLE user_sessions");
        assertThrows(RuntimeException.class, () -> fixture.admin.reset("fixture-owner", NEW_PASSWORD));
        assertEquals(original, fixture.hash());
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT revision FROM users", Long.class));
    }

    @Test
    void rejectsShortSecretLegacyHashesAndNonHostCommandForms() {
        Argon2Passwords passwords = new Argon2Passwords();
        assertThrows(IllegalArgumentException.class, () -> passwords.encode("short"));
        assertFalse(passwords.matches(OLD_PASSWORD, "legacy-hash"));
        assertTrue(passwords.dummyHash().startsWith("$argon2id$"));
        assertEquals("init", PasswordAdminCommand.parse(new String[] {"init", "fixture-id", "fixture"}).mode());
        assertEquals("reset", PasswordAdminCommand.parse(new String[] {"reset", "fixture"}).mode());
        assertThrows(IllegalArgumentException.class,
                () -> PasswordAdminCommand.parse(new String[] {"register", "fixture"}));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordAdminCommand.parse(new String[] {"reset", "fixture", "secret-in-args"}));
    }

    private static final class Fixture {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:b02-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        final Argon2Passwords passwords = new Argon2Passwords();
        final PasswordAdministration admin;

        Fixture() {
            jdbc.execute("CREATE TABLE users (id VARCHAR(64) PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL, "
                    + "password_hash VARCHAR(255), revision BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL, "
                    + "updated_at TIMESTAMP(6) NOT NULL)");
            jdbc.execute("CREATE TABLE user_sessions (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, "
                    + "revoked_at TIMESTAMP(6))");
            admin = new PasswordAdministration(new JdbcPasswordAdminStore(dataSource,
                    Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC)), passwords);
        }

        String hash() {
            return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class,
                    "fixture-owner-id");
        }
    }
}
