package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.JavaCgroupTelemetry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current-process cgroup v2 local kernel facts. Does not establish JVM heap OOM health. */
public final class LinuxJavaCgroupProbe implements JavaCgroupTelemetry.Api {
    private static final int MAX_BYTES = 65536;
    private static final Pattern GROUP = Pattern.compile("/[a-zA-Z0-9_.:@-]+(?:/[a-zA-Z0-9_.:@-]+)*");
    private static final Pattern EVENT = Pattern.compile("([a-z_]+)[ \\t]+([0-9]+)[ \\t\\r]*");
    private final Clock clock;
    private final Path root, fixtureGroup, boot;
    private final Supplier<Identity> identity;
    private final FileSystemType filesystem;
    private final Function<BasicFileAttributes, String> directoryKey;
    private final boolean supported;

    record Identity(long pid, Instant startedAt) {
        @Override public String toString() { return "JavaCgroupIdentity[redacted]"; }
    }
    @FunctionalInterface interface FileSystemType { String type(Path path) throws IOException; }

    public LinuxJavaCgroupProbe(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        root = Path.of("/sys/fs/cgroup"); fixtureGroup = null;
        boot = Path.of("/proc/sys/kernel/random/boot_id");
        supported = "Linux".equalsIgnoreCase(System.getProperty("os.name"));
        identity = () -> {
            var process = ProcessHandle.current();
            return process.info().startInstant().map(start -> new Identity(process.pid(), start)).orElse(null);
        };
        filesystem = path -> Files.getFileStore(path).type();
        directoryKey = attributes -> attributes.fileKey() == null ? null : attributes.fileKey().toString();
    }

    /** Owned absolute file and native identity seams are package-only and cannot be browser inputs. */
    LinuxJavaCgroupProbe(Clock clock, Path root, Path group, Path boot,
                         Supplier<Identity> identity, FileSystemType filesystem,
                         Function<BasicFileAttributes, String> directoryKey) {
        this.clock = Objects.requireNonNull(clock);
        this.root = absolute(root); fixtureGroup = absolute(group); this.boot = absolute(boot);
        this.identity = Objects.requireNonNull(identity); this.filesystem = Objects.requireNonNull(filesystem);
        this.directoryKey = Objects.requireNonNull(directoryKey);
        supported = true;
    }

    @Override public JavaCgroupTelemetry.Evidence read() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("cgroup memory acquisition requires no transaction");
        }
        if (!supported) return null;
        try {
            Identity own = identity.get();
            if (own == null || own.pid() <= 0 || own.startedAt() == null || own.startedAt().getEpochSecond() < 0) return null;
            Path proc = fixtureGroup != null ? fixtureGroup : Path.of("/proc", Long.toString(own.pid()), "cgroup");
            String relative = group(readNative(proc));
            if (relative == null) return null;
            Path directory = root.resolve(relative.substring(1)).normalize();
            if (directory.equals(root) || !directory.startsWith(root)) return null;
            Object before = directoryIdentity(directory);
            if (before == null || !"cgroup2".equals(filesystem.type(directory))) return null;
            String bootId = readNative(boot).strip();
            if (!bootId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
            if (!member(readNative(directory.resolve("cgroup.procs")), own.pid())) return null;
            long current = decimal(readNative(directory.resolve("memory.current")).strip());
            String max = readNative(directory.resolve("memory.max")).strip();
            Long maximum = max.equals("max") ? null : decimal(max);
            Map<String, Long> events = events(readNative(directory.resolve("memory.events.local")));
            if (!events.containsKey("oom") || !events.containsKey("oom_kill")) return null;
            if (!relative.equals(group(readNative(proc))) || !before.equals(directoryIdentity(directory))
                    || !member(readNative(directory.resolve("cgroup.procs")), own.pid())) return null;
            String epoch = hash(bootId, relative, before.toString());
            return new JavaCgroupTelemetry.Evidence(own.pid(), own.startedAt().truncatedTo(ChronoUnit.MICROS),
                    clock.instant().truncatedTo(ChronoUnit.MICROS), epoch, current, maximum, events.get("oom"), events.get("oom_kill"));
        } catch (IOException | RuntimeException unavailable) { return null; }
    }

    private static Path absolute(Path path) {
        Path value = Objects.requireNonNull(path).normalize();
        if (!value.isAbsolute()) throw new IllegalArgumentException("absolute owned cgroup fixture required");
        return value;
    }

    private static void noLinks(Path path) throws IOException {
        for (Path entry = path; entry != null; entry = entry.getParent()) {
            if (Files.isSymbolicLink(entry)) throw new IOException("cgroup source unavailable");
        }
    }

    private Object directoryIdentity(Path path) throws IOException {
        noLinks(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() ? directoryKey.apply(attributes) : null;
    }

    private static String readNative(Path path) throws IOException {
        noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) throw new IOException("cgroup source unavailable");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("cgroup source unavailable");
        for (byte value : bytes) {
            if (value < 0 || value == 127 || value < 32 && value != '\t' && value != '\n' && value != '\r') throw new IOException("cgroup source unavailable");
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String group(String raw) {
        String found = null;
        for (String line : raw.split("\n", -1)) {
            if (!line.startsWith("0::")) continue;
            if (found != null) return null;
            found = line.substring(3);
            if (!GROUP.matcher(found).matches()) return null;
            for (String part : found.substring(1).split("/")) if (part.equals(".") || part.equals("..")) return null;
        }
        return found;
    }

    private static boolean member(String raw, long ownPid) {
        boolean found = false;
        for (String line : raw.split("\n", -1)) {
            if (line.isBlank()) continue;
            long pid = decimal(line.strip());
            if (pid <= 0) return false;
            if (pid == ownPid) found = true;
        }
        return found;
    }

    private static long decimal(String value) {
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("invalid native memory number");
        return Long.parseLong(value);
    }

    private static Map<String, Long> events(String raw) {
        var found = new HashMap<String, Long>();
        for (String line : raw.split("\n", -1)) {
            if (line.isBlank()) continue;
            var event = EVENT.matcher(line);
            if (!event.matches() || found.putIfAbsent(event.group(1), decimal(event.group(2))) != null || found.size() > 128)
                throw new IllegalArgumentException("invalid native memory events");
        }
        return found;
    }

    private static String hash(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public String toString() { return "LinuxJavaCgroupProbe[redacted]"; }
}
