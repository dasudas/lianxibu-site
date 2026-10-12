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
    @Test void continuousLowVolumeWritesExpireAcrossWriterRestarts() throws Exception {
        Instant start=clock.now;
        for(int day=0;day<=10;day++) {
            clock.now=start.plus(Duration.ofDays(day));
            new OperationalNoticeLog(directory,clock,1048576,2097152,Duration.ofDays(7)).append(event());
        }
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.toString().endsWith(".jsonl")).toList())
                for(String line:Files.readAllLines(file))
                    assertTrue(Instant.parse(CanonicalJson.parse(line).get("event_time").asString()).isAfter(clock.now.minus(Duration.ofDays(7))));
        }
    }
    @Test void legacyCurrentUsesFirstRecordAndOrphanMarkerCannotAgeNewSegment() throws Exception {
        log().append(event());
        Path marker=directory.resolve("ops-notice-current.started");Files.delete(marker);
        clock.now=clock.now.plus(Duration.ofDays(8));
        Files.setLastModifiedTime(directory.resolve("ops-notice-current.jsonl"),java.nio.file.attribute.FileTime.from(clock.now));
        log().append(event());
        assertEquals(1,Files.readAllLines(directory.resolve("ops-notice-current.jsonl")).size());
        Files.delete(directory.resolve("ops-notice-current.jsonl"));
        clock.now=clock.now.plus(Duration.ofDays(8));log().append(event());
        assertEquals(clock.now,Instant.parse(Files.readString(marker)));
    }
    @Test void malformedStartMarkerFailsWithoutAppending() throws Exception {
        log().append(event());Path current=directory.resolve("ops-notice-current.jsonl");String before=Files.readString(current);
        Files.writeString(directory.resolve("ops-notice-current.started"),"not-an-instant");
        assertThrows(java.io.IOException.class,()->log().append(event()));assertEquals(before,Files.readString(current));
    }
    @Test void legacyArchiveRotationDateCannotRenewOldRecords() throws Exception {
        byte[] old=OperationalNoticeLog.line(event());clock.now=clock.now.plus(Duration.ofDays(10));
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC).format(clock.now);
        Path legacy=directory.resolve("ops-notice-"+stamp+"-"+java.util.UUID.randomUUID()+".jsonl");Files.write(legacy,old);
        Path recent=directory.resolve("ops-notice-"+stamp+"-"+java.util.UUID.randomUUID()+".jsonl");Files.write(recent,OperationalNoticeLog.line(event()));
        Path versioned=directory.resolve("ops-notice-v2-"+stamp+"-"+java.util.UUID.randomUUID()+".jsonl");Files.write(versioned,old);
        log().append(event());assertFalse(Files.exists(legacy));assertTrue(Files.exists(recent));assertTrue(Files.exists(versioned));
    }
    long bytes() throws Exception {try(var files=Files.list(directory)){return files.filter(p->p.getFileName().toString().startsWith("ops-notice-")).mapToLong(p->{try{return Files.size(p);}catch(Exception error){throw new RuntimeException(error);}}).sum();}}
}
