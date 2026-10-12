package com.lianxibu.site.domain.jobs;

import java.time.Instant;

/** A complete native slot scan and the actual recovery sweep result; neither reserves a slot. */
public final class WorkerRecoveryTelemetry {
    private WorkerRecoveryTelemetry() { }
    public record Snapshot(long overdueSeconds, int runningAttempts, boolean slotFree) {
        public Snapshot {
            if(overdueSeconds<0||overdueSeconds>9007199254740991L||runningAttempts<0||runningAttempts>200
                    ||runningAttempts==0&&overdueSeconds!=0||slotFree&&runningAttempts!=0)
                throw new IllegalArgumentException("invalid native recovery snapshot");
        }
        @Override public String toString(){return "WorkerRecoverySnapshot[redacted]";}
    }
    public record Evidence(Instant receivedAt,Snapshot snapshot,long consecutiveFailedScans,boolean scanSuccessful) {
        public Evidence {
            if(receivedAt==null||receivedAt.getEpochSecond()<0||receivedAt.getNano()%1000!=0||snapshot==null
                    ||consecutiveFailedScans<0||consecutiveFailedScans>9007199254740991L
                    ||scanSuccessful!=(consecutiveFailedScans==0))
                throw new IllegalArgumentException("invalid actual recovery scan facts");
        }
        @Override public String toString(){return "WorkerRecoveryEvidence[redacted]";}
    }
    /** Null means no complete, trustworthy actual scan. Constructors never manufacture an initial healthy scan. */
    @FunctionalInterface public interface Api { Evidence read(); }
}
