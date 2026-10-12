package com.lianxibu.site.domain.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/** Complete original seven-field heartbeat, with immutable canonical bytes and native hash. */
public final class CollectorHeartbeatTelemetry {
    private CollectorHeartbeatTelemetry() { }
    public record Sample(Instant observedAt, long pendingObservations, long pendingControls,
                         long quarantined, long outboxBytes, long freeBytes, Instant oldestPendingAt) {
        public Sample {
            time(observedAt);
            safe(pendingObservations); safe(pendingControls); safe(quarantined); safe(outboxBytes); safe(freeBytes);
            boolean pending = pendingObservations != 0 || pendingControls != 0;
            if (pending != (oldestPendingAt != null)) throw invalid();
            if (oldestPendingAt != null) { time(oldestPendingAt); if (oldestPendingAt.isAfter(observedAt)) throw invalid(); }
        }
        @Override public String toString() { return "CollectorHeartbeatSample[redacted]"; }
    }
    public record Parsed(Sample sample, String canonical, String payloadHash) {
        @Override public String toString() { return "CollectorHeartbeatParsed[redacted]"; }
    }
    public static Parsed parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 4096) throw invalid();
        var fields = Set.of("observed_at", "pending_observations", "pending_controls", "quarantined",
                "outbox_bytes", "free_bytes", "oldest_pending_at");
        var root = WireValues.object(CanonicalJson.parse(raw), fields, fields);
        var oldest = root.get("oldest_pending_at");
        var sample = new Sample(WireValues.utcMicroseconds(root.get("observed_at")),
                WireValues.nonnegativeInteger(root.get("pending_observations")),
                WireValues.nonnegativeInteger(root.get("pending_controls")),
                WireValues.nonnegativeInteger(root.get("quarantined")),
                WireValues.nonnegativeInteger(root.get("outbox_bytes")),
                WireValues.nonnegativeInteger(root.get("free_bytes")),
                oldest.isNull() ? null : WireValues.utcMicroseconds(oldest));
        return new Parsed(sample, new String(CanonicalJson.bytes(root), StandardCharsets.UTF_8),
                CanonicalJson.hash(CanonicalJson.Domain.ACTION, root));
    }
    private static void safe(long value) { if (value < 0 || value > CanonicalJson.MAX_SAFE_INTEGER) throw invalid(); }
    private static void time(Instant value) { if (value == null || value.getEpochSecond() < 0 || value.getNano() % 1000 != 0) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid collector heartbeat telemetry"); }
}
