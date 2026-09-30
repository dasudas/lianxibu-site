package com.lianxibu.site.domain.sources;

import java.time.Instant;

/** Durable identity of one scheduled or node lease action. */
public record RunActionReceipt(String actionKey, String operation, String requestScope,
                               String nodeId, String runId, byte[] claimHash,
                               long resultRevision, Instant originalLeaseUntil) {
    @Override public String toString() {
        return "RunReceipt[operation=" + operation + ",runId=" + runId + "]";
    }

    public interface Store {
        RunActionReceipt find(String actionKey);
        void save(RunActionReceipt value);
    }
}
