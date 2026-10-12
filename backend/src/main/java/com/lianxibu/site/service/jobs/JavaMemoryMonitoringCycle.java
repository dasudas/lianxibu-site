package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Five bounded acquisitions before a single durable operational sample; no scheduler or external delivery. */
public final class JavaMemoryMonitoringCycle {
    public enum Outcome { SAMPLED, ALREADY_SAMPLED, BUSY, STOPPED }
    private final JavaMemoryTelemetry.Api host;
    private final JavaProcessTelemetry.Api process;
    private final JavaCgroupTelemetry.Api cgroup;
    private final JavaVmMemoryTelemetry.Api vm;
    private final JavaVmOomTelemetry.Api oom;
    private final JavaMemoryEvidence validation;
    private final Key key;
    private final OperationalMonitoringService.Store store;
    private final OperationalMonitoringService monitor;
    private final Clock clock;
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile boolean stopped = true;

    public JavaMemoryMonitoringCycle(JavaMemoryTelemetry.Api host, JavaProcessTelemetry.Api process, JavaCgroupTelemetry.Api cgroup,
            JavaVmMemoryTelemetry.Api vm, JavaVmOomTelemetry.Api oom, JavaMemoryEvidence validation, Key key,
            OperationalMonitoringService.Store store, OperationalMonitoringService monitor, Clock clock) {
        if (host == null || process == null || cgroup == null || vm == null || oom == null || validation == null || key == null
                || !key.ruleId().equals("OPS-06") || store == null || monitor == null || clock == null)
            throw new IllegalArgumentException("exact Java memory operational bindings required");
        this.host=host; this.process=process; this.cgroup=cgroup; this.vm=vm; this.oom=oom; this.validation=validation;
        this.key=key; this.store=store; this.monitor=monitor; this.clock=clock;
    }
    public synchronized void start() {
        if (!stopped) return;
        if (active.get()) throw new IllegalStateException("Java memory sampling still stopping");
        stopped=false;
    }
    public synchronized void stop() { stopped=true; }
    private boolean stopping() { return stopped || Thread.currentThread().isInterrupted(); }
    public Outcome tick() {
        if (stopping()) return Outcome.STOPPED;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Java memory acquisition requires no transaction");
        if (!active.compareAndSet(false,true)) return Outcome.BUSY;
        try {
            Instant minute=clock.instant().truncatedTo(ChronoUnit.MINUTES);
            State prior=store.read(key);
            if (prior!=null && prior.lastSampleAt()!=null) {
                if (prior.lastSampleAt().isAfter(minute)) throw new IllegalStateException("operational sample clock rollback");
                if (prior.lastSampleAt().equals(minute)) return Outcome.ALREADY_SAMPLED;
            }
            var h=read(host::read); if (stopping()) return Outcome.STOPPED;
            var p=read(process::read); if (stopping()) return Outcome.STOPPED;
            var c=read(cgroup::read); if (stopping()) return Outcome.STOPPED;
            var v=read(vm::read); if (stopping()) return Outcome.STOPPED;
            var o=read(oom::read); if (stopping()) return Outcome.STOPPED;
            Instant completed=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var reading=completed.truncatedTo(ChronoUnit.MINUTES).equals(minute) ? validation.validate(h,p,c,v,o,completed) : null;
            synchronized (this) {
                if (stopping()) return Outcome.STOPPED;
                monitor.sample(key,reading!=null,reading,minute,action(minute));
            }
            return Outcome.SAMPLED;
        } finally { active.set(false); }
    }
    private static <T> T read(Supplier<T> source) {
        try { return source.get(); } catch (RuntimeException unavailable) { return null; }
    }
    private SharedWriteExecutor.ActionContext action(Instant minute) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            String resource=HexFormat.of().formatHex(digest.digest((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8))).substring(0,32);
            return new SharedWriteExecutor.ActionContext("java-memory-sample:"+resource+":"+minute.getEpochSecond(),"ops_"+resource,null);
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("sample identity unavailable"); }
    }
    @Override public String toString() { return "JavaMemoryMonitoringCycle[redacted]"; }
}
