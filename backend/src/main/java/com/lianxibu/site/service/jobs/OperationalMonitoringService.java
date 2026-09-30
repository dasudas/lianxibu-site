package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.settings.OperationalRulePolicy;
import com.lianxibu.site.service.settings.SettingsService.OperationalRule;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Transactional rule state/notice intent port; network delivery is outside this service. */
public final class OperationalMonitoringService {
    public enum Action { ACKNOWLEDGE, RESUME_CAPACITY, SILENCE }
    public record Configuration(long revision, List<OperationalRule> rules) {
        public Configuration { rules = List.copyOf(rules); }
    }
    public interface ConfigurationSource { Configuration current(); }
    public interface Store {
        State committed(String actionKey, String commandHash);
        State read(Key key);
        /** CAS summary and append immutable ops_rule dispositions/notice intents atomically. */
        void save(State previous, State next, List<Notice> notices, String actionKey, String commandHash,
                  String operatorId, String reason);
    }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }
    private final Store store;
    private final ConfigurationSource configuration;
    private final SharedWriteExecutor writes;
    private final Clock clock;
    private final OperationalRuleEngine engine = new OperationalRuleEngine();
    public OperationalMonitoringService(Store store, ConfigurationSource configuration,
                                        SharedWriteExecutor writes, Clock clock) {
        if (store == null || configuration == null || writes == null || clock == null)
            throw new IllegalArgumentException("monitoring dependencies required");
        this.store = store; this.configuration = configuration; this.writes = writes; this.clock = clock;
    }
    public State sample(Key key, boolean available, Reading reading, Instant sampledAt, ActionContext action) {
        if (key == null || action == null) throw new IllegalArgumentException("sample identity required");
        Instant now = sampledAt;
        if (now == null || now.getNano() != 0 || now.getEpochSecond() % 60 != 0
                || now.isAfter(clock.instant().truncatedTo(ChronoUnit.MINUTES)))
            throw new IllegalArgumentException("invalid captured sample time");
        String hash = digest("sample", key.ruleId(), key.component(), key.resourceKey(), now.toString(),
                Boolean.toString(available), reading == null ? null : reading.getClass().getSimpleName(),
                reading == null ? null : reading.toString());
        return writes.execute(action, receipt -> store.committed(receipt, hash), context -> {
            State before = store.read(key); if (before == null) before = State.empty(key);
            Configuration snapshot = configuration.current();
            var rule = configured(snapshot, key.ruleId());
            var transition = engine.sample(before, rule == null ? null : OperationalRulePolicy.compile(rule),
                    snapshot == null ? 0 : snapshot.revision(), now, available, reading);
            store.save(before, transition.state(), transition.notices(), context.actionKey(), hash, null, "sample");
            return transition.state();
        });
    }
    public State act(Actor actor, Key key, long expectedRevision, Action operation, String reason,
                     Instant silenceUntil, ActionContext action) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank())
            throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED, null);
        if (!"owner".equals(actor.role())) throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN, null);
        if (key == null || expectedRevision < 0 || operation == null || reason == null || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > 2048 || action == null)
            throw new IllegalArgumentException("invalid operational action");
        Instant now = clock.instant();
        String hash = digest("admin", actor.userId(), key.ruleId(), key.component(), key.resourceKey(),
                Long.toString(expectedRevision), operation.name(), reason, silenceUntil == null ? null : silenceUntil.toString());
        return writes.execute(action, receipt -> store.committed(receipt, hash), context -> {
            State before = store.read(key);
            if (before == null || before.revision() != expectedRevision) throw new Rejected("revision_conflict");
            Configuration snapshot = configuration.current(); var rule = configured(snapshot, key.ruleId());
            if (rule == null) throw new Rejected("configuration_unknown");
            var config = OperationalRulePolicy.compile(rule);
            boolean ack = before.acknowledged(), recoveryReady = before.recoveryReady(); var guards = before.guards();
            var status = before.status(); var level = before.level(); var reminder = before.nextReminderAt();
            Instant silence = before.silencedUntil();
            List<Notice> notices = List.of();
            switch (operation) {
                case ACKNOWLEDGE -> { if (level == null) throw new Rejected("alert_not_active"); ack = true; }
                case RESUME_CAPACITY -> {
                    if (!"OPS-04".equals(key.ruleId()) || !before.recoveryReady() || guards.isEmpty()
                            || snapshot.revision() != before.configurationRevision() || before.lastSampleAt() == null
                            || now.isBefore(before.lastSampleAt()) || now.isAfter(before.lastSampleAt().plusSeconds(config.seconds("sample_interval_seconds"))))
                        throw new Rejected("recovery_conditions_unmet");
                    guards = Set.of(); recoveryReady = false; status = Status.RESOLVED; level = null; reminder = null; ack = false;
                    notices = List.of(new Notice("resolved", before.level(), now, before.observedValue(), before.threshold()));
                }
                case SILENCE -> {
                    if (Set.of("OPS-03", "OPS-04", "OPS-05", "OPS-10").contains(key.ruleId())) throw new Rejected("rule_cannot_be_silenced");
                    if (silenceUntil == null || !silenceUntil.isAfter(now)
                            || silenceUntil.isAfter(now.plusSeconds(config.seconds("maximum_silence_seconds"))))
                        throw new Rejected("invalid_silence_expiry");
                    silence = silenceUntil;
                }
            }
            State next = new State(key, before.revision() + 1, before.configurationRevision(), status, level,
                    before.firstSeenAt(), before.lastSeenAt(), before.lastSampleAt(), before.observedValue(),
                    before.threshold(), before.timers(), before.counters(), guards, ack, recoveryReady, reminder, silence);
            store.save(before, next, notices, context.actionKey(), hash, actor.userId(), reason);
            return next;
        });
    }
    private static OperationalRule configured(Configuration snapshot, String ruleId) {
        if (snapshot == null || snapshot.revision() < 0) return null;
        var matches = snapshot.rules().stream().filter(rule -> rule != null && ruleId.equals(rule.ruleId())).toList();
        return matches.size() == 1 && OperationalRulePolicy.ready(matches.getFirst()) ? matches.getFirst() : null;
    }
    private static String digest(String... fields) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
