package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.settings.OperationalRulePolicy.Compiled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic transitions over durable samples; contains no external notification or cleanup. */
public final class OperationalRuleEngine {
    public enum Level { WARNING, CRITICAL }
    public enum Status { NORMAL, ACTIVE, RESOLVED, TELEMETRY_UNKNOWN, CONFIGURATION_UNKNOWN }
    public enum Guard { NEW_RUN, PREPARE_ANALYSIS, DISPATCH_AI, UPLOAD_OBSERVATION, NEW_FETCH, RETRY_MEMBER }
    public record Key(String ruleId, String component, String resourceKey) {
        public Key {
            if (ruleId == null || !ruleId.matches("OPS-(0[1-9]|10)") || component == null
                    || !component.matches("[a-z][a-z0-9_]{0,63}") || resourceKey == null
                    || resourceKey.isBlank() || resourceKey.codePointCount(0, resourceKey.length()) > 128)
                throw new IllegalArgumentException("invalid operational identity");
        }
    }
    public sealed interface Reading permits Backlog, Heartbeat, SlotRecovery, Capacity, Disk, Memory,
            ContractFailures, Provider, ApiHealth, Telemetry { }
    /** Eligible age must be reconstructed from durable ready/unpaused spans. */
    public record Backlog(long eligibleSeconds, long wallSeconds, boolean explicitlyDisposed) implements Reading { }
    public record Heartbeat(long gapSeconds, boolean expected, boolean validHeartbeat, boolean reconciled) implements Reading { }
    public record SlotRecovery(long overdueSeconds, long consecutiveFailures, boolean scanSuccessful) implements Reading { }
    public record Capacity(BigDecimal freePercent) implements Reading { }
    public record Disk(BigDecimal queueMib, BigDecimal freeMib) implements Reading { }
    /** Separate Java and collector resources; unavailable fields are not filled with zero. */
    public record Memory(boolean javaNode, BigDecimal measuredMib, boolean outOfMemory) implements Reading { }
    public record ContractFailures(long newEvents, long eventsInWindow, boolean revalidated) implements Reading { }
    public record Provider(boolean explicitFailure, long unknownCount, long oldestUnknownSeconds,
                           boolean repairedAndValidated) implements Reading { }
    /** windowEndedAt identifies a complete, nonoverlapping rate window. */
    public record ApiHealth(long requests, BigDecimal errorPercent, BigDecimal lockP95Millis,
                            Instant windowEndedAt) implements Reading { }
    public record Telemetry(long metricAgeSeconds, long capacityAgeSeconds) implements Reading { }
    public record State(Key key, long revision, long configurationRevision, Status status, Level level,
                        Instant firstSeenAt, Instant lastSeenAt, Instant lastSampleAt,
                        String observedValue, String threshold, Map<String, Instant> timers,
                        Map<String, Long> counters, Set<Guard> guards, boolean acknowledged,
                        boolean recoveryReady, Instant nextReminderAt, Instant silencedUntil) {
        public State { timers = Map.copyOf(timers); counters = Map.copyOf(counters); guards = Set.copyOf(guards); }
        public static State empty(Key key) {
            return new State(key, 0, -1, Status.NORMAL, null, null, null, null, null, null,
                    Map.of(), Map.of(), Set.of(), false, false, null, null);
        }
    }
    public record Notice(String action, Level level, Instant at, String observedValue, String threshold) { }
    public record Transition(State state, List<Notice> notices) {
        public Transition { notices = List.copyOf(notices); }
    }
    private record Decision(Level severity, boolean healthy, boolean manualRecovery,
                            Set<Guard> requestedGuards, String observed, String threshold,
                            boolean verifiedProviderRepair) { }
    private static final Set<Guard> CAPACITY_GUARDS = Set.of(Guard.NEW_RUN, Guard.PREPARE_ANALYSIS,
            Guard.DISPATCH_AI, Guard.UPLOAD_OBSERVATION);

