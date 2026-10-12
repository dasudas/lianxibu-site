package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CurrentJavaVmMemoryProbeTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
    static final String HOOK = "fictional-command-not-executed %p";
    static final Map<String, String> FLAGS = Map.of("InitialHeapSize", "33554432", "MaxHeapSize", "67108864",
            "MaxMetaspaceSize", "33554432", "MaxDirectMemorySize", "8388608", "ReservedCodeCacheSize", "16777216",
            "ExitOnOutOfMemoryError", "true", "OnOutOfMemoryError", HOOK);
    CurrentJavaVmMemoryProbe probe(Supplier<Map<String, String>> flags) {
        return new CurrentJavaVmMemoryProbe(CLOCK, () -> new CurrentJavaVmMemoryProbe.Identity(1234, START), flags);
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void ActualCapturedOptionsPreserveExactBudgetPolicyAndPrivateHookHash() throws Exception {
        var values = new HashMap<>(FLAGS); var provider = probe(() -> values); var fact = provider.read(); assertNotNull(fact);
        assertEquals(1234, fact.pid()); assertEquals(START.truncatedTo(java.time.temporal.ChronoUnit.MICROS), fact.startedAt());
        assertEquals(33554432, fact.initialHeapBytes()); assertEquals(67108864, fact.maximumHeapBytes());
        assertEquals(33554432, fact.maximumMetaspaceBytes()); assertEquals(8388608, fact.maximumDirectBytes());
        assertEquals(16777216, fact.reservedCodeCacheBytes()); assertTrue(fact.exitOnOutOfMemoryError()); assertEquals(hash(HOOK), fact.oomHookSha256());
        assertEquals("CurrentJavaVmMemoryProbe[redacted]", provider.toString());
        values.put("OnOutOfMemoryError", ""); values.put("ExitOnOutOfMemoryError", "false");
        assertNull(provider.read().oomHookSha256()); assertFalse(provider.read().exitOnOutOfMemoryError());
        values.put("OnOutOfMemoryError", "虚构命令 %p"); assertEquals(hash("虚构命令 %p"), provider.read().oomHookSha256());
    }

    @Test void MissingAutoUnlimitedAndMalformedNativeBudgetOrPolicyAreUnknown() {
        for (String name : FLAGS.keySet()) {
            var missing = new HashMap<>(FLAGS); missing.remove(name); assertNull(probe(() -> missing).read());
        }
        for (String name : List.of("InitialHeapSize", "MaxHeapSize", "MaxMetaspaceSize", "MaxDirectMemorySize", "ReservedCodeCacheSize")) {
            for (String raw : new String[] {"", "0", "-1", "+1", "1m", "1.5", "9223372036854775808"}) {
                var values = new HashMap<>(FLAGS); values.put(name, raw); assertNull(probe(() -> values).read());
            }
        }
        var badRange = new HashMap<>(FLAGS); badRange.put("InitialHeapSize", "67108865"); assertNull(probe(() -> badRange).read());
        var badBoolean = new HashMap<>(FLAGS); badBoolean.put("ExitOnOutOfMemoryError", "TRUE"); assertNull(probe(() -> badBoolean).read());
        var extra = new HashMap<>(FLAGS); extra.put("fictional_extra", "1"); assertNull(probe(() -> extra).read());
        var huge = new HashMap<>(FLAGS); huge.put("OnOutOfMemoryError", "x".repeat(8193)); assertNull(probe(() -> huge).read());
        huge.put("OnOutOfMemoryError", "虚".repeat(3000)); assertNull(probe(() -> huge).read());
        assertNull(probe(() -> null).read());
    }

    @Test void IdentitySnapshotAndClockFailuresAreUnknownAndClockIsCapturedAfterOptions() {
        var clock = mock(Clock.class); var values = new HashMap<>(FLAGS);
        var provider = new CurrentJavaVmMemoryProbe(clock, () -> new CurrentJavaVmMemoryProbe.Identity(1234, START), () -> values);
        when(clock.instant()).thenAnswer(call -> { values.put("MaxDirectMemorySize", "0"); return CLOCK.instant(); });
        assertEquals(8388608, provider.read().maximumDirectBytes()); assertNull(provider.read()); verify(clock, times(1)).instant();
        for (CurrentJavaVmMemoryProbe.Identity own : new CurrentJavaVmMemoryProbe.Identity[] {null,
                new CurrentJavaVmMemoryProbe.Identity(0, START), new CurrentJavaVmMemoryProbe.Identity(1234, null),
                new CurrentJavaVmMemoryProbe.Identity(1234, Instant.EPOCH.minusSeconds(1)), new CurrentJavaVmMemoryProbe.Identity(1234, CLOCK.instant().plusSeconds(1))})
            assertNull(new CurrentJavaVmMemoryProbe(CLOCK, () -> own, () -> FLAGS).read());
        assertNull(new CurrentJavaVmMemoryProbe(CLOCK, () -> { throw new IllegalStateException("fictional identity unavailable"); }, () -> FLAGS).read());
        assertNull(probe(() -> { throw new IllegalStateException("fictional VM unavailable"); }).read());
        when(clock.instant()).thenThrow(new IllegalStateException("fictional clock unavailable"));
        assertNull(new CurrentJavaVmMemoryProbe(clock, () -> new CurrentJavaVmMemoryProbe.Identity(1234, START), () -> FLAGS).read());
    }

    @Test void ConstructorsAreLazyAndAllTransactionsAreRejectedBeforeNativeAcquisition() {
        var clock = mock(Clock.class);
        @SuppressWarnings("unchecked") Supplier<CurrentJavaVmMemoryProbe.Identity> identity = mock(Supplier.class);
        @SuppressWarnings("unchecked") Supplier<Map<String, String>> flags = mock(Supplier.class);
        var provider = new CurrentJavaVmMemoryProbe(clock, identity, flags);
        assertNotNull(new CurrentJavaVmMemoryProbe(clock)); verifyNoInteractions(clock, identity, flags);
        assertThrows(RuntimeException.class, () -> new CurrentJavaVmMemoryProbe(null));
        for (boolean readonly : new boolean[] {true, false}) {
            try {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(readonly);
                assertThrows(IllegalStateException.class, provider::read); verifyNoInteractions(clock, identity, flags);
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void OwnedRealHotspotChildVmsReadConfiguredFlagsUnsetPolicyAndDefaultUnknown() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        for (String mode : new String[] {"configured", "unset", "defaults"}) {
            var args = new ArrayList<String>(); args.add(java);
            if (!mode.equals("defaults")) {
                args.addAll(List.of("-Xms32m", "-Xmx64m", "-XX:MaxMetaspaceSize=32m", "-XX:MaxDirectMemorySize=8m", "-XX:ReservedCodeCacheSize=16m"));
                if (mode.equals("configured")) args.addAll(List.of("-XX:+ExitOnOutOfMemoryError", "-XX:OnOutOfMemoryError=" + HOOK));
                else args.add("-XX:-ExitOnOutOfMemoryError");
            }
            args.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), NativeProbe.class.getName(), mode));
            Path output = directory.resolve("owned-vm-" + mode + ".log");
            var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
            var process = builder.start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "owned VM probe timeout");
                assertEquals(0, process.exitValue(), "owned VM native probe failed");
                assertTrue(Files.size(output) <= 8192 && Files.readString(output).contains("native_vm_probe_ok"), "native evidence unavailable");
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        }
    }

    public static class NativeProbe {
        public static void main(String[] args) throws Exception {
            var fact = new CurrentJavaVmMemoryProbe(Clock.systemUTC()).read();
            if (args[0].equals("defaults")) {
                if (fact != null) throw new AssertionError("default budgets must remain unknown");
            } else {
                if (fact == null || fact.pid() != ProcessHandle.current().pid()
                        || !fact.startedAt().equals(ProcessHandle.current().info().startInstant().orElseThrow().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                        || fact.initialHeapBytes() != 33554432 || fact.maximumHeapBytes() != 67108864
                        || fact.maximumMetaspaceBytes() != 33554432 || fact.maximumDirectBytes() != 8388608 || fact.reservedCodeCacheBytes() != 16777216)
                    throw new AssertionError("actual native VM budget mismatch");
                if (args[0].equals("configured")) {
                    if (!fact.exitOnOutOfMemoryError() || !hash(HOOK).equals(fact.oomHookSha256())) throw new AssertionError("actual native policy mismatch");
                } else if (fact.exitOnOutOfMemoryError() || fact.oomHookSha256() != null) throw new AssertionError("actual unset native policy mismatch");
            }
            System.out.println("native_vm_probe_ok");
        }
    }
}
