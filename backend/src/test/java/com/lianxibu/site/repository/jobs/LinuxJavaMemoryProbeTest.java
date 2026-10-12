package com.lianxibu.site.repository.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LinuxJavaMemoryProbeTest {
    @TempDir Path directory;
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-10T10:00:00.123456789Z"),ZoneOffset.UTC);
    static final String DATA="MemTotal:       1638400 kB\nMemFree: 1 kB\nMemAvailable: 262144 kB\nSwapTotal: 1048576 kB\n";
    LinuxJavaMemoryProbe probe(String text)throws Exception{var path=directory.resolve("meminfo");Files.writeString(path,text);return new LinuxJavaMemoryProbe(CLOCK,path);}
    @Test void ActualOwnedFileRetainsAvailableMemoryRatherThanMemFreeAndCapturesAfterReading()throws Exception{
        var clock=mock(Clock.class);var path=directory.resolve("meminfo");Files.writeString(path,DATA);var provider=new LinuxJavaMemoryProbe(clock,path);verifyNoInteractions(clock);when(clock.instant()).thenAnswer(call->{assertEquals(DATA,Files.readString(path));return CLOCK.instant();});var fact=provider.read();assertNotNull(fact);assertEquals(1638400L*1024,fact.totalBytes());assertEquals(262144L*1024,fact.availableBytes());assertEquals(Instant.parse("2026-10-10T10:00:00.123456Z"),fact.receivedAt());verify(clock,times(1)).instant();assertEquals("LinuxJavaMemoryProbe[redacted]",provider.toString());
        doReturn(CLOCK.instant()).when(clock).instant();Files.writeString(path,DATA.replace("262144","0"));assertEquals(0,provider.read().availableBytes());Files.writeString(path,DATA.replace("262144","131072"));assertEquals(131072L*1024,provider.read().availableBytes());verify(clock,times(3)).instant();
    }
    @Test void MissingDuplicateWrongUnitsAndInvalidRangesAreUnknownWithoutFallback()throws Exception{
        for(String raw:new String[]{DATA.replace("MemAvailable:","Other:"),DATA.replace("MemTotal:","Other:"),DATA+"MemAvailable: 1 kB\n",DATA+"MemTotal: 1 kB\n",DATA.replace("262144 kB","262144 MB"),DATA.replace("262144","-1"),DATA.replace("262144","1.5"),DATA.replace("1638400","0"),DATA.replace("262144","1638401"),DATA.replace("1638400","9223372036854775807"),DATA.replace("262144","9223372036854775808"),DATA.replace("262144","+262144")})assertNull(probe(raw).read());
    }
    @Test void ActualByteLimitMalformedTextAndUnavailableFilesCannotBecomeHealthy()throws Exception{
        assertNotNull(probe(DATA+" ".repeat(65536-DATA.getBytes(StandardCharsets.US_ASCII).length)).read());assertNull(probe(DATA+" ".repeat(65537-DATA.getBytes(StandardCharsets.US_ASCII).length)).read());assertNull(probe(DATA+"\0").read());assertNull(probe(DATA+"非ASCII").read());
        var provider=probe(DATA);Files.delete(directory.resolve("meminfo"));assertNull(provider.read());Files.createDirectory(directory.resolve("meminfo"));assertNull(provider.read());
    }
    @Test void TransactionRolesAreRefusedBeforeFileOrClockAcquisition()throws Exception{
        var clock=mock(Clock.class);var provider=new LinuxJavaMemoryProbe(clock,directory.resolve("absent"));
        for(boolean readonly:new boolean[]{true,false})try{TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readonly);assertThrows(IllegalStateException.class,provider::read);verifyNoInteractions(clock);}finally{TransactionSynchronizationManager.clear();}
    }
    @Test void ConstructionIsLazyAndBadClockOrReadAcquisitionIsUnknown()throws Exception{
        var clock=mock(Clock.class);assertNotNull(new LinuxJavaMemoryProbe(clock));verifyNoInteractions(clock);assertThrows(RuntimeException.class,()->new LinuxJavaMemoryProbe(null));assertThrows(RuntimeException.class,()->new LinuxJavaMemoryProbe(clock,Path.of("relative")));
        var path=directory.resolve("meminfo");Files.writeString(path,DATA);when(clock.instant()).thenThrow(new IllegalStateException("fictional clock unavailable"));assertNull(new LinuxJavaMemoryProbe(clock,path).read());
        if(!System.getProperty("os.name").equalsIgnoreCase("Linux")){assertNull(new LinuxJavaMemoryProbe(clock).read());verify(clock,times(1)).instant();}
    }
}
