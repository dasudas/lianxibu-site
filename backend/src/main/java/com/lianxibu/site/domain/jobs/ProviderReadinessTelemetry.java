package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** Authenticated model-list and account-balance facts, independent of a paid generation result. */
public final class ProviderReadinessTelemetry {
    private ProviderReadinessTelemetry() { }
    public enum Status { READY, CREDENTIAL_REJECTED, BALANCE_UNAVAILABLE, MODEL_UNAVAILABLE }
    public record Evidence(Instant receivedAt, Status status) {
        public Evidence {
            if (receivedAt == null || receivedAt.getEpochSecond() < 0
                    || receivedAt.getNano() % 1000 != 0 || status == null)
                throw new IllegalArgumentException("invalid provider readiness facts");
        }
        @Override public String toString() { return "ProviderReadinessEvidence[redacted]"; }
    }
    /** Null means unknown; READY does not validate prompts/results or clear a previous failure guard. */
    @FunctionalInterface public interface Api { Evidence read(); }
}
