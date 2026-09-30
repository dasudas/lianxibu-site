package com.lianxibu.site.service.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.settings.SettingsUpdateService.Current;
import com.lianxibu.site.service.settings.SettingsUpdateService.Store;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SettingsUpdateServiceTest {
    private static final Actor OWNER = new Actor("owner", "owner");
    private Fixture store;
    private SettingsUpdateService service;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:settings-update-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        store = new Fixture();
        service = new SettingsUpdateService(store, new SharedWriteExecutor(
                new JdbcSharedWriteEntry(source), new DataSourceTransactionManager(source)));
    }

    @Test void changesOnlyFutureInputAndPauseOnlyNewCalls() {
        var before = service.freezeForNewInput();
        assertEquals(List.of("old"), before.followKeywords());
        var updated = service.write(OWNER, new SettingsService.PreferencesWrite(1,
                List.of("new"), List.of("mute"), true, "published", List.of()),
                "write", "csrf", "origin");
        assertEquals(2, updated.revision());
        assertEquals(List.of("old"), before.followKeywords());
        assertEquals(List.of("new"), service.freezeForNewInput().followKeywords());
        assertEquals(List.of("mute"), service.freezeForNewInput().blockedKeywords());
        assertFalse(service.newAttemptAllowed());
        assertEquals("running", store.activeCall);
        assertEquals(1, store.pauseSignals);
        service.write(OWNER, new SettingsService.PreferencesWrite(2,
                List.of("new"), List.of("mute"), false, "published", List.of()),
                "resume", "csrf", "origin");
        assertTrue(service.newAttemptAllowed());
        assertEquals(2, store.pauseSignals);
    }

    @Test void semanticNoopKeepsRevisionAndReceiptAcrossLaterChange() {
        var same = new SettingsService.PreferencesWrite(1, List.of("old"), List.of(),
                false, null, List.of());
        assertEquals(1, service.write(OWNER, same, "noop", "csrf", "origin").revision());
        assertEquals(0, store.updates);
        assertEquals(0, store.pauseSignals);
        service.write(OWNER, new SettingsService.PreferencesWrite(1, List.of("next"),
                List.of(), false, null, List.of()), "change", "csrf", "origin");
        assertEquals(1, service.write(OWNER, same, "noop", "csrf", "origin").revision());
        assertThrows(IllegalStateException.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("forged"), List.of(),
                        false, null, List.of()), "noop", "csrf", "origin"));
    }

    @Test void defaultMustBePublishedAndLegacyNeedsExplicitMapping() {
        assertThrows(SettingsUpdateService.Rejected.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("old"), List.of(),
                        false, "draft", List.of()), "draft", "csrf", "origin"));
        assertThrows(SettingsUpdateService.Rejected.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(2, List.of("new"), List.of(),
                        false, null, List.of()), "stale", "csrf", "origin"));
        store.current = new Current(1, null, null, false, null, List.of(), false);
        assertThrows(SettingsUpdateService.Rejected.class, () -> service.get(OWNER));
        service.write(OWNER, new SettingsService.PreferencesWrite(1, List.of("explicit"),
                List.of("excluded"), false, null, List.of()), "map", "csrf", "origin");
        assertEquals(List.of("explicit"), service.get(OWNER).followKeywords());
        assertEquals(List.of("excluded"), service.get(OWNER).blockedKeywords());
    }

    @Test void ownerRoleAndDuplicateKeywordsAreEnforced() {
        assertThrows(RuntimeException.class, () -> service.get(new Actor("user", "user")));
        assertThrows(IllegalArgumentException.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("x", "x"), List.of(),
                        false, null, List.of()), "bad", "csrf", "origin"));
    }

    @Test void operationalChangeNeedsReasonAndHasOneAuditedReceipt() {
        var rules = OperationalFixtures.complete();
        var noReason = new SettingsService.PreferencesWrite(1, List.of("old"), List.of(), false, null, rules);
        assertThrows(IllegalArgumentException.class, () -> service.write(OWNER, noReason, "ops", "csrf", "origin"));
        assertEquals(0, store.updates); assertEquals(0, store.opsAudits);
        var valid = new SettingsService.PreferencesWrite(1, List.of("old"), List.of(), false, null, rules, "Fictional config change");
        assertEquals(2, service.write(OWNER, valid, "ops", "csrf", "origin").revision());
        assertEquals(1, store.opsAudits); assertEquals("Fictional config change", store.opsReason);
        assertEquals(2, service.write(OWNER, valid, "ops", "csrf", "origin").revision());
        assertEquals(1, store.opsAudits);
        assertThrows(IllegalStateException.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("old"), List.of(), false, null, rules, "Different reason"),
                "ops", "csrf", "origin"));
        var changed = new java.util.ArrayList<>(rules);
        changed.set(3, OperationalRulePolicyTest.with(rules.get(3), OperationalRulePolicyTest.changed(rules.get(3), "protect_free_percent", "4")));
        assertThrows(IllegalStateException.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("old"), List.of(), false, null, changed, "Fictional config change"),
                "ops", "csrf", "origin"));
        assertThrows(SettingsUpdateService.Rejected.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(1, List.of("old"), List.of(), false, null, changed, "Fictional config change"),
                "stale-ops", "csrf", "origin"));
        assertEquals(1, store.opsAudits);
    }
    @Test void unchangedLegacyOperationalValuesDoNotBlockOrdinaryPreferences() {
        var old = new SettingsService.OperationalRule("fixture-legacy", "2", "5", "1", null, false);
        store.current = new Current(1, List.of("old"), List.of(), false, null, List.of(old), true);
        service.write(OWNER, new SettingsService.PreferencesWrite(1, List.of("new"), List.of(), false, null,
                List.of(old)), "ordinary", "csrf", "origin");
        assertEquals(0, store.opsAudits); assertFalse(OperationalRulePolicy.ready(old));
        assertThrows(IllegalArgumentException.class, () -> service.write(OWNER,
                new SettingsService.PreferencesWrite(2, List.of("new"), List.of(), false, null,
                        List.of(new SettingsService.OperationalRule("OPS-04", "15", "10", "15", null, false)), "Changed"),
                "incomplete", "csrf", "origin"));
        assertEquals(0, store.opsAudits);
    }

    private static final class Fixture implements Store {
        Current current = new Current(1, List.of("old"), List.of(), false, null, List.of(), true);
        final Map<String, SettingsService.Preferences> receipts = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        String activeCall = "running";
        int pauseSignals;
        int updates;
        int opsAudits;
        String opsReason;
        @Override public void recordOperationalChange(Current previous, SettingsService.PreferencesWrite command,
                                                      String actionKey, String actorId) {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            opsAudits++; opsReason = command.operationalChangeReason();
        }
        @Override public SettingsService.Preferences committed(String key, String hash) {
            if (hashes.containsKey(key) && !hashes.get(key).equals(hash))
                throw new IllegalStateException("action conflict");
            return receipts.get(key);
        }
        @Override public Current current() { return current; }
        @Override public void update(Current previous, SettingsService.PreferencesWrite command,
                                     String hash, String key, String actor) {
            assertEquals(previous.revision(), current.revision());
            current = new Current(previous.revision() + 1, List.copyOf(command.followKeywords()),
                    List.copyOf(command.blockedKeywords()), command.aiPaused(),
                    command.defaultPackageId(), List.copyOf(command.operationalLimits()), true);
            updates++;
            receipt(key, hash);
        }
        @Override public void recordNoop(Current previous, String hash, String key, String actor) {
            receipt(key, hash);
        }
        private void receipt(String key, String hash) {
            receipts.put(key, new SettingsService.Preferences(current.revision(),
                    current.followKeywords(), current.blockedKeywords(), current.aiPaused(),
                    current.defaultPackageId(), current.operationalLimits()));
            hashes.put(key, hash);
        }
        @Override public boolean publishedPackage(String packageId) {
            return "published".equals(packageId);
        }
        @Override public void recordPauseIntent(boolean paused) { pauseSignals++; }
    }
}
