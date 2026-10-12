package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LinuxJavaProcessProbeTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T10:00:00.123456789Z");
    static final Clock CLOCK = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
    static final String DATA = "Name:\tjava\nPid:\t1234\nVmSize:\t999999 kB\nVmRSS:\t65536 kB\nThreads:\t8\n";

    LinuxJavaProcessProbe probe(String data) throws Exception {
        Path path = directory.resolve("status");
        Files.writeString(path, data);
        return new LinuxJavaProcessProbe(CLOCK, path, () -> new LinuxJavaProcessProbe.Identity(1234, START));
    }

    @Test void ReadsActualFileWithOriginalBirthAndClockAfterAcquisition() throws Exception {
        Path path = directory.resolve("status");
        Files.writeString(path, DATA);
        var clock = mock(Clock.class);
        @SuppressWarnings("unchecked") Supplier<LinuxJavaProcessProbe.Identity> identity = mock(Supplier.class);
        var provider = new LinuxJavaProcessProbe(clock, path, identity);
        verifyNoInteractions(clock, identity);
        when(identity.get()).thenReturn(new LinuxJavaProcessProbe.Identity(1234, START));
        when(clock.instant()).thenAnswer(call -> { assertEquals(DATA, Files.readString(path)); return CLOCK.instant(); });
        var fact = provider.read();
        assertNotNull(fact);
        assertEquals(1234, fact.pid());
        assertEquals(START.truncatedTo(java.time.temporal.ChronoUnit.MICROS), fact.startedAt());
        assertEquals(CLOCK.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), fact.receivedAt());
        assertEquals(65536L * 1024, fact.residentBytes());
        assertEquals("LinuxJavaProcessProbe[redacted]", provider.toString());
        doReturn(CLOCK.instant()).when(clock).instant();
        Files.writeString(path, DATA.replace("65536", "0"));
        assertEquals(0, provider.read().residentBytes());
        Files.writeString(path, DATA.replace("65536", "32768"));
        assertEquals(32768L * 1024, provider.read().residentBytes());
        verify(clock, times(3)).instant(); verify(identity, times(3)).get();
    }

    @Test void PidMustMatchOwnIdentityAndResidentFieldMustBeUniqueAndWellFormed() throws Exception {
        for (String raw : new String[] {
                DATA.replace("1234", "1235"), DATA.replace("Pid:", "Other:"), DATA + "Pid: 1234\n",
                DATA.replace("VmRSS:", "Other:"), DATA + "VmRSS: 1 kB\n", DATA.replace("65536 kB", "65536 MB"),
                DATA.replace("65536", "-1"), DATA.replace("65536", "1.5"), DATA.replace("65536", "+1"),
                DATA.replace("65536", "9223372036854775807"), DATA.replace("65536", "9223372036854775808"),
                DATA.replace("1234", "0"), DATA.replace("1234", "9223372036854775808") }) {
            assertNull(probe(raw).read());
        }
    }

    @Test void MissingInvalidOrFutureBirthIdentityIsUnknown() throws Exception {
        Path path = directory.resolve("status"); Files.writeString(path, DATA);
        for (LinuxJavaProcessProbe.Identity identity : new LinuxJavaProcessProbe.Identity[] {
                null, new LinuxJavaProcessProbe.Identity(0, START), new LinuxJavaProcessProbe.Identity(-1, START),
                new LinuxJavaProcessProbe.Identity(1234, null), new LinuxJavaProcessProbe.Identity(1234, Instant.EPOCH.minusSeconds(1)),
                new LinuxJavaProcessProbe.Identity(1234, CLOCK.instant().plusSeconds(1)) }) {
            assertNull(new LinuxJavaProcessProbe(CLOCK, path, () -> identity).read());
        }
    }

    @Test void ByteLimitInvalidTextAndMissingFilesFailClosed() throws Exception {
        assertNotNull(probe(DATA + " ".repeat(65536 - DATA.getBytes(StandardCharsets.US_ASCII).length)).read());
        assertNull(probe(DATA + " ".repeat(65537 - DATA.getBytes(StandardCharsets.US_ASCII).length)).read());
        assertNull(probe(DATA + "\0").read()); assertNull(probe(DATA + "非ASCII").read());
        var provider = probe(DATA); Files.delete(directory.resolve("status")); assertNull(provider.read());
        Files.createDirectory(directory.resolve("status")); assertNull(provider.read());
    }

    @Test void TransactionIsRejectedBeforeIdentityFileOrClockWork() {
        var clock = mock(Clock.class);
        @SuppressWarnings("unchecked") Supplier<LinuxJavaProcessProbe.Identity> identity = mock(Supplier.class);
        var provider = new LinuxJavaProcessProbe(clock, directory.resolve("absent"), identity);
        for (boolean readOnly : new boolean[] { true, false }) {
            try {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
                assertThrows(IllegalStateException.class, provider::read); verifyNoInteractions(clock, identity);
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void ProviderFailuresAndUnsupportedPlatformNeverBecomeZeroSamples() throws Exception {
        Path path = directory.resolve("status"); Files.writeString(path, DATA);
        var clock = mock(Clock.class);
        assertNotNull(new LinuxJavaProcessProbe(clock)); verifyNoInteractions(clock);
        assertThrows(RuntimeException.class, () -> new LinuxJavaProcessProbe(null));
        assertThrows(RuntimeException.class, () -> new LinuxJavaProcessProbe(clock, Path.of("relative"), () -> null));
        assertNull(new LinuxJavaProcessProbe(CLOCK, path, () -> { throw new IllegalStateException("fictional identity unavailable"); }).read());
        when(clock.instant()).thenThrow(new IllegalStateException("fictional clock unavailable"));
        assertNull(new LinuxJavaProcessProbe(clock, path, () -> new LinuxJavaProcessProbe.Identity(1234, START)).read());
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            assertNull(new LinuxJavaProcessProbe(clock).read()); verify(clock, times(1)).instant();
        }
    }
}