    public Transition sample(State previous, Compiled config, long configurationRevision,
                             Instant now, boolean available, Reading reading) {
        if (previous == null || now == null || configurationRevision < 0
                || now.getNano() != 0 || now.getEpochSecond() % 60 != 0
                || previous.lastSampleAt() != null && !now.isAfter(previous.lastSampleAt()))
            throw new IllegalArgumentException("invalid operational sample sequence");
        Map<String, Instant> timers = new HashMap<>(previous.timers());
        Map<String, Long> counters = new HashMap<>(previous.counters());
        boolean continuous = previous.lastSampleAt() != null
                && now.equals(previous.lastSampleAt().plusSeconds(60))
                && configurationRevision == previous.configurationRevision();
        if (!continuous) {
            timers.clear(); counters.clear();
            if ("OPS-08".equals(previous.key().ruleId()) && previous.guards().contains(Guard.DISPATCH_AI))
                counters.put("provider_failed", 1L);
        }
        if ("OPS-08".equals(previous.key().ruleId()) && previous.guards().contains(Guard.DISPATCH_AI))
            counters.put("provider_failed", 1L);
        if (config == null) return unknown(previous, configurationRevision, now, Status.CONFIGURATION_UNKNOWN);
        if (!previous.key().ruleId().equals(config.ruleId())) throw new IllegalArgumentException("rule identity mismatch");
        if (!available || reading == null) return unknown(previous, configurationRevision, now, Status.TELEMETRY_UNKNOWN);
        Decision decision = evaluate(config, reading, timers, counters, now);
        Set<Guard> guards = new java.util.HashSet<>(previous.guards());
        guards.addAll(decision.requestedGuards());
        if (decision.verifiedProviderRepair()) guards.remove(Guard.DISPATCH_AI);
        Level level = previous.level();
        Status status = previous.status();
        boolean acknowledged = previous.acknowledged(), recoveryReady = false;
        Instant first = previous.firstSeenAt(), reminder = previous.nextReminderAt();
        List<Notice> notices = new ArrayList<>();
        if (decision.severity() != null) {
            boolean firstTrigger = level == null;
            boolean upgrade = level == Level.WARNING && decision.severity() == Level.CRITICAL;
            status = Status.ACTIVE;
            if (firstTrigger || upgrade) {
                level = decision.severity(); acknowledged = false;
                if (firstTrigger) first = now;
                notices.add(new Notice(firstTrigger ? "trigger" : "upgrade", level, now, decision.observed(), decision.threshold()));
                reminder = now.plusSeconds(reminderSeconds(config, level));
            }
        } else if (decision.healthy()) {
            if (decision.manualRecovery() && !guards.isEmpty()) {
                recoveryReady = true; status = Status.ACTIVE;
            } else {
                guards.clear();
                if (level != null) notices.add(new Notice("resolved", level, now, decision.observed(), decision.threshold()));
                status = level == null ? Status.NORMAL : Status.RESOLVED;
                level = null; acknowledged = false; reminder = null;
            }
        } else if (level == null) status = Status.NORMAL;
        if (level != null && !acknowledged && notices.isEmpty() && reminder != null && !now.isBefore(reminder)
                && (previous.silencedUntil() == null || !now.isBefore(previous.silencedUntil()))) {
            notices.add(new Notice("reminder", level, now, decision.observed(), decision.threshold()));
            reminder = now.plusSeconds(reminderSeconds(config, level));
        }
        // Silence only same-level notification. Upgrades and recovery remain visible.
        if (previous.silencedUntil() != null && now.isBefore(previous.silencedUntil()))
            notices.removeIf(notice -> "reminder".equals(notice.action()));
        return new Transition(new State(previous.key(), previous.revision() + 1, configurationRevision,
                status, level, first, now, now, decision.observed(), decision.threshold(), timers, counters,
                guards, acknowledged, recoveryReady, reminder, previous.silencedUntil()), notices);
    }
    private Transition unknown(State previous, long configRevision, Instant now, Status status) {
        // Missing configuration or telemetry cannot release a guard or complete a window.
        return new Transition(new State(previous.key(), previous.revision() + 1, configRevision, status,
                previous.level(), previous.firstSeenAt(), now, now, null, previous.threshold(), Map.of(), Map.of(),
                previous.guards(), previous.acknowledged(), false, previous.nextReminderAt(), previous.silencedUntil()), List.of());
    }
    private static long reminderSeconds(Compiled config, Level level) {
        return config.seconds(level == Level.CRITICAL ? "critical_reminder_seconds" : "warning_reminder_seconds");
    }
    private Decision evaluate(Compiled c, Reading value, Map<String, Instant> timers,
                              Map<String, Long> counts, Instant now) {
        Level severity = null; boolean healthy = false, manual = false, repaired = false;
        Set<Guard> guards = Set.of(); BigDecimal observed, threshold = c.warning();
        switch (c.ruleId()) {
            case "OPS-01" -> {
                Backlog r = required(value, Backlog.class); nonnegative(r.eligibleSeconds(), r.wallSeconds());
                observed = number(r.eligibleSeconds());
                boolean warn = held(timers, "eligible_warning", observed.compareTo(c.warning()) >= 0, c.seconds("warning_hold_seconds"), now)
                        || number(r.wallSeconds()).compareTo(c.parameter("wall_warning_seconds")) >= 0;
                if (number(r.wallSeconds()).compareTo(c.critical()) >= 0) severity = Level.CRITICAL;
                else if (warn) severity = Level.WARNING;
                healthy = r.explicitlyDisposed() || held(timers, "healthy", observed.compareTo(c.recovery()) < 0
                        && number(r.wallSeconds()).compareTo(c.parameter("wall_warning_seconds")) < 0,
                        c.seconds("recovery_hold_seconds"), now);
                if (r.explicitlyDisposed()) severity = null;
                if (severity == Level.CRITICAL) { observed = number(r.wallSeconds()); threshold = c.critical(); }
            }
            case "OPS-02" -> {
                Heartbeat r = required(value, Heartbeat.class); nonnegative(r.gapSeconds()); observed = number(r.gapSeconds());
                boolean warn = consecutive(counts, "gap", r.expected() && observed.compareTo(c.warning()) > 0)
                        >= c.parameter("warning_sample_count").longValueExact();
                if (r.expected() && observed.compareTo(c.critical()) > 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (warn) severity = Level.WARNING;
                healthy = consecutive(counts, "heartbeats", r.validHeartbeat() && observed.compareTo(c.warning()) <= 0)
                        >= c.recovery().longValueExact() && r.reconciled();
            }
            case "OPS-03" -> {
                SlotRecovery r = required(value, SlotRecovery.class); nonnegative(r.overdueSeconds(), r.consecutiveFailures());
                observed = number(r.overdueSeconds());
                if (observed.compareTo(c.warning()) > 0 || number(r.consecutiveFailures()).compareTo(c.critical()) >= 0) {
                    severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI);
                    if (observed.compareTo(c.warning()) <= 0) { observed = number(r.consecutiveFailures()); threshold = c.critical(); }
                }
                healthy = held(timers, "healthy", r.scanSuccessful() && r.overdueSeconds() == 0
                        && r.consecutiveFailures() == 0, c.recovery().longValueExact(), now);
            }
            case "OPS-04" -> {
                Capacity r = required(value, Capacity.class); observed = bounded(r.freePercent(), "100");
                boolean critical = held(timers, "critical", observed.compareTo(c.critical()) < 0, c.seconds("critical_hold_seconds"), now);
                boolean warning = held(timers, "warning", observed.compareTo(c.warning()) < 0, c.seconds("warning_hold_seconds"), now);
                if (observed.compareTo(c.parameter("protect_free_percent")) < 0) {
                    severity = Level.CRITICAL; guards = CAPACITY_GUARDS; threshold = c.parameter("protect_free_percent");
                } else if (critical) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (warning) severity = Level.WARNING;
                healthy = held(timers, "healthy", observed.compareTo(c.recovery()) >= 0, c.seconds("recovery_hold_seconds"), now);
                manual = true;
            }
            case "OPS-05" -> {
                Disk r = required(value, Disk.class); observed = bounded(r.queueMib(), "9007199254740991");
                BigDecimal free = bounded(r.freeMib(), "9007199254740991");
                boolean warning = held(timers, "warning", observed.compareTo(c.warning()) >= 0, c.seconds("warning_hold_seconds"), now);
                if (observed.compareTo(c.critical()) >= 0 || free.compareTo(c.parameter("disk_free_min_mib")) < 0) {
                    severity = Level.CRITICAL; guards = Set.of(Guard.NEW_FETCH);
                    if (free.compareTo(c.parameter("disk_free_min_mib")) < 0) { observed = free; threshold = c.parameter("disk_free_min_mib"); }
                    else threshold = c.critical();
                } else if (warning) severity = Level.WARNING;
                healthy = held(timers, "healthy", r.queueMib().compareTo(c.recovery()) < 0
                        && free.compareTo(c.parameter("recovery_disk_free_mib")) >= 0, c.seconds("recovery_hold_seconds"), now);
            }
            case "OPS-06" -> {
                Memory r = required(value, Memory.class); observed = bounded(r.measuredMib(), "9007199254740991");
                if (r.javaNode()) {
                    if (held(timers, "warning", observed.compareTo(c.warning()) < 0, c.seconds("java_warning_hold_seconds"), now)) {
                        severity = Level.WARNING; guards = Set.of(Guard.DISPATCH_AI);
                    }
                    if (r.outOfMemory()) { severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = number(1); threshold = number(1); }
                    healthy = held(timers, "healthy", !r.outOfMemory() && observed.compareTo(c.recovery()) >= 0, c.seconds("recovery_hold_seconds"), now);
                } else {
                    threshold = c.parameter("collector_warning_rss_mib");
                    if (r.outOfMemory() || observed.compareTo(c.critical()) >= 0) {
                        severity = Level.CRITICAL; guards = Set.of(Guard.NEW_FETCH); threshold = c.critical();
                        if (r.outOfMemory()) { observed = number(1); threshold = number(1); }
                    }
                    else if (held(timers, "warning", observed.compareTo(threshold) > 0, c.seconds("collector_warning_hold_seconds"), now)) severity = Level.WARNING;
                    healthy = held(timers, "healthy", !r.outOfMemory() && observed.compareTo(c.parameter("collector_recovery_rss_mib")) <= 0, c.seconds("recovery_hold_seconds"), now);
                }
            }
            case "OPS-07" -> {
                ContractFailures r = required(value, ContractFailures.class); nonnegative(r.newEvents(), r.eventsInWindow());
                if (r.newEvents() > r.eventsInWindow()) throw new IllegalArgumentException("invalid event window");
                observed = number(r.eventsInWindow());
                if (r.newEvents() > 0) { severity = Level.WARNING; guards = Set.of(Guard.RETRY_MEMBER); }
                if (observed.compareTo(c.critical()) >= 0) { severity = Level.CRITICAL; threshold = c.critical(); guards = Set.of(Guard.RETRY_MEMBER, Guard.NEW_FETCH); }
                healthy = held(timers, "healthy", r.newEvents() == 0, c.recovery().longValueExact(), now) && r.revalidated();
            }
            case "OPS-08" -> {
                Provider r = required(value, Provider.class); nonnegative(r.unknownCount(), r.oldestUnknownSeconds());
                observed = number(r.unknownCount());
                if (r.explicitFailure()) { severity = Level.CRITICAL; guards = Set.of(Guard.DISPATCH_AI); observed = number(1); threshold = c.critical(); }
                else if (observed.compareTo(c.warning()) >= 0 || number(r.oldestUnknownSeconds()).compareTo(c.parameter("unknown_age_seconds")) >= 0)
                    severity = Level.WARNING;
                if (!r.explicitFailure() && number(r.oldestUnknownSeconds()).compareTo(c.parameter("unknown_age_seconds")) >= 0) {
                    observed = number(r.oldestUnknownSeconds()); threshold = c.parameter("unknown_age_seconds");
                }
                boolean blockedBefore = counts.getOrDefault("provider_failed", 0L) > 0;
                if (r.explicitFailure()) counts.put("provider_failed", 1L);
                if (!r.explicitFailure() && r.repairedAndValidated()) { counts.put("provider_failed", 0L); repaired = true; }
                healthy = !r.explicitFailure() && r.unknownCount() == 0 && (!blockedBefore || r.repairedAndValidated());
            }
            case "OPS-09" -> {
                ApiHealth r = required(value, ApiHealth.class); nonnegative(r.requests());
                observed = bounded(r.errorPercent(), "100"); BigDecimal lock = bounded(r.lockP95Millis(), "9007199254740991");
                long window = c.seconds("rate_window_seconds");
                if (r.windowEndedAt() == null || r.windowEndedAt().isAfter(now)
                        || r.windowEndedAt().isBefore(now.minusSeconds(window)) || r.windowEndedAt().getNano() != 0)
                    throw new IllegalArgumentException("invalid rate window");
                boolean requests = number(r.requests()).compareTo(c.parameter("minimum_requests")) >= 0;
                Instant lastWindow = timers.get("rate_window_end");
                if (lastWindow == null || r.windowEndedAt().isAfter(lastWindow)) {
                    if (lastWindow != null && !r.windowEndedAt().equals(lastWindow.plusSeconds(window))) counts.put("rate_windows", 0L);
                    consecutive(counts, "rate_windows", requests && observed.compareTo(c.warning()) > 0);
                    timers.put("rate_window_end", r.windowEndedAt());
                } else if (!lastWindow.equals(r.windowEndedAt())) throw new IllegalArgumentException("rate window rollback");
                boolean lockWarning = held(timers, "lock_warning", lock.compareTo(c.parameter("lock_wait_p95_ms")) > 0,
                        c.seconds("lock_warning_hold_seconds"), now);
                if (requests && observed.compareTo(c.critical()) >= 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (counts.getOrDefault("rate_windows", 0L) >= c.parameter("warning_window_count").longValueExact()) severity = Level.WARNING;
                else if (lockWarning) { severity = Level.WARNING; observed = lock; threshold = c.parameter("lock_wait_p95_ms"); }
                healthy = held(timers, "healthy", r.requests() > 0 && r.errorPercent().compareTo(c.recovery()) < 0
                        && lock.compareTo(c.parameter("lock_wait_p95_ms")) <= 0, c.seconds("recovery_hold_seconds"), now);
            }
            case "OPS-10" -> {
                Telemetry r = required(value, Telemetry.class); nonnegative(r.metricAgeSeconds(), r.capacityAgeSeconds()); observed = number(Math.max(r.metricAgeSeconds(), r.capacityAgeSeconds()));
                if (observed.compareTo(c.critical()) > 0) { severity = Level.CRITICAL; threshold = c.critical(); }
                else if (observed.compareTo(c.warning()) > 0) severity = Level.WARNING;
                if (number(r.capacityAgeSeconds()).compareTo(c.parameter("capacity_unknown_guard_seconds")) > 0) guards = CAPACITY_GUARDS;
                healthy = held(timers, "healthy", r.metricAgeSeconds() <= c.seconds("sample_interval_seconds") && r.capacityAgeSeconds() <= c.seconds("sample_interval_seconds"),
                        c.recovery().longValueExact(), now);
            }
            default -> throw new IllegalArgumentException("unsupported operational rule");
        }
        return new Decision(severity, healthy, manual, guards, observed.toPlainString(), threshold.toPlainString(), repaired);
    }
    private static boolean held(Map<String, Instant> timers, String key, boolean condition, long seconds, Instant now) {
        if (!condition) { timers.remove(key); return false; }
        Instant since = timers.computeIfAbsent(key, ignored -> now);
        return !now.isBefore(since.plusSeconds(seconds));
    }
    private static long consecutive(Map<String, Long> counters, String key, boolean condition) {
        long value = condition ? Math.min(1_000_000L, counters.getOrDefault(key, 0L) + 1) : 0;
        counters.put(key, value); return value;
    }
    private static BigDecimal number(long value) { return BigDecimal.valueOf(value); }
    private static void nonnegative(long... values) { for (long value : values) if (value < 0 || value > 9007199254740991L) throw new IllegalArgumentException("invalid metric"); }
    private static BigDecimal bounded(BigDecimal value, String max) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal(max)) > 0 || value.scale() > 8)
            throw new IllegalArgumentException("invalid metric");
        return value;
    }
    private static <T> T required(Reading reading, Class<T> type) {
        if (!type.isInstance(reading)) throw new IllegalArgumentException("reading rule mismatch"); return type.cast(reading);
    }
}
