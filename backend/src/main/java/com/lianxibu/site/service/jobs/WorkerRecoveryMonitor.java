package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes only a completed actual sweep joined to a complete native read-only snapshot. */
public final class WorkerRecoveryMonitor implements WorkerRecoveryTelemetry.Api {
    private final WorkerRecoverySweep sweep;private final Supplier<Snapshot> snapshot;private final Clock clock;
    private final AtomicBoolean active=new AtomicBoolean();private volatile Evidence latest;private long failedScans;
    public WorkerRecoveryMonitor(WorkerRecoverySweep sweep,Supplier<Snapshot> snapshot,Clock clock){this.sweep=Objects.requireNonNull(sweep);this.snapshot=Objects.requireNonNull(snapshot);this.clock=Objects.requireNonNull(clock);}
    @Override public Evidence read(){return latest;}
    public WorkerRecoverySweep.Result scan(){
        if(TransactionSynchronizationManager.isActualTransactionActive()||Thread.currentThread().isInterrupted())throw new IllegalStateException("recovery observation requires its outer pass");
        if(!active.compareAndSet(false,true))throw new IllegalStateException("recovery scan already active");
        latest=null;
        try {
            Instant began=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var result=Objects.requireNonNull(sweep.scan());
            if(result.failed()>0)failedScans=Math.min(9007199254740991L,failedScans+1);else if(!result.more())failedScans=0;
            if(result.more()||Thread.currentThread().isInterrupted())throw new IllegalStateException("complete recovery scan unavailable");
            var actual=Objects.requireNonNull(snapshot.get());Instant received=clock.instant().truncatedTo(ChronoUnit.MICROS);
            if(received.isBefore(began)||Thread.currentThread().isInterrupted())throw new IllegalStateException("recovery scan time unavailable");
            latest=new Evidence(received,actual,failedScans,result.failed()==0);return result;
        }finally{active.set(false);}
    }
}
