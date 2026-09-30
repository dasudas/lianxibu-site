package com.lianxibu.site.service.settings;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.settings.JdbcSettingsReads;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Compatibility snapshot that exposes missing mappings instead of guessing old list syntax. */
public final class SettingsReadService {
    public record Snapshot(long revision, Boolean aiPaused, String legacyWatchTermsRaw,
                           String legacyBlockedTermsRaw, String defaultPackageId,
                           Set<String> missingFields) {
        @Override public String toString() {
            return "SettingsSnapshot[revision=" + revision + ",missingFields=" + missingFields + "]";
        }
    }
    private final JdbcSettingsReads store;
    private final TransactionTemplate reads;

    public SettingsReadService(JdbcSettingsReads store, PlatformTransactionManager tx) {
        if (store == null || tx == null) throw new IllegalArgumentException("settings read dependencies required");
        this.store = store;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Snapshot current() {
        return reads.execute(status -> {
            var setting = store.setting("preferences");
            if (setting == null) {
                return new Snapshot(0, null, null, null, null,
                        Set.of("preferences", "ai_paused", "follow_keywords", "blocked_keywords",
                                "default_package_id", "operational_limits"));
            }
            JsonNode value = CanonicalJson.parse(setting.valueJson());
            if (!value.isObject()) throw new IllegalStateException("invalid preferences shape");
            Set<String> missing = new LinkedHashSet<>();
            Boolean paused = flag(value.get("pause_new_jobs"));
            if (paused == null) missing.add("ai_paused");
            String watch = string(value.get("watch_terms_raw"));
            if (watch == null) missing.add("follow_keywords");
            else missing.add("follow_keywords_unmapped");
            String blocked = string(value.get("blocked_terms_raw"));
            if (blocked == null) missing.add("blocked_keywords");
            else missing.add("blocked_keywords_unmapped");
            String defaultId = string(value.get("default_package_id"));
            if (defaultId == null) missing.add("default_package_id");
            if (value.get("operational_limits") == null) missing.add("operational_limits");
            return new Snapshot(setting.revision(), paused, watch, blocked, defaultId,
                    Set.copyOf(missing));
        });
    }

    /** An explicit package is checked on its own; an invalid default never replaces it. */
    public Optional<String> resolvePublishedPackage(String explicitPackageId) {
        if (explicitPackageId != null) id(explicitPackageId);
        return reads.execute(status -> {
            String selected = explicitPackageId == null ? current().defaultPackageId() : explicitPackageId;
            return selected != null && store.publishedPackage(selected)
                    ? Optional.of(selected) : Optional.empty();
        });
    }

    private static Boolean flag(JsonNode value) {
        if (value == null) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber() && (value.asInt() == 0 || value.asInt() == 1)) {
            return value.asInt() == 1;
        }
        return null;
    }
    private static String string(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText() : null;
    }
    private static void id(String value) {
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("invalid package id");
        }
    }
}
