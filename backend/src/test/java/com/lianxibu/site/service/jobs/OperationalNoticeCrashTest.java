package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationalNoticeCrashTest {
    @TempDir Path directory;
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    static OperationalNoticeDelivery.Event event(String id){return new OperationalNoticeDelivery.Event(id,"OPS-04","database","fictional","L1","triggered",NOW,"4","5");}
    static OperationalNoticeLog log(Path dir) throws Exception{return new OperationalNoticeLog(dir,Clock.fixed(NOW,ZoneOffset.UTC),4096,8192,Duration.ofDays(7));}
    @Test void processCrashReleasesLockAndRecoversPartialAppendAndRotation() throws Exception {
        for(String mode:new String[]{"partial","rotated"}) {
            Path dir=Files.createDirectory(directory.resolve(mode));
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Child.class.getName(),dir.toString(),mode).redirectErrorStream(true).redirectOutput(dir.resolve("child-output.txt").toFile()).start();
            try{assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(17,process.exitValue());}
            finally{if(process.isAlive())process.destroyForcibly().waitFor();}
            log(dir).append(event("after-restart"));
            int rows=0;try(var files=Files.list(dir)) {
                for(Path file:files.filter(p->p.toString().endsWith(".jsonl")).toList())
                    for(String line:Files.readAllLines(file)){assertNotNull(com.lianxibu.site.domain.protocol.CanonicalJson.parse(line).get("event_id"));rows++;}
            }
            assertEquals(2,rows);assertEquals(NOW,Instant.parse(Files.readString(dir.resolve("ops-notice-current.started"))));
        }
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            Path dir=Path.of(args[0]);log(dir).append(event("before-crash"));
            try(var guard=FileChannel.open(dir.resolve("ops-notice.lock"),StandardOpenOption.WRITE);var held=guard.lock()) {
                Path current=dir.resolve("ops-notice-current.jsonl");
                if(args[1].equals("partial")) {
                    try(var file=FileChannel.open(current,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){file.write(ByteBuffer.wrap("{\"interrupted".getBytes(java.nio.charset.StandardCharsets.UTF_8)));file.force(true);}
                } else {
                    Files.move(current,dir.resolve("ops-notice-v2-20260101000000000-"+java.util.UUID.randomUUID()+".jsonl"),StandardCopyOption.ATOMIC_MOVE);
                }
                Runtime.getRuntime().halt(17);
            }
        }
    }
}
