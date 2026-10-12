package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.JavaVmMemoryTelemetry;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed local native VM options only; does not execute hooks or infer an OOM event count. */
public final class CurrentJavaVmMemoryProbe implements JavaVmMemoryTelemetry.Api {
    private static final Set<String> NAMES = Set.of("InitialHeapSize", "MaxHeapSize", "MaxMetaspaceSize",
            "MaxDirectMemorySize", "ReservedCodeCacheSize", "ExitOnOutOfMemoryError", "OnOutOfMemoryError");
    private final Clock clock;
    private final Supplier<Identity> identity;
    private final Supplier<Map<String, String>> flags;

    record Identity(long pid, Instant startedAt) {
        @Override public String toString() { return "JavaVmIdentity[redacted]"; }
    }

    public CurrentJavaVmMemoryProbe(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        identity = () -> {
            var process = ProcessHandle.current();
            return process.info().startInstant().map(start -> new Identity(process.pid(), start)).orElse(null);
        };
        flags = () -> {
            var bean = Objects.requireNonNull(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class));
            var actual = new HashMap<String, String>();
            for (String name : NAMES) {
                var option = bean.getVMOption(name);
                if (!name.equals(option.getName())) throw new IllegalStateException("native VM option unavailable");
                actual.put(name, Objects.requireNonNull(option.getValue()));
            }
            return Map.copyOf(actual);
        };
    }

    /** Trusted owned snapshot seams only, never accepts caller-supplied runtime options. */
    CurrentJavaVmMemoryProbe(Clock clock, Supplier<Identity> identity, Supplier<Map<String, String>> flags) {
        this.clock = Objects.requireNonNull(clock); this.identity = Objects.requireNonNull(identity);
        this.flags = Objects.requireNonNull(flags);
    }

    @Override public JavaVmMemoryTelemetry.Evidence read() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("native VM memory acquisition requires no transaction");
        }
        try {
            Identity own = identity.get();
            if (own == null || own.pid() <= 0 || own.startedAt() == null || own.startedAt().getEpochSecond() < 0) return null;
            Map<String, String> actual = Map.copyOf(flags.get());
            if (!actual.keySet().equals(NAMES)) return null;
            long initial = bytes(actual.get("InitialHeapSize")), heap = bytes(actual.get("MaxHeapSize"));
            long meta = bytes(actual.get("MaxMetaspaceSize")), direct = bytes(actual.get("MaxDirectMemorySize"));
            long code = bytes(actual.get("ReservedCodeCacheSize"));
            String exit = actual.get("ExitOnOutOfMemoryError");
            if (!exit.equals("true") && !exit.equals("false")) return null;
            String hook = actual.get("OnOutOfMemoryError");
            if (hook.length() > 8192) return null;
            byte[] encoded = hook.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > 8192) return null;
            String hash = hook.isEmpty() ? null : sha256(encoded);
            return new JavaVmMemoryTelemetry.Evidence(own.pid(), own.startedAt().truncatedTo(ChronoUnit.MICROS),
                    clock.instant().truncatedTo(ChronoUnit.MICROS), initial, heap, meta, direct, code, exit.equals("true"), hash);
        } catch (RuntimeException unavailable) { return null; }
    }

    private static long bytes(String value) {
        if (!value.matches("[0-9]{1,19}")) throw new IllegalArgumentException("native VM memory budget unavailable");
        long bytes = Long.parseLong(value);
        if (bytes <= 0) throw new IllegalArgumentException("explicit native VM memory budget required");
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public String toString() { return "CurrentJavaVmMemoryProbe[redacted]"; }
}
