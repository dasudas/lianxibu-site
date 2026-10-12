package com.lianxibu.site.domain.jobs;
import java.time.Instant;

/** Actual host memory facts only; missing RSS or OOM evidence is a separate unknown. */
public final class JavaMemoryTelemetry {
    private JavaMemoryTelemetry(){ }
    public record Evidence(Instant receivedAt,long totalBytes,long availableBytes){
        public Evidence {
            if(receivedAt==null||receivedAt.getEpochSecond()<0||receivedAt.getNano()%1000!=0||totalBytes<=0||availableBytes<0||availableBytes>totalBytes)throw new IllegalArgumentException("invalid Java host memory facts");
        }
        @Override public String toString(){return "JavaMemoryEvidence[redacted]";}
    }
    /** Null represents unavailable actual telemetry, never a synthesized healthy sample. */
    @FunctionalInterface public interface Api {Evidence read();}
}
