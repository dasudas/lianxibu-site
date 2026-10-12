package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.RdsTelemetry.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded acquisition before database transactions; durable minute replay and watermarks. */
public final class RdsMonitoringCycle {
    public enum Outcome { SAMPLED, ALREADY_SAMPLED, BUSY, STOPPED }
    private final Api api;private final RdsCapacityEvidence validation;private final Resource resource;
    private final Key capacity,telemetry;private final OperationalMonitoringService.Store store;
    private final OperationalMonitoringService monitor;private final Clock clock;
    private final AtomicBoolean active=new AtomicBoolean();private volatile boolean stopped;
    public RdsMonitoringCycle(Api api,RdsCapacityEvidence validation,Resource resource,Key capacity,Key telemetry,
                              OperationalMonitoringService.Store store,OperationalMonitoringService monitor,Clock clock) {
        if(api==null||validation==null||resource==null||capacity==null||telemetry==null||store==null||monitor==null||clock==null
                ||!"OPS-04".equals(capacity.ruleId())||!"OPS-10".equals(telemetry.ruleId())
                ||!capacity.component().equals(telemetry.component())||!resource.instanceId().equals(capacity.resourceKey())
                ||!resource.instanceId().equals(telemetry.resourceKey()))throw new IllegalArgumentException("exact RDS operational bindings required");
        this.api=api;this.validation=validation;this.resource=resource;this.capacity=capacity;this.telemetry=telemetry;
        this.store=store;this.monitor=monitor;this.clock=clock;
    }
    public void start(){stopped=false;}
    public void stop(){stopped=true;}
    public Outcome tick() {
        if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
        if(!active.compareAndSet(false,true))return Outcome.BUSY;
        try {
            Instant now=clock.instant().truncatedTo(ChronoUnit.MINUTES);
            State before=store.read(capacity),health=store.read(telemetry);
            boolean firstDone=sampled(before,now),secondDone=sampled(health,now);
            if(firstDone&&secondDone)return Outcome.ALREADY_SAMPLED;
            if(!firstDone) {
                Evidence evidence;
                try{evidence=api.read(resource,clock.instant());}catch(RuntimeException unavailable){evidence=null;}
                if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
                Sample sample=validation.validate(resource,evidence,clock.instant());
                before=monitor.sample(capacity,sample.available(),sample.available()?new Capacity(sample.freePercent(),sample.measuredAt()):null,now,action(capacity,now));
            }
            if(stopped||Thread.currentThread().isInterrupted())return Outcome.STOPPED;
            if(!secondDone) {
                Long last=before==null?null:before.counters().get("capacity_verified_at");
                long age=last==null||last>now.getEpochSecond()?601:Math.max(0,now.getEpochSecond()-last);
                monitor.sample(telemetry,true,new Telemetry(age,age),now,action(telemetry,now));
            }
            return Outcome.SAMPLED;
        } finally {active.set(false);}
    }
    private static boolean sampled(State state,Instant now) {
        if(state==null||state.lastSampleAt()==null)return false;
        if(state.lastSampleAt().isAfter(now))throw new IllegalStateException("operational sample clock rollback");
        return state.lastSampleAt().equals(now);
    }
    private static SharedWriteExecutor.ActionContext action(Key key,Instant now) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update((key.component()+"\n"+key.resourceKey()).getBytes(StandardCharsets.UTF_8));
            String id=HexFormat.of().formatHex(digest.digest()).substring(0,32);
            return new SharedWriteExecutor.ActionContext("rds-sample:"+id+":"+key.ruleId()+":"+now.getEpochSecond(),"ops_"+id,null);
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("sample identity unavailable");}
    }
}
