package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.JavaProcessTelemetry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded actual RSS acquisition for this Java process, without diagnostic path disclosure. */
public final class LinuxJavaProcessProbe implements JavaProcessTelemetry.Api {
    private static final int MAX_BYTES = 65536;
    private static final Pattern PID = Pattern.compile("Pid:[ \\t]*([0-9]+)[ \\t\\r]*");
    private static final Pattern RSS = Pattern.compile("VmRSS:[ \\t]*([0-9]+)[ \\t]+kB[ \\t\\r]*");
    private final Clock clock;
    private final Path fixture;
    private final Supplier<Identity> identity;
    private final boolean supported;

    record Identity(long pid, Instant startedAt) {
        @Override public String toString() { return "JavaProcessIdentity[redacted]"; }
    }

    public LinuxJavaProcessProbe(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        fixture = null;
        supported = "Linux".equalsIgnoreCase(System.getProperty("os.name"));
        identity = () -> {
            var process = ProcessHandle.current();
            return process.info().startInstant().map(start -> new Identity(process.pid(), start)).orElse(null);
        };
    }

    /** Absolute owned fixture seam; never accepts a request-supplied process or path. */
    LinuxJavaProcessProbe(Clock clock, Path path, Supplier<Identity> identity) {
        this.clock = Objects.requireNonNull(clock);
        fixture = Objects.requireNonNull(path).normalize();
        if (!fixture.isAbsolute()) throw new IllegalArgumentException("absolute owned process fixture required");
        this.identity = Objects.requireNonNull(identity);
        supported = true;
    }

    @Override public JavaProcessTelemetry.Evidence read() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("process memory acquisition requires no transaction");
        }
        if (!supported) return null;
        try {
            Identity own = identity.get();
            if (own == null || own.pid() <= 0 || own.startedAt() == null || own.startedAt().getEpochSecond() < 0) return null;
            Path path = fixture != null ? fixture : Path.of("/proc", Long.toString(own.pid()), "status");
            for (Path entry = path; entry != null; entry = entry.getParent()) {
                if (Files.isSymbolicLink(entry)) return null;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) return null;
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return null;
            for (byte value : bytes) {
                if (value < 0 || value == 127 || value < 32 && value != '\t' && value != '\n' && value != '\r') return null;
            }
            Long pid = null, residentBytes = null;
            for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n", -1)) {
                String text = line.stripLeading();
                if (text.startsWith("Pid:")) {
                    var field = PID.matcher(text);
                    if (pid != null || !field.matches()) return null;
                    pid = Long.parseLong(field.group(1));
                } else if (text.startsWith("VmRSS:")) {
                    var field = RSS.matcher(text);
                    if (residentBytes != null || !field.matches()) return null;
                    residentBytes = Math.multiplyExact(Long.parseLong(field.group(1)), 1024L);
                }
            }
            if (pid == null || pid != own.pid() || residentBytes == null) return null;
            return new JavaProcessTelemetry.Evidence(own.pid(), own.startedAt().truncatedTo(ChronoUnit.MICROS),
                    clock.instant().truncatedTo(ChronoUnit.MICROS), residentBytes);
        } catch (IOException | RuntimeException unavailable) { return null; }
    }

    @Override public String toString() { return "LinuxJavaProcessProbe[redacted]"; }
}
