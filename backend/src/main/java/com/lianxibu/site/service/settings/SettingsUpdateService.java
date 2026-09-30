package com.lianxibu.site.service.settings;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Versioned preferences; frozen generation inputs retain their captured values. */
public final class SettingsUpdateService implements SettingsService {
    public record Current(long revision, List<String> followKeywords,
                          List<String> blockedKeywords, boolean aiPaused,
                          String defaultPackageId, List<OperationalRule> operationalLimits,
                          boolean mappingComplete) { }
    public record Frozen(long settingsRevision, List<String> followKeywords,
                         List<String> blockedKeywords, String defaultPackageId) { }
    public interface Store {
        Preferences committed(String actionKey, String commandHash);
        Current current();
        /** Preserve unmapped legacy raw fields; do not infer delimiters. */
        void update(Current previous, PreferencesWrite command, String commandHash,
                    String actionKey, String actorId);
        void recordNoop(Current previous, String commandHash, String actionKey, String actorId);
        boolean publishedPackage(String packageId);
        /** Durable pause intent in the same transaction; running calls keep their permit. */
        void recordPauseIntent(boolean paused);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }

    private final Store store;
    private final SharedWriteExecutor writes;

    public SettingsUpdateService(Store store, SharedWriteExecutor writes) {
        if (store == null || writes == null) throw new IllegalArgumentException("settings dependencies required");
        this.store = store;
        this.writes = writes;
    }

    @Override public Preferences get(Actor actor) {
        owner(actor);
        return project(ready(store.current()));
    }

    @Override public Preferences write(Actor actor, PreferencesWrite command,
                                       String key, String csrf, String origin) {
        String actorId = owner(actor);
        validate(command);
        if (key == null || key.isBlank() || key.length() > 128
                || csrf == null || csrf.isBlank() || origin == null || origin.isBlank())
            throw new IllegalArgumentException("valid write headers required");
        String hash = commandHash(actorId, command);
        ActionContext action = new ActionContext(key, "preferences", null);
        return writes.execute(action, actionKey -> store.committed(actionKey, hash), ignored -> {
            Current current = store.current();
            if (current == null) throw new Rejected("settings_unavailable");
            if (current.revision() != command.expectedRevision())
                throw new Rejected("revision_conflict");
            if (command.defaultPackageId() != null
                    && !store.publishedPackage(command.defaultPackageId()))
                throw new Rejected("default_package_unavailable");
            if (current.mappingComplete() && same(current, command)) {
                store.recordNoop(current, hash, key, actorId);
                return project(current);
            }
            store.update(current, command, hash, key, actorId);
            if (current.aiPaused() != command.aiPaused()) store.recordPauseIntent(command.aiPaused());
            return new Preferences(current.revision() + 1,
                    List.copyOf(command.followKeywords()), List.copyOf(command.blockedKeywords()),
                    command.aiPaused(), command.defaultPackageId(),
                    List.copyOf(command.operationalLimits()));
        });
    }

    /** Capture by value when a new input is prepared; never dereference it again for that attempt. */
    public Frozen freezeForNewInput() {
        Current current = ready(store.current());
        return new Frozen(current.revision(), List.copyOf(current.followKeywords()),
                List.copyOf(current.blockedKeywords()), current.defaultPackageId());
    }

    /** Called at dispatch of each NEW provider attempt, not by in-flight completion. */
    public boolean newAttemptAllowed() {
        return !ready(store.current()).aiPaused();
    }

    private static Current ready(Current value) {
        if (value == null || !value.mappingComplete() || value.followKeywords() == null
                || value.blockedKeywords() == null || value.operationalLimits() == null)
            throw new Rejected("settings_mapping_required");
        return value;
    }
    private static Preferences project(Current value) {
        return new Preferences(value.revision(), List.copyOf(value.followKeywords()),
                List.copyOf(value.blockedKeywords()), value.aiPaused(),
                value.defaultPackageId(), List.copyOf(value.operationalLimits()));
    }
    private static boolean same(Current current, PreferencesWrite next) {
        return current.followKeywords().equals(next.followKeywords())
                && current.blockedKeywords().equals(next.blockedKeywords())
                && current.aiPaused() == next.aiPaused()
                && java.util.Objects.equals(current.defaultPackageId(), next.defaultPackageId())
                && current.operationalLimits().equals(next.operationalLimits());
    }
    private static void validate(PreferencesWrite value) {
        if (value == null || value.expectedRevision() < 0
                || value.followKeywords() == null || value.blockedKeywords() == null
                || value.operationalLimits() == null || value.followKeywords().size() > 100
                || value.blockedKeywords().size() > 100 || value.operationalLimits().size() > 20)
            throw new IllegalArgumentException("invalid preferences");
        keywords(value.followKeywords());
        keywords(value.blockedKeywords());
        if (value.defaultPackageId() != null && (value.defaultPackageId().isBlank()
                || value.defaultPackageId().length() > 64))
            throw new IllegalArgumentException("invalid default package");
        Set<String> ruleIds = new HashSet<>();
        for (OperationalRule rule : value.operationalLimits()) {
            if (rule == null || rule.ruleId() == null || rule.ruleId().isBlank()
                    || rule.ruleId().length() > 64 || !ruleIds.add(rule.ruleId())
                    || rule.warningThreshold() == null || rule.criticalThreshold() == null
                    || rule.recoveryThreshold() == null)
                throw new IllegalArgumentException("invalid operational rule");
        }
    }
    private static void keywords(List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128
                    || !seen.add(value)) throw new IllegalArgumentException("invalid keyword");
        }
    }
    private static String owner(Actor actor) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank())
            throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED, null);
        if (!"owner".equals(actor.role()))
            throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN, null);
        return actor.userId();
    }
    private static String commandHash(String actorId, PreferencesWrite value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("preferences-command-v1\n".getBytes(StandardCharsets.UTF_8));
            field(digest, actorId);
            field(digest, Long.toString(value.expectedRevision()));
            field(digest, Integer.toString(value.followKeywords().size()));
            value.followKeywords().forEach(item -> field(digest, item));
            field(digest, Integer.toString(value.blockedKeywords().size()));
            value.blockedKeywords().forEach(item -> field(digest, item));
            field(digest, Boolean.toString(value.aiPaused()));
            field(digest, value.defaultPackageId());
            field(digest, Integer.toString(value.operationalLimits().size()));
            for (OperationalRule rule : value.operationalLimits()) {
                field(digest, rule.ruleId());
                field(digest, rule.warningThreshold());
                field(digest, rule.criticalThreshold());
                field(digest, rule.recoveryThreshold());
                field(digest, rule.notificationSecretRef());
                field(digest, Boolean.toString(rule.notificationConfigured()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
