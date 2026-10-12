package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure qualification of independently acquired facts against a controlled artifact budget. */
public final class JavaMemoryEvidence {
    private static final long MAX_SAFE = 9007199254740991L;
    private static final BigDecimal MIB = BigDecimal.valueOf(1048576);
    public record Budget(long initialHeapBytes, long maximumHeapBytes, long maximumMetaspaceBytes,
                         long maximumDirectBytes, long reservedCodeCacheBytes, long nativeAllowanceBytes,
                         long hardLimitBytes, boolean exitOnOutOfMemoryError, String oomHookSha256) {
        public Budget {
            for (long value : new long[] {initialHeapBytes, maximumHeapBytes, maximumMetaspaceBytes,
                    maximumDirectBytes, reservedCodeCacheBytes, nativeAllowanceBytes, hardLimitBytes})
                if (value <= 0 || value > MAX_SAFE) throw new IllegalArgumentException("positive controlled memory budget required");
            long planned = Math.addExact(Math.addExact(Math.addExact(Math.addExact(maximumHeapBytes,
                    maximumMetaspaceBytes), maximumDirectBytes), reservedCodeCacheBytes), nativeAllowanceBytes);
            if (initialHeapBytes > maximumHeapBytes || planned > hardLimitBytes
                    || oomHookSha256 != null && !oomHookSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid controlled memory budget");
        }
        @Override public String toString() { return "JavaMemoryBudget[redacted]"; }
    }
    private final Budget budget;
    public JavaMemoryEvidence(Budget budget) {
        if (budget == null) throw new IllegalArgumentException("controlled memory budget required");
        this.budget = budget;
    }

    public JavaMemoryVmEvents validate(JavaMemoryTelemetry.Evidence host, JavaProcessTelemetry.Evidence process,
            JavaCgroupTelemetry.Evidence cgroup, JavaVmMemoryTelemetry.Evidence vm, JavaVmOomTelemetry.Evidence oom, Instant now) {
        if (host == null || process == null || cgroup == null || vm == null || oom == null || !time(now)) return null;
        for (Instant received : new Instant[] {host.receivedAt(), process.receivedAt(), cgroup.receivedAt(), vm.receivedAt(), oom.receivedAt()})
            if (!fresh(received, now)) return null;
        if (process.pid() > MAX_SAFE || !time(process.startedAt())
                || process.pid() != cgroup.pid() || process.pid() != vm.pid() || process.pid() != oom.pid()
                || !process.startedAt().equals(cgroup.startedAt()) || !process.startedAt().equals(vm.startedAt()) || !process.startedAt().equals(oom.startedAt())
                || cgroup.oomEvents() > MAX_SAFE || cgroup.oomKills() > MAX_SAFE
                || cgroup.maximumBytes() == null || cgroup.maximumBytes() != budget.hardLimitBytes()
                || vm.initialHeapBytes() != budget.initialHeapBytes() || vm.maximumHeapBytes() != budget.maximumHeapBytes()
                || vm.maximumMetaspaceBytes() != budget.maximumMetaspaceBytes() || vm.maximumDirectBytes() != budget.maximumDirectBytes()
                || vm.reservedCodeCacheBytes() != budget.reservedCodeCacheBytes() || vm.exitOnOutOfMemoryError() != budget.exitOnOutOfMemoryError()
                || !Objects.equals(vm.oomHookSha256(), budget.oomHookSha256())) return null;
        // No additional application-reported event is supplied here. Native and kernel events retain their own counters.
        var memory = new JavaMemory(mib(host.availableBytes()), mib(process.residentBytes()), mib(budget.hardLimitBytes()), false);
        return new JavaMemoryVmEvents(new JavaMemoryEvents(memory, cgroup.resourceEpoch(), cgroup.oomEvents(), cgroup.oomKills(), cgroup.receivedAt()), oom);
    }
    private static BigDecimal mib(long bytes) { return BigDecimal.valueOf(bytes).divide(MIB, 8, RoundingMode.DOWN); }
    private static boolean fresh(Instant received, Instant now) {
        return time(received) && !received.isAfter(now) && !received.isBefore(now.minusSeconds(20))
                && received.truncatedTo(ChronoUnit.MINUTES).equals(now.truncatedTo(ChronoUnit.MINUTES));
    }
    private static boolean time(Instant value) {
        if (value == null || value.getEpochSecond() < 0 || value.getNano() % 1000 != 0) return false;
        try { return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1000000L), value.getNano() / 1000L) <= MAX_SAFE; }
        catch (ArithmeticException overflow) { return false; }
    }
    @Override public String toString() { return "JavaMemoryEvidence[redacted]"; }
}
