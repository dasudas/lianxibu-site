package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LinuxJavaCgroupProbeTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
    static final String GROUP = "/system.slice/fictional.service";
    static final String EVENTS = "low 0\nhigh 1\nmax 2\noom 3\noom_kill 9\n";
    Path root, group, proc, boot;

    void setup() throws Exception {
        root = directory.resolve("cgroup"); group = root.resolve(GROUP.substring(1)); Files.createDirectories(group);
        proc = directory.resolve("cgroup-membership"); boot = directory.resolve("boot-id");
        Files.writeString(proc, "0::" + GROUP + "\n");
        Files.writeString(boot, "00000000-0000-4000-8000-000000000001\n");
        Files.writeString(group.resolve("cgroup.procs"), "1234\n1235\n");
        Files.writeString(group.resolve("memory.current"), "536870913\n");
        Files.writeString(group.resolve("memory.max"), "536870912\n");
        Files.writeString(group.resolve("memory.events.local"), EVENTS);
    }
    LinuxJavaCgroupProbe probe() { return probe(CLOCK, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> "cgroup2"); }
    LinuxJavaCgroupProbe probe(Clock clock, Supplier<LinuxJavaCgroupProbe.Identity> identity, LinuxJavaCgroupProbe.FileSystemType type) {
        // Windows has no Java fileKey. Actual owned fixture birth is a test token only, never Linux qualification.
        return new LinuxJavaCgroupProbe(clock, root, proc, boot, identity, type,
                attributes -> attributes.fileKey() == null ? attributes.creationTime().toString() : attributes.fileKey().toString());
    }

    @Test void ActualLocalFilesPreserveNativeCapacityCountersBirthAndResourceEpoch() throws Exception {
        setup(); var provider = probe(); var sample = provider.read(); assertNotNull(sample);
        assertEquals(1234, sample.pid()); assertEquals(START.truncatedTo(java.time.temporal.ChronoUnit.MICROS), sample.startedAt());
        assertEquals(536870913, sample.currentBytes()); assertEquals(536870912L, sample.maximumBytes());
        assertEquals(3, sample.oomEvents()); assertEquals(9, sample.oomKills()); assertTrue(sample.resourceEpoch().matches("[0-9a-f]{64}"));
        assertEquals(sample.resourceEpoch(), provider.read().resourceEpoch()); assertEquals("LinuxJavaCgroupProbe[redacted]", provider.toString());
        Files.writeString(group.resolve("memory.events"), "oom 999\noom_kill 999\n");
        Files.writeString(group.resolve("memory.events.local"), EVENTS.replace("oom 3", "oom 0").replace("oom_kill 9", "oom_kill 0"));
        Files.writeString(group.resolve("memory.current"), "0\n"); Files.writeString(group.resolve("memory.max"), "max\n");
        var zero = provider.read(); assertNotNull(zero); assertEquals(0, zero.currentBytes()); assertNull(zero.maximumBytes());
        assertEquals(0, zero.oomEvents()); assertEquals(0, zero.oomKills()); assertEquals(sample.resourceEpoch(), zero.resourceEpoch());
        Files.writeString(boot, "00000000-0000-4000-8000-000000000002\n"); assertNotEquals(sample.resourceEpoch(), provider.read().resourceEpoch());
        String before = provider.read().resourceEpoch(); Files.move(group, group.resolveSibling("previous.service"));
        Files.createDirectory(group); for (String name : new String[] {"cgroup.procs", "memory.current", "memory.max", "memory.events.local"})
            Files.copy(group.resolveSibling("previous.service").resolve(name), group.resolve(name));
        assertNotEquals(before, provider.read().resourceEpoch());
    }

    @Test void NativeMembershipFilesystemTypeBootAndSafeNonRootGroupAreRequired() throws Exception {
        setup(); for (String raw : new String[] {"", "0::/\n", "0::/../fictional\n", "0::/system.slice/../fictional.service\n",
                "0:://system.slice/fictional.service\n", "0::" + GROUP + "/\n", "0::" + GROUP + "\n0::" + GROUP + "\n", "1:memory:" + GROUP + "\n"}) {
            Files.writeString(proc, raw); assertNull(probe().read());
        }
        Files.writeString(proc, "0::" + GROUP + "\n");
        assertNull(probe(CLOCK, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> "ext4").read());
        for (String raw : new String[] {"", "1235\n", "-1234\n", "1234 bad\n", "9223372036854775808\n"}) {
            Files.writeString(group.resolve("cgroup.procs"), raw); assertNull(probe().read());
        }
        Files.writeString(group.resolve("cgroup.procs"), "1234\n");
        for (String raw : new String[] {"", "not-a-boot-id", "00000000-0000-4000-8000-000000000001\nextra\n"}) {
            Files.writeString(boot, raw); assertNull(probe().read());
        }
    }

    @Test void MissingMalformedDuplicateAndOverflowFieldsNeverBecomeKnownZero() throws Exception {
        setup(); for (String raw : new String[] {"", "oom 3\n", "oom_kill 9\n", EVENTS + "oom 3\n", EVENTS + "oom_kill 9\n",
                EVENTS.replace("oom 3", "oom -1"), EVENTS.replace("oom 3", "oom 1.5"), EVENTS.replace("oom 3", "oom 9223372036854775808"),
                EVENTS.replace("oom 3", "oom +3"), EVENTS + "\0", EVENTS + "非ASCII"}) {
            Files.writeString(group.resolve("memory.events.local"), raw); assertNull(probe().read());
        }
        Files.writeString(group.resolve("memory.events.local"), EVENTS);
        for (String name : new String[] {"memory.current", "memory.max"}) {
            String original = Files.readString(group.resolve(name));
            for (String raw : new String[] {"", "-1", "+1", "1.5", "9223372036854775808", "123 kB", "1\n2\n"}) {
                Files.writeString(group.resolve(name), raw); assertNull(probe().read());
            }
            Files.writeString(group.resolve(name), original);
        }
        Files.writeString(group.resolve("memory.max"), "0\n"); assertNull(probe().read());
    }

    @Test void ActualReadLimitAndUnavailableFileAreUnknown() throws Exception {
        setup(); Files.writeString(proc, "0::" + GROUP + "\n" + " ".repeat(65537)); assertNull(probe().read());
        Files.writeString(proc, "0::" + GROUP + "\n"); Files.writeString(group.resolve("memory.events.local"), "x".repeat(65537)); assertNull(probe().read());
        Files.writeString(group.resolve("memory.events.local"), EVENTS); Files.delete(group.resolve("memory.current")); assertNull(probe().read());
        Files.createDirectory(group.resolve("memory.current")); assertNull(probe().read());
    }

    @Test void MigrationOrDirectoryReplacementDuringAcquisitionIsRejected() throws Exception {
        setup(); assertNull(probe(CLOCK, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> {
            Files.writeString(proc, "0::/system.slice/another.service\n"); return "cgroup2";
        }).read());
        Files.writeString(proc, "0::" + GROUP + "\n");
        assertNull(probe(CLOCK, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> {
            Path old = group.resolveSibling("replaced.service"); Files.move(group, old); Files.createDirectory(group);
            for (String name : new String[] {"cgroup.procs", "memory.current", "memory.max", "memory.events.local"})
                Files.copy(old.resolve(name), group.resolve(name));
            return "cgroup2";
        }).read());
    }

    @Test void InvalidBirthUnavailableDependenciesAndClockAreUnknown() throws Exception {
        setup(); for (LinuxJavaCgroupProbe.Identity own : new LinuxJavaCgroupProbe.Identity[] {null,
                new LinuxJavaCgroupProbe.Identity(0, START), new LinuxJavaCgroupProbe.Identity(1234, null),
                new LinuxJavaCgroupProbe.Identity(1234, Instant.EPOCH.minusSeconds(1)), new LinuxJavaCgroupProbe.Identity(1234, CLOCK.instant().plusSeconds(1))})
            assertNull(probe(CLOCK, () -> own, path -> "cgroup2").read());
        assertNull(probe(CLOCK, () -> { throw new IllegalStateException("fictional identity unavailable"); }, path -> "cgroup2").read());
        assertNull(probe(CLOCK, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> { throw new java.io.IOException("fictional filesystem unavailable"); }).read());
        var clock = mock(Clock.class); when(clock.instant()).thenThrow(new IllegalStateException("fictional clock unavailable"));
        assertNull(probe(clock, () -> new LinuxJavaCgroupProbe.Identity(1234, START), path -> "cgroup2").read());
    }

    @Test void ConstructorsAreLazyAndTransactionsAreRejectedBeforeAnyAcquisition() throws Exception {
        setup(); var clock = mock(Clock.class);
        @SuppressWarnings("unchecked") Supplier<LinuxJavaCgroupProbe.Identity> identity = mock(Supplier.class);
        var type = mock(LinuxJavaCgroupProbe.FileSystemType.class); var provider = probe(clock, identity, type);
        assertNotNull(new LinuxJavaCgroupProbe(clock)); verifyNoInteractions(clock, identity, type);
        assertThrows(RuntimeException.class, () -> new LinuxJavaCgroupProbe(null));
        assertThrows(RuntimeException.class, () -> new LinuxJavaCgroupProbe(clock, Path.of("relative"), proc, boot, identity, type, attributes -> "fixture"));
        for (boolean readOnly : new boolean[] {true, false}) {
            try {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
                assertThrows(IllegalStateException.class, provider::read); verifyNoInteractions(clock, identity, type);
            } finally { TransactionSynchronizationManager.clear(); }
        }
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) { assertNull(new LinuxJavaCgroupProbe(clock).read()); verifyNoInteractions(clock); }
    }
}
