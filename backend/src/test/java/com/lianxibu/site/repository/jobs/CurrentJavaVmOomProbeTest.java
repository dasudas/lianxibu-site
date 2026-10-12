package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CurrentJavaVmOomProbeTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
    static final String SUMMARY = "---------------  S U M M A R Y ------------";
    static final String PROCESS = "---------------  P R O C E S S  ---------------";
    static final String SYSTEM = "---------------  S Y S T E M  ---------------";
    static final String COUNTERS = "OutOfMemory and StackOverflow Exception counts:";
    static final String PREFIX = "JRE version: fictional JDK21\n" + SUMMARY + "\n\nFICTIONAL_PRIVATE_PARAMETER\n" + PROCESS + "\n\n";
    static final String SUFFIX = "fixture heap\n\n" + SYSTEM + "\nfixture system\n";
    static final String ZERO = PREFIX + SUFFIX;
    static String report(String counts) { return PREFIX + COUNTERS + "\n" + counts + "\n\n" + SUFFIX; }
    CurrentJavaVmOomProbe probe(CurrentJavaVmOomProbe.ReportSource source) {
        return new CurrentJavaVmOomProbe(CLOCK, () -> new CurrentJavaVmOomProbe.Identity(1234, START), source);
    }

    @Test void CompleteNativeFramesPreserveActualZeroNonzeroCategoriesAndSourceChanges() {
        var source = new AtomicReference<>(ZERO); var provider = probe(source::get); var zero = provider.read(); assertNotNull(zero);
        assertEquals(0, zero.javaHeapEvents()); assertEquals(0, zero.metaspaceEvents()); assertEquals(0, zero.classMetaspaceEvents());
        source.set(report("OutOfMemoryError java_heap_errors=3\nOutOfMemoryError metaspace_errors=2\nOutOfMemoryError class_metaspace_errors=1"));
        var fact = provider.read(); assertEquals(3, fact.javaHeapEvents()); assertEquals(2, fact.metaspaceEvents()); assertEquals(1, fact.classMetaspaceEvents());
        assertEquals(1234, fact.pid()); assertEquals(START.truncatedTo(java.time.temporal.ChronoUnit.MICROS), fact.startedAt());
        assertEquals("CurrentJavaVmOomProbe[redacted]", provider.toString()); assertFalse(fact.toString().contains("FICTIONAL_PRIVATE_PARAMETER"));
        source.set(report("StackOverflowErrors=2\nLinkageErrors=1")); assertEquals(0, provider.read().javaHeapEvents());
        source.set(report("OutOfMemoryError java_heap_errors=2147483647")); assertEquals(Integer.MAX_VALUE, provider.read().javaHeapEvents());
    }

    @Test void MissingDuplicateMalformedOrTruncatedNativeRegionsCannotBeKnownZero() {
        for (String raw : new String[] {null, "", ZERO.replace(SUMMARY, "missing"), ZERO.replace(PROCESS, "missing"), ZERO.replace(SYSTEM, "missing"),
                ZERO + SUMMARY, ZERO + PROCESS, ZERO + SYSTEM, SYSTEM + PREFIX, PREFIX,
                report(""), report("LinkageErrors=1"), report("OtherErrors=1"), report("OutOfMemoryError java_heap_errors=0"),
                report("OutOfMemoryError java_heap_errors=-1"), report("OutOfMemoryError java_heap_errors=2147483648"),
                report("OutOfMemoryError java_heap_errors=9223372036854775808"), report("OutOfMemoryError java_heap_errors=1.5"),
                report("OutOfMemoryError java_heap_errors=1\nOutOfMemoryError java_heap_errors=2"),
                report("StackOverflowErrors=1\nStackOverflowErrors=2"), report("OutOfMemoryError unknown_errors=1"),
                report("OutOfMemoryError java_heap_errors=1").replace("\n\nfixture heap", "\nfixture heap"),
                PREFIX + "OutOfMemoryError java_heap_errors=1\n\n" + SUFFIX,
                PREFIX + "fixture heap\n" + COUNTERS + "\nOutOfMemoryError java_heap_errors=1\n\n" + SUFFIX,
                report("OutOfMemoryError java_heap_errors=1") + COUNTERS}) assertNull(probe(() -> raw).read());
    }

    @Test void WholeDiagnosticCapacityAndUnavailableSourceAreUnknownWithoutExposingRawBody() {
        assertNull(probe(() -> ZERO + "x".repeat(1048577)).read());
        assertNull(probe(() -> ZERO + "虚".repeat(400000)).read());
        assertNull(probe(() -> { throw new IllegalStateException("FICTIONAL_PRIVATE_PARAMETER"); }).read());
    }

    @Test void InvalidBirthOrClockIsUnknownAndCompletedSourcePrecedesClockCapture() {
        for (CurrentJavaVmOomProbe.Identity identity : new CurrentJavaVmOomProbe.Identity[] {null,
                new CurrentJavaVmOomProbe.Identity(0, START), new CurrentJavaVmOomProbe.Identity(1234, null),
                new CurrentJavaVmOomProbe.Identity(1234, Instant.EPOCH.minusSeconds(1)), new CurrentJavaVmOomProbe.Identity(1234, CLOCK.instant().plusSeconds(1))})
            assertNull(new CurrentJavaVmOomProbe(CLOCK, () -> identity, () -> ZERO).read());
        var source = new AtomicReference<>(report("OutOfMemoryError java_heap_errors=1")); var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> { source.set("missing"); return CLOCK.instant(); });
        var provider = new CurrentJavaVmOomProbe(clock, () -> new CurrentJavaVmOomProbe.Identity(1234, START), source::get);
        assertEquals(1, provider.read().javaHeapEvents()); assertNull(provider.read()); verify(clock, times(1)).instant();
        when(clock.instant()).thenThrow(new IllegalStateException("FICTIONAL_PRIVATE_PARAMETER"));
        assertNull(new CurrentJavaVmOomProbe(clock, () -> new CurrentJavaVmOomProbe.Identity(1234, START), () -> ZERO).read());
        assertNull(new CurrentJavaVmOomProbe(CLOCK, () -> { throw new IllegalStateException("fictional identity unavailable"); }, () -> ZERO).read());
    }

    @Test void ConstructionIsLazyAndBothTransactionRolesAreRejectedBeforeNativeWork() {
        var clock = mock(Clock.class);
        @SuppressWarnings("unchecked") Supplier<CurrentJavaVmOomProbe.Identity> identity = mock(Supplier.class);
        var source = mock(CurrentJavaVmOomProbe.ReportSource.class); var provider = new CurrentJavaVmOomProbe(clock, identity, source);
        assertNotNull(new CurrentJavaVmOomProbe(clock)); verifyNoInteractions(clock, identity, source);
        assertThrows(RuntimeException.class, () -> new CurrentJavaVmOomProbe(null));
        for (boolean readonly : new boolean[] {true, false}) {
            try {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(readonly);
                assertThrows(IllegalStateException.class, provider::read); verifyNoInteractions(clock, identity, source);
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void OwnedRealJdk21ChildReportsNativeZeroAndTwoActualHeapOomIncrements() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path output = directory.resolve("owned-native-oom.log");
        var builder = new ProcessBuilder(java, "-Xms16m", "-Xmx64m", "-XX:MaxMetaspaceSize=64m",
                "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-ExitOnOutOfMemoryError", "-XX:-CrashOnOutOfMemoryError", "-XX:OnOutOfMemoryError=",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), NativeProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "owned native probe timeout"); assertEquals(0, process.exitValue(), "owned native probe failed");
            assertTrue(Files.size(output) <= 8192 && Files.readString(output).contains("native_oom_probe_ok"), "native OOM evidence unavailable");
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }

    public static class NativeProbe {
        static volatile Object retained;
        static void actualOom() {
            try { retained = new byte[96 * 1024 * 1024]; throw new AssertionError("owned allocation unexpectedly succeeded"); }
            catch (OutOfMemoryError expected) { }
        }
        public static void main(String[] args) {
            var provider = new CurrentJavaVmOomProbe(Clock.systemUTC()); var before = provider.read();
            if (before == null || before.javaHeapEvents() != 0 || before.metaspaceEvents() != 0 || before.classMetaspaceEvents() != 0)
                throw new AssertionError("native initial counter unavailable");
            actualOom(); var first = provider.read(); actualOom(); var second = provider.read();
            if (first == null || second == null || first.javaHeapEvents() <= before.javaHeapEvents() || second.javaHeapEvents() <= first.javaHeapEvents()
                    || second.pid() != ProcessHandle.current().pid() || !before.startedAt().equals(second.startedAt()))
                throw new AssertionError("native OOM increments unavailable");
            System.out.println("native_oom_probe_ok");
        }
    }
}
