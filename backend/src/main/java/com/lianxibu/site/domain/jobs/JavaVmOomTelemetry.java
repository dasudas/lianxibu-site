package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** Cumulative HotSpot-reported OOM categories for the original process, not arbitrary application errors. */
public final class JavaVmOomTelemetry {
    private JavaVmOomTelemetry() { }

    public record Evidence(long pid, Instant startedAt, Instant receivedAt, long javaHeapEvents,
                           long metaspaceEvents, long classMetaspaceEvents) {
        public Evidence {
            if (pid <= 0 || !valid(startedAt) || !valid(receivedAt) || startedAt.isAfter(receivedAt)
                    || !counter(javaHeapEvents) || !counter(metaspaceEvents) || !counter(classMetaspaceEvents)) {
                throw new IllegalArgumentException("invalid Java VM OOM facts");
            }
        }

        private static boolean valid(Instant value) {
            return value != null && value.getEpochSecond() >= 0 && value.getNano() % 1000 == 0;
        }
        private static boolean counter(long value) { return value >= 0 && value <= Integer.MAX_VALUE; }

        @Override public String toString() { return "JavaVmOomEvidence[redacted]"; }
    }

    @FunctionalInterface public interface Api { Evidence read(); }
}
