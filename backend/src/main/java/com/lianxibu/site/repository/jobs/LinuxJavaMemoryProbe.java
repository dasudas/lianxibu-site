package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.JavaMemoryTelemetry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual fixed Linux kernel memory facts, without host path or raw diagnostic exposure. */
public final class LinuxJavaMemoryProbe implements JavaMemoryTelemetry.Api {
    private static final int MAX_BYTES=65536;
    private static final Pattern FIELD=Pattern.compile("(MemTotal|MemAvailable):[ \\t]*([0-9]+)[ \\t]+kB[ \\t\\r]*");
    private final Clock clock;
    private final Path path;
    public LinuxJavaMemoryProbe(Clock clock){this.clock=Objects.requireNonNull(clock);path="Linux".equalsIgnoreCase(System.getProperty("os.name"))?Path.of("/proc/meminfo"):null;}
    /** Owned file seam only, never exposed through runtime request or configuration input. */
    LinuxJavaMemoryProbe(Clock clock,Path path){this.clock=Objects.requireNonNull(clock);this.path=Objects.requireNonNull(path).normalize();if(!this.path.isAbsolute())throw new IllegalArgumentException("absolute owned memory fixture required");}
    @Override public JavaMemoryTelemetry.Evidence read(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("host memory acquisition requires no transaction");
        if(path==null)return null;
        try{
            for(Path entry=path;entry!=null;entry=entry.getParent())if(Files.isSymbolicLink(entry))return null;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_BYTES)return null;
            byte[] bytes;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)return null;
            for(byte value:bytes)if(value<0||value==127||value<32&&value!='\t'&&value!='\n'&&value!='\r')return null;
            var fields=new HashMap<String,Long>();
            for(String line:new String(bytes,StandardCharsets.US_ASCII).split("\n",-1)){
                String text=line.stripLeading();if(!text.startsWith("MemTotal:")&&!text.startsWith("MemAvailable:"))continue;
                var field=FIELD.matcher(text);if(!field.matches())return null;
                long value=Math.multiplyExact(Long.parseLong(field.group(2)),1024L);if(fields.putIfAbsent(field.group(1),value)!=null)return null;
            }
            if(fields.size()!=2)return null;long total=fields.get("MemTotal"),available=fields.get("MemAvailable");if(total<=0||available<0||available>total)return null;
            return new JavaMemoryTelemetry.Evidence(clock.instant().truncatedTo(ChronoUnit.MICROS),total,available);
        }catch(IOException|RuntimeException unavailable){return null;}
    }
    @Override public String toString(){return "LinuxJavaMemoryProbe[redacted]";}
}
