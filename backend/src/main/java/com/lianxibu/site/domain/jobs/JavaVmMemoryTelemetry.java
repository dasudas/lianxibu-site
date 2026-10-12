package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** Native runtime budget and startup policy facts, which alone do not establish absence of OOM. */
public final class JavaVmMemoryTelemetry {
    private JavaVmMemoryTelemetry() { }

    public record Evidence(long pid, Instant startedAt, Instant receivedAt, long initialHeapBytes,
                           long maximumHeapBytes, long maximumMetaspaceBytes, long maximumDirectBytes,
                           long reservedCodeCacheBytes, boolean exitOnOutOfMemoryError, String oomHookSha256) {
        public Evidence {
            if (pid <= 0 || !valid(startedAt) || !valid(receivedAt) || startedAt.isAfter(receivedAt)
                    || initialHeapBytes <= 0 || maximumHeapBytes < initialHeapBytes || maximumMetaspaceBytes <= 0
                    || maximumDirectBytes <= 0 || reservedCodeCacheBytes <= 0
                    || oomHookSha256 != null && !oomHookSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid Java VM memory facts");
            }
        }

        private static boolean valid(Instant value) {
            return value != null && value.getEpochSecond() >= 0 && value.getNano() % 1000 == 0;
        }

        @Override public String toString() { return "JavaVmMemoryEvidence[redacted]"; }
    }

    @FunctionalInterface public interface Api { Evidence read(); }
}
