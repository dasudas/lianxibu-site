package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OperationalNoticeLogTest {
    @TempDir Path directory;
    final OperationalPersistenceFixture.MutableClock clock=new OperationalPersistenceFixture.MutableClock();
    OperationalNoticeDelivery.Event event(){return new OperationalNoticeDelivery.Event("fictional-event","OPS-04","database","fictional\nresource","L1","triggered",clock.instant(),"4","5");}
    OperationalNoticeLog log() throws Exception{return new OperationalNoticeLog(directory,clock,4096,8192,Duration.ofDays(7));}
    @Test void restartAndPartialAppendKeepCompleteJsonLinesAndStableEventId() throws Exception {
        log().append(event());Path current=directory.resolve("ops-notice-current.jsonl");Files.writeString(current,"{\"partial",StandardOpenOption.APPEND);
        log().append(event());var lines=Files.readAllLines(current);assertEquals(2,lines.size());
        for(String line:lines){var value=CanonicalJson.parse(line);assertEquals("fictional-event",value.get("event_id").asString());assertEquals("fictional\nresource",value.get("resource_key").asString());assertFalse(value.has("payload"));assertFalse(value.has("recipient"));}
    }
    @Test void byteAndAgeRotationOnlyRemoveOwnedLogs() throws Exception {
        Path unrelated=directory.resolve("business-audit.jsonl");Files.writeString(unrelated,"retained");
        for(int n=0;n<80;n++){clock.now=clock.now.plusSeconds(1);log().append(event());}
        assertTrue(bytes()<=8192);assertEquals("retained",Files.readString(unrelated));
        clock.now=clock.now.plus(Duration.ofDays(8));log().append(event());
        try(var files=Files.list(directory)){assertEquals(1,files.filter(p->p.getFileName().toString().endsWith(".jsonl")&&!p.equals(unrelated)).count());}
        assertEquals("retained",Files.readString(unrelated));
    }
    @Test void busyOrUnexpectedPathsFailForRetry() throws Exception {
        try(var guard=FileChannel.open(directory.resolve("ops-notice.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=guard.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,()->log().append(event()));
        }
        Files.createDirectory(directory.resolve("ops-notice-current.jsonl"));assertThrows(java.io.IOException.class,()->log().append(event()));
        assertThrows(IllegalArgumentException.class,()->new OperationalNoticeLog(directory,clock,4096,4096,Duration.ofDays(8)));
    }
    long bytes() throws Exception {try(var files=Files.list(directory)){return files.filter(p->p.getFileName().toString().startsWith("ops-notice-")).mapToLong(p->{try{return Files.size(p);}catch(Exception error){throw new RuntimeException(error);}}).sum();}}
}
