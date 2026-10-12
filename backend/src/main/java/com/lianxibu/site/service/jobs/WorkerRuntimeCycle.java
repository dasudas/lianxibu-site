package com.lianxibu.site.service.jobs;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One recovery/claim/run pass on the dedicated AI executor, never on the scheduler. */
public final class WorkerRuntimeCycle {
    public enum Outcome { IDLE, COMPLETED, RECOVERY_REQUIRED, BUSY }
    private final java.util.function.Supplier<WorkerRecoverySweep.Result> recovery;private final WorkerAuthorizationService worker;
    private final WorkerAttemptRunner runner;private final String epoch;private final AtomicBoolean active=new AtomicBoolean();
    public WorkerRuntimeCycle(WorkerRecoverySweep recovery,WorkerAuthorizationService worker,WorkerAttemptRunner runner,String epoch){
        this(Objects.requireNonNull(recovery)::scan,worker,runner,epoch);
    }
    public WorkerRuntimeCycle(java.util.function.Supplier<WorkerRecoverySweep.Result> recovery,WorkerAuthorizationService worker,WorkerAttemptRunner runner,String epoch){
        this.recovery=Objects.requireNonNull(recovery);this.worker=Objects.requireNonNull(worker);this.runner=Objects.requireNonNull(runner);
        if(epoch==null||!epoch.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException("worker epoch required");this.epoch=epoch;
    }
    public Outcome run(BooleanSupplier running){
        Objects.requireNonNull(running);if(!active.compareAndSet(false,true))return Outcome.BUSY;
        try{
            if(Objects.requireNonNull(recovery.get()).failed()>0)return Outcome.RECOVERY_REQUIRED;
            if(!running.getAsBoolean())return Outcome.IDLE;
            var claim=worker.claim(new SharedWriteExecutor.ActionContext("worker-cycle:"+epoch+":"+UUID.randomUUID(),"analysis-worker",null));
            if(claim.state()==WorkerAuthorizationService.State.IDLE)return Outcome.IDLE;
            if(claim.state()!=WorkerAuthorizationService.State.CLAIMED||claim.permit()==null)return Outcome.RECOVERY_REQUIRED;
            if(!running.getAsBoolean())claim.permit().cancelBeforeSend();
            var state=runner.run(claim.permit());
            return state==WorkerAttemptRunner.State.RECOVERY_REQUIRED||state==WorkerAttemptRunner.State.BUSY?Outcome.RECOVERY_REQUIRED:Outcome.COMPLETED;
        }catch(RuntimeException unavailable){return Outcome.RECOVERY_REQUIRED;}
        finally{active.set(false);}
    }
}
