package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.JavaVmOomTelemetry;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Extracts native JDK21 counters only; the private whole diagnostic is never logged or retained. */
public final class CurrentJavaVmOomProbe implements JavaVmOomTelemetry.Api {
    private static final int MAX_REPORT = 1048576;
    private static final String SUMMARY = "---------------  S U M M A R Y ------------";
    private static final String PROCESS = "---------------  P R O C E S S  ---------------";
    private static final String SYSTEM = "---------------  S Y S T E M  ---------------";
    private static final String COUNTERS = "OutOfMemory and StackOverflow Exception counts:";
    private static final Set<String> OOM_KEYS = Set.of("java_heap_errors", "metaspace_errors", "class_metaspace_errors");
    private static final Pattern ORPHAN = Pattern.compile("(?m)^OutOfMemoryError (?:java_heap_errors|metaspace_errors|class_metaspace_errors)=");
    private final Clock clock;
    private final Supplier<Identity> identity;
    private final ReportSource source;

    record Identity(long pid, Instant startedAt) {
        @Override public String toString() { return "JavaVmOomIdentity[redacted]"; }
    }
    @FunctionalInterface interface ReportSource { String read() throws Exception; }

    public CurrentJavaVmOomProbe(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        identity = () -> {
            var process = ProcessHandle.current();
            return process.info().startInstant().map(start -> new Identity(process.pid(), start)).orElse(null);
        };
        source = () -> {
            if (Runtime.version().feature() != 21) return null;
            var server = ManagementFactory.getPlatformMBeanServer();
            var name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            var metadata = server.getMBeanInfo(name);
            if (!"com.sun.management.internal.DiagnosticCommandImpl".equals(metadata.getClassName())) return null;
            var methods = Arrays.stream(metadata.getOperations()).filter(method -> method.getName().equals("vmInfo")).toList();
            if (methods.size() != 1) return null;
            var method = methods.getFirst();
            if (!method.getReturnType().equals("java.lang.String") || method.getSignature().length != 0
                    || !"VM.info".equals(method.getDescriptor().getFieldValue("dcmd.name"))) return null;
            return (String) server.invoke(name, "vmInfo", new Object[0], new String[0]);
        };
    }

    /** Trusted owned raw source seam only. Never accepts a report or process from a browser request. */
    CurrentJavaVmOomProbe(Clock clock, Supplier<Identity> identity, ReportSource source) {
        this.clock = Objects.requireNonNull(clock); this.identity = Objects.requireNonNull(identity); this.source = Objects.requireNonNull(source);
    }

    @Override public JavaVmOomTelemetry.Evidence read() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("native VM OOM acquisition requires no transaction");
        }
        try {
            Identity own = identity.get();
            if (own == null || own.pid() <= 0 || own.startedAt() == null || own.startedAt().getEpochSecond() < 0) return null;
            Map<String, Long> counts = counters(source.read());
            if (counts == null) return null;
            return new JavaVmOomTelemetry.Evidence(own.pid(), own.startedAt().truncatedTo(ChronoUnit.MICROS),
                    clock.instant().truncatedTo(ChronoUnit.MICROS), counts.getOrDefault("java_heap_errors", 0L),
                    counts.getOrDefault("metaspace_errors", 0L), counts.getOrDefault("class_metaspace_errors", 0L));
        } catch (Exception unavailable) { return null; }
    }

    private static int unique(String report, String marker) {
        int at = report.indexOf(marker);
        return at < 0 || report.indexOf(marker, at + marker.length()) >= 0 ? -1 : at;
    }

    private static Map<String, Long> counters(String report) {
        if (report == null || report.length() > MAX_REPORT || report.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT) return null;
        int summary = unique(report, SUMMARY), process = unique(report, PROCESS), system = unique(report, SYSTEM);
        if (summary < 0 || process <= summary || system <= process) return null;
        String region = report.substring(process + PROCESS.length(), system).replace("\r\n", "\n").stripLeading();
        int marker = report.indexOf(COUNTERS);
        if (marker < 0) return ORPHAN.matcher(region).find() ? null : Map.of();
        if (unique(report, COUNTERS) < 0 || !region.startsWith(COUNTERS + "\n")) return null;
        int end = region.indexOf("\n\n", COUNTERS.length());
        if (end <= COUNTERS.length() + 1) return null;
        String block = region.substring(COUNTERS.length() + 1, end);
        var result = new HashMap<String, Long>();
        for (String line : block.split("\n", -1)) {
            String field = line.startsWith("OutOfMemoryError ") ? line.substring("OutOfMemoryError ".length()) : line;
            String[] pair = field.split("=", -1);
            if (pair.length != 2 || !OOM_KEYS.contains(pair[0]) && !pair[0].equals("StackOverflowErrors") && !pair[0].equals("LinkageErrors")
                    || !pair[1].matches("[1-9][0-9]{0,9}")) return null;
            long value = Long.parseLong(pair[1]);
            if (value > Integer.MAX_VALUE || result.putIfAbsent(pair[0], value) != null) return null;
        }
        // HotSpot enters this section only for an OOM or stack-overflow count, not linkage alone.
        if (result.keySet().stream().noneMatch(key -> OOM_KEYS.contains(key) || key.equals("StackOverflowErrors"))) return null;
        return result;
    }

    @Override public String toString() { return "CurrentJavaVmOomProbe[redacted]"; }
}
