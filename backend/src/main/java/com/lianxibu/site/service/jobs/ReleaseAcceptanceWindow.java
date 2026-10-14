package com.lianxibu.site.service.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Immutable host permission adds a bounded acceptance window to the normal schedule. */
public final class ReleaseAcceptanceWindow {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Instant issued;
    private final Instant until;

    private ReleaseAcceptanceWindow(Instant issued, Instant until) {
        this.issued = issued;
        this.until = until;
    }

    public static ReleaseAcceptanceWindow normal() {
        return new ReleaseAcceptanceWindow(null, null);
    }

    public static ReleaseAcceptanceWindow fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment);
        String release = environment.get("SITE_AI_ACCEPTANCE_RELEASE");
        String start = environment.get("SITE_AI_ACCEPTANCE_ISSUED_AT");
        String end = environment.get("SITE_AI_ACCEPTANCE_UNTIL");
        if (release == null && start == null && end == null) return normal();
        try {
            if (release == null || !release.matches("[1-9][0-9]{9}") || start == null || end == null)
                throw new IllegalArgumentException();
            Instant issued = Instant.parse(start);
            Instant until = Instant.parse(end);
            if (issued.getEpochSecond() < 0 || issued.getNano() % 1000 != 0 || until.getNano() % 1000 != 0
                    || !issued.isBefore(until) || Duration.between(issued, until).compareTo(Duration.ofHours(12)) > 0)
                throw new IllegalArgumentException();
            return new ReleaseAcceptanceWindow(issued, until);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("release acceptance window configuration unavailable");
        }
    }

    private static final class RuntimePolicy {
        private static final ReleaseAcceptanceWindow VALUE = fromEnvironment(System.getenv());
    }

    public static ReleaseAcceptanceWindow current() {
        return RuntimePolicy.VALUE;
    }

    public boolean isOpen(Instant now) {
        Objects.requireNonNull(now);
        LocalTime local = now.atZone(SHANGHAI).toLocalTime();
        return !local.isBefore(LocalTime.of(1, 0)) && local.isBefore(LocalTime.of(7, 0)) || acceptanceOpen(now);
    }

    public Instant nextEligible(Instant now) {
        return isOpen(now) ? now : nextWindow(now);
    }

    public Instant nextWindow(Instant now) {
        if (acceptanceOpen(Objects.requireNonNull(now))) return now;
        var local = Objects.requireNonNull(now).atZone(SHANGHAI);
        var day = local.toLocalDate();
        if (!local.toLocalTime().isBefore(LocalTime.of(7, 0))) day = day.plusDays(1);
        return day.atTime(1, 0).atZone(SHANGHAI).toInstant();
    }

    private boolean acceptanceOpen(Instant now) {
        return issued != null && !now.isBefore(issued) && now.isBefore(until);
    }

    @Override public String toString() { return "ReleaseAcceptanceWindow[redacted]"; }
}
