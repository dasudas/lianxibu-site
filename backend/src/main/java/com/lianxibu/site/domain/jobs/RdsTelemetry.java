package com.lianxibu.site.domain.jobs;

import java.math.BigDecimal;
import java.time.Instant;

/** Host-owned monitoring input; never a browser or collector supplied health assertion. */
public final class RdsTelemetry {
    private RdsTelemetry() { }
    public record Resource(String accountId, String regionId, String instanceId) {
        public Resource {
            if(accountId==null||!accountId.matches("[0-9]{16}")||!"cn-hangzhou".equals(regionId)
                    ||instanceId==null||!instanceId.matches("rm-[a-z0-9-]{1,60}"))
                throw new IllegalArgumentException("invalid RDS monitoring resource");
        }
        @Override public String toString() { return "RdsResource[redacted]"; }
    }
    public record Evidence(String attributesJson, String metadataJson, String metricsJson, Instant receivedAt) {
        @Override public String toString() { return "RdsEvidence[redacted]"; }
    }
    public record Sample(boolean available, BigDecimal freePercent, Instant measuredAt) {
        public Sample {
            if(available&&(freePercent==null||freePercent.signum()<0||freePercent.compareTo(BigDecimal.valueOf(100))>0||measuredAt==null)
                    ||!available&&(freePercent!=null||measuredAt!=null))
                throw new IllegalArgumentException("invalid capacity sample");
        }
        public static Sample unknown() { return new Sample(false,null,null); }
    }
    @FunctionalInterface public interface Api { Evidence read(Resource resource, Instant queryAt); }
}
