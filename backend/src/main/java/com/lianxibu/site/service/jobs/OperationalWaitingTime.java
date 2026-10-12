package com.lianxibu.site.service.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Ready/unpaused durable intervals intersect the fixed eligible window; wall age is separate. */
public final class OperationalWaitingTime {
    public record Span(Instant start, Instant end, boolean ready, boolean globallyPaused) { }
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private OperationalWaitingTime() { }
    public static long eligibleSeconds(List<Span> spans) {
        if (spans == null || spans.size() > 10000) throw new IllegalArgumentException("invalid waiting spans");
        long total = 0; Instant previousEnd = null;
        for (Span span : spans) {
            if (span == null || span.start() == null || span.end() == null || span.end().isBefore(span.start())
                    || previousEnd != null && span.start().isBefore(previousEnd)
                    || Duration.between(span.start(), span.end()).toDays() > 3660)
                throw new IllegalArgumentException("invalid waiting span sequence");
            previousEnd = span.end();
            if (!span.ready() || span.globallyPaused()) continue;
            var date = span.start().atZone(ZONE).toLocalDate();
            var finalDate = span.end().atZone(ZONE).toLocalDate();
            for (; !date.isAfter(finalDate); date = date.plusDays(1)) {
                Instant start = date.atTime(LocalTime.of(1, 0)).atZone(ZONE).toInstant();
                Instant end = date.atTime(LocalTime.of(7, 0)).atZone(ZONE).toInstant();
                if (start.isBefore(span.start())) start = span.start();
                if (end.isAfter(span.end())) end = span.end();
                if (end.isAfter(start)) total = Math.addExact(total, Duration.between(start, end).toNanos());
            }
        }
        return total / 1_000_000_000L;
    }
}
