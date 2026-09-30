package com.lianxibu.site.service.settings;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Explicit validated configuration; absent parameters never acquire defaults. */
public final class OperationalRulePolicy {
    private OperationalRulePolicy() { }
    public record Compiled(String ruleId, BigDecimal warning, BigDecimal critical, BigDecimal recovery,
                           Map<String, BigDecimal> parameters) {
        public Compiled { parameters = Map.copyOf(parameters); }
        public long seconds(String name) { return parameters.get(name).longValueExact(); }
        public BigDecimal parameter(String name) { return parameters.get(name); }
    }
    private static final Set<String> COMMON = Set.of("sample_interval_seconds", "warning_reminder_seconds",
            "critical_reminder_seconds", "maximum_silence_seconds", "resolved_retention_seconds");
    private static final Map<String, Set<String>> SPECIFIC = Map.ofEntries(
            Map.entry("OPS-01", Set.of("warning_hold_seconds", "wall_warning_seconds", "recovery_hold_seconds")),
            Map.entry("OPS-02", Set.of("warning_sample_count", "reconciliation_required")),
            Map.entry("OPS-03", Set.of("recovery_retry_seconds")),
            Map.entry("OPS-04", Set.of("warning_hold_seconds", "critical_hold_seconds", "protect_free_percent", "recovery_hold_seconds", "owner_confirmation_required")),
            Map.entry("OPS-05", Set.of("warning_hold_seconds", "disk_free_min_mib", "recovery_disk_free_mib", "recovery_hold_seconds")),
            Map.entry("OPS-06", Set.of("java_warning_hold_seconds", "collector_warning_rss_mib", "collector_warning_hold_seconds", "collector_recovery_rss_mib", "recovery_hold_seconds")),
            Map.entry("OPS-07", Set.of("event_window_seconds", "owner_revalidation_required")),
            Map.entry("OPS-08", Set.of("unknown_age_seconds", "configuration_validation_required")),
            Map.entry("OPS-09", Set.of("minimum_requests", "rate_window_seconds", "warning_window_count", "lock_wait_p95_ms", "lock_warning_hold_seconds", "recovery_hold_seconds")),
            Map.entry("OPS-10", Set.of("capacity_unknown_guard_seconds")));

