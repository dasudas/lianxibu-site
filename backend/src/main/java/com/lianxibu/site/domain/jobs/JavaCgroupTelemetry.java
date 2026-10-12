package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** Local kernel facts for the cgroup containing this Java process; not JVM heap OOM evidence. */
public final class JavaCgroupTelemetry {
    private JavaCgroupTelemetry() { }

    public record Evidence(long pid, Instant startedAt, Instant receivedAt, String resourceEpoch,
                           long currentBytes, Long maximumBytes, long oomEvents, long oomKills) {
        public Evidence {
            if (pid <= 0 || !valid(startedAt) || !valid(receivedAt) || startedAt.isAfter(receivedAt)
                    || resourceEpoch == null || !resourceEpoch.matches("[0-9a-f]{64}")
                    || currentBytes < 0 || maximumBytes != null && maximumBytes <= 0 || oomEvents < 0 || oomKills < 0) {
                throw new IllegalArgumentException("invalid Java cgroup facts");
            }
        }

        private static boolean valid(Instant value) {
            return value != null && value.getEpochSecond() >= 0 && value.getNano() % 1000 == 0;
        }

        @Override public String toString() { return "JavaCgroupEvidence[redacted]"; }
    }

    /** Null sample is unknown; null maximumBytes within a sample is the native unlimited value. */
    @FunctionalInterface public interface Api { Evidence read(); }
}
