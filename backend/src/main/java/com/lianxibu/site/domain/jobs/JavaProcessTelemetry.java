package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** Kernel-reported resident memory of the current Java process and its actual birth identity. */
public final class JavaProcessTelemetry {
    private JavaProcessTelemetry() { }

    public record Evidence(long pid, Instant startedAt, Instant receivedAt, long residentBytes) {
        public Evidence {
            if (pid <= 0 || !valid(startedAt) || !valid(receivedAt)
                    || startedAt.isAfter(receivedAt) || residentBytes < 0) {
                throw new IllegalArgumentException("invalid Java process facts");
            }
        }

        private static boolean valid(Instant value) {
            return value != null && value.getEpochSecond() >= 0 && value.getNano() % 1000 == 0;
        }

        @Override public String toString() { return "JavaProcessEvidence[redacted]"; }
    }

    /** Null represents unavailable telemetry; a sample alone does not establish OOM or budget status. */
    @FunctionalInterface public interface Api { Evidence read(); }
}