    public static boolean ready(SettingsService.OperationalRule rule) {
        try { compile(rule); return true; } catch (IllegalArgumentException invalid) { return false; }
    }
    public static Compiled compile(SettingsService.OperationalRule rule) {
        if (rule == null || !SPECIFIC.containsKey(rule.ruleId()) || rule.ruleParameters() == null)
            throw invalid();
        Set<String> expected = new HashSet<>(COMMON); expected.addAll(SPECIFIC.get(rule.ruleId()));
        if (!rule.ruleParameters().keySet().equals(expected)) throw invalid();
        Map<String, BigDecimal> parameters = new HashMap<>();
        rule.ruleParameters().forEach((key, value) -> {
            BigDecimal number = decimal(value);
            // Percent may be fractional; all other settings have integral physical units.
            range(number, "protect_free_percent".equals(key) ? "0.00000001" : "1", "31536000");
            if (!"protect_free_percent".equals(key)) integer(number);
            if (key.endsWith("_required")) equal(number, "1");
            parameters.put(key, number);
        });
        equal(parameters.get("sample_interval_seconds"), "60");
        range(parameters.get("maximum_silence_seconds"), "1", "7200");
        equal(parameters.get("resolved_retention_seconds"), "604800");
        range(parameters.get("warning_reminder_seconds"), "60", "86400");
        range(parameters.get("critical_reminder_seconds"), "60", "86400");
        le(parameters.get("critical_reminder_seconds"), parameters.get("warning_reminder_seconds"));
        BigDecimal warning = decimal(rule.warningThreshold()), critical = decimal(rule.criticalThreshold()),
                recovery = decimal(rule.recoveryThreshold());
        Compiled value = new Compiled(rule.ruleId(), warning, critical, recovery, parameters);
        if (rule.notificationSecretRef() != null && (rule.notificationSecretRef().isBlank()
                || rule.notificationSecretRef().codePointCount(0, rule.notificationSecretRef().length()) > 128)) throw invalid();
        if (rule.notificationConfigured() && rule.notificationSecretRef() == null) throw invalid();
        switch (rule.ruleId()) {
            case "OPS-01" -> {
                integralRange(value, "1", "31536000");
                le(recovery, warning); le(warning, value.parameter("wall_warning_seconds"));
                lt(value.parameter("wall_warning_seconds"), critical);
            }
            case "OPS-02" -> {
                range(warning, "60", "86400"); range(critical, "60", "86400"); lt(warning, critical);
                integer(warning); integer(critical); range(recovery, "1", "100"); integer(recovery);
                range(value.parameter("warning_sample_count"), "1", "100");
            }
            case "OPS-03" -> {
                integralRange(value, "1", "86400"); range(critical, "1", "100");
                range(value.parameter("recovery_retry_seconds"), "1", "3600");
            }
            case "OPS-04" -> {
                range(warning, "0.00000001", "100"); range(critical, "0.00000001", "100");
                range(recovery, "0.00000001", "100"); lt(value.parameter("protect_free_percent"), critical);
                lt(critical, warning); le(warning, recovery);
            }
            case "OPS-05" -> {
                integralRange(value, "1", "1048576"); lt(warning, critical); le(recovery, warning);
                lt(value.parameter("disk_free_min_mib"), value.parameter("recovery_disk_free_mib"));
            }
            case "OPS-06" -> {
                integralRange(value, "1", "1048576"); lt(warning, recovery);
                lt(value.parameter("collector_warning_rss_mib"), critical);
                lt(value.parameter("collector_recovery_rss_mib"), value.parameter("collector_warning_rss_mib"));
            }
            case "OPS-07" -> { integralRange(value, "1", "31536000"); le(warning, critical); range(critical, "1", "100"); }
            case "OPS-08" -> { integralRange(value, "1", "31536000"); range(warning, "1", "10000"); equal(critical, "1"); equal(recovery, "1"); }
            case "OPS-09" -> {
                range(warning, "0", "100"); range(critical, "0.00000001", "100"); range(recovery, "0", "100");
                lt(warning, critical); le(recovery, warning);
                range(value.parameter("minimum_requests"), "1", "10000000");
                range(value.parameter("warning_window_count"), "1", "100");
                range(value.parameter("lock_wait_p95_ms"), "1", "60000");
            }
            case "OPS-10" -> {
                integralRange(value, "1", "86400"); lt(warning, critical);
                le(warning, value.parameter("capacity_unknown_guard_seconds"));
                le(value.parameter("capacity_unknown_guard_seconds"), critical);
            }
            default -> throw invalid();
        }
        return value;
    }
    private static void integralRange(Compiled value, String min, String max) {
        for (BigDecimal number : new BigDecimal[]{value.warning(), value.critical(), value.recovery()}) {
            range(number, min, max); integer(number);
        }
    }
    private static BigDecimal decimal(String value) {
        if (value == null || value.length() > 30 || !value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,8})?")) throw invalid();
        return new BigDecimal(value);
    }
    private static void integer(BigDecimal value) { if (value.stripTrailingZeros().scale() > 0) throw invalid(); }
    private static void equal(BigDecimal value, String expected) { if (value.compareTo(new BigDecimal(expected)) != 0) throw invalid(); }
    private static void range(BigDecimal value, String min, String max) {
        if (value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0) throw invalid();
    }
    private static void lt(BigDecimal left, BigDecimal right) { if (left.compareTo(right) >= 0) throw invalid(); }
    private static void le(BigDecimal left, BigDecimal right) { if (left.compareTo(right) > 0) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid operational configuration"); }
}
