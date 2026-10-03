package com.lianxibu.site.service.jobs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static java.nio.file.StandardOpenOption.*;

/** Dedicated, bounded JSON-lines log for a host collector. It contains no arbitrary exception/payload text. */
public final class OperationalNoticeLog implements OperationalNoticeDelivery.Sink {
    private final Path directory;private final Clock clock;private final long segmentBytes,totalBytes;private final Duration retention;
    private static final String CURRENT="ops-notice-current.jsonl";
    private static final String ARCHIVE="ops-notice-[0-9]{17}-[0-9a-f-]{36}\\.jsonl";
    public OperationalNoticeLog(Path directory,Clock clock,long segmentBytes,long totalBytes,Duration retention) throws IOException {
        if(directory==null||clock==null||segmentBytes<4096||totalBytes<segmentBytes||totalBytes>256L*1024*1024
                ||retention==null||retention.isZero()||retention.isNegative()||retention.compareTo(Duration.ofDays(7))>0)
            throw new IllegalArgumentException("bounded dedicated log configuration required");
        Path requested=directory.toAbsolutePath().normalize();Files.createDirectories(requested);
        if(Files.isSymbolicLink(requested))throw new IOException("log directory cannot be a symbolic link");
        this.directory=requested.toRealPath();this.clock=clock;this.segmentBytes=segmentBytes;this.totalBytes=totalBytes;this.retention=retention;
    }
    @Override public void append(OperationalNoticeDelivery.Event event) throws IOException {
        byte[] bytes=line(Objects.requireNonNull(event));if(bytes.length>segmentBytes)throw new IOException("event exceeds segment bound");
        Path lock=owned("ops-notice.lock");
        try(var guard=FileChannel.open(lock,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);var held=guard.tryLock()) {
            if(held==null)throw new IOException("log writer busy");
            Instant now=clock.instant();Path current=owned(CURRENT);
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                repairTail(current);
                if(Files.size(current)+bytes.length>segmentBytes||Files.getLastModifiedTime(current).toInstant().isBefore(now.minus(retention))) {
                    String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC).format(now);
                    Files.move(current,owned("ops-notice-"+stamp+"-"+UUID.randomUUID()+".jsonl"),StandardCopyOption.ATOMIC_MOVE);
                }
            }
            var archives=archives();long size=Files.exists(current)?Files.size(current):0;
            for(Path path:archives)size+=Files.size(path);
            for(Path path:archives) {
                if(Files.getLastModifiedTime(path).toInstant().isBefore(now.minus(retention))||size+bytes.length>totalBytes) {
                    long removed=Files.size(path);Files.delete(path);size-=removed;
                }
            }
            if(size+bytes.length>totalBytes)throw new IOException("log byte bound reached");
            try(var output=FileChannel.open(current,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS)) {
                long before=output.size();output.position(before);
                try{var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                catch(IOException failure){try{output.truncate(before);output.force(true);}catch(IOException repair){failure.addSuppressed(repair);}throw failure;}
            }
            Files.setLastModifiedTime(current,java.nio.file.attribute.FileTime.from(now));
        }
    }
    private Path owned(String name) throws IOException {
        Path path=directory.resolve(name);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected log path type");
        return path;
    }
    private List<Path> archives() throws IOException {
        var result=new ArrayList<Path>();try(var stream=Files.newDirectoryStream(directory)) {
            for(Path path:stream)if(path.getFileName().toString().matches(ARCHIVE))result.add(owned(path.getFileName().toString()));
        }
        result.sort(Comparator.comparing(path->path.getFileName().toString()));return result;
    }
    private static void repairTail(Path path) throws IOException {
        try(var file=FileChannel.open(path,READ,WRITE,LinkOption.NOFOLLOW_LINKS)) {
            long end=file.size();if(end==0)return;var buffer=ByteBuffer.allocate(4096);
            while(end>0){long start=Math.max(0,end-buffer.capacity());buffer.clear();buffer.limit((int)(end-start));file.position(start);
                while(buffer.hasRemaining()){if(file.read(buffer)<0)throw new IOException("log changed during repair");}
                for(int i=buffer.position()-1;i>=0;i--)if(buffer.get(i)=='\n'){if(start+i+1!=file.size()){file.truncate(start+i+1);file.force(true);}return;}
                end=start;
            }
            file.truncate(0);file.force(true);
        }
    }
    static byte[] line(OperationalNoticeDelivery.Event event) {
        var out=JsonMapper.shared().createObjectNode();out.put("event_id",event.id());out.put("event_time",event.at().toString());
        out.put("level",event.level());out.put("rule_id",event.ruleId());out.put("component",event.component());out.put("resource_key",event.resourceKey());
        out.put("state",event.action().equals("resolved")?"resolved":"active");out.put("action",event.action());
        out.putNull("request_id");out.putNull("run_id");out.putNull("root_job_id");out.putNull("error_code");
        out.put("observed_value",event.observedValue());out.put("threshold",event.threshold());
        return (out.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
