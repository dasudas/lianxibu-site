package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.DeepSeekChatClient;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;

/** Runs one already claimed permit; all network work occurs outside database transactions. */
public final class WorkerAttemptRunner {
    public enum State { BUSY, NOT_SENT, APPLIED, RETAINED, KNOWN_FAILURE, UNKNOWN, RECOVERY_REQUIRED }
    private final WorkerAuthorizationService worker;
    private final DeepSeekChatClient client;
    private final WorkerLeaseService leases;
    private final TransportUnknownService unknown;
    private final AttemptOutcomeService outcomes;
    private final ScheduledExecutorService monitor;
    private final Clock clock;
    private final Duration heartbeat;
    private final AtomicBoolean active=new AtomicBoolean();
    public WorkerAttemptRunner(WorkerAuthorizationService worker,DeepSeekChatClient client,WorkerLeaseService leases,
            TransportUnknownService unknown,AttemptOutcomeService outcomes,ScheduledExecutorService monitor,Clock clock) {
        this(worker,client,leases,unknown,outcomes,monitor,clock,Duration.ofSeconds(20));
    }
    WorkerAttemptRunner(WorkerAuthorizationService worker,DeepSeekChatClient client,WorkerLeaseService leases,
            TransportUnknownService unknown,AttemptOutcomeService outcomes,ScheduledExecutorService monitor,Clock clock,Duration heartbeat) {
        this.worker=Objects.requireNonNull(worker);this.client=Objects.requireNonNull(client);this.leases=Objects.requireNonNull(leases);
        this.unknown=Objects.requireNonNull(unknown);this.outcomes=Objects.requireNonNull(outcomes);this.monitor=Objects.requireNonNull(monitor);this.clock=Objects.requireNonNull(clock);
        if(heartbeat==null||heartbeat.isNegative()||heartbeat.isZero()||heartbeat.compareTo(Duration.ofSeconds(20))>0)throw new IllegalArgumentException("heartbeat period invalid");this.heartbeat=heartbeat;
    }
    public State run(WorkerAuthorizationService.Permit permit) {
        Objects.requireNonNull(permit);if(!active.compareAndSet(false,true))return State.BUSY;
        boolean interrupted=Thread.interrupted();var watch=new Watch(permit,Thread.currentThread());
        ScheduledFuture<?> renewal=null,deadline=null;
        try {
            if(interrupted||!clock.instant().isBefore(watch.deadline))permit.cancelBeforeSend();
            try {
                renewal=monitor.scheduleAtFixedRate(watch::renew,heartbeat.toNanos(),heartbeat.toNanos(),TimeUnit.NANOSECONDS);
                deadline=monitor.schedule(watch::cancel,Math.max(0,Duration.between(clock.instant(),watch.deadline).toNanos()),TimeUnit.NANOSECONDS);
            } catch(RuntimeException unavailable) {permit.cancelBeforeSend();}
            var result=worker.dispatch(permit,client::sendOnce);
            watch.stop();if(renewal!=null)renewal.cancel(false);if(deadline!=null)deadline.cancel(false);
            interrupted=Thread.interrupted()||interrupted;
            if(result.state()==WorkerAuthorizationService.State.NOT_SENT)return State.NOT_SENT;
            if(result.state()==WorkerAuthorizationService.State.LOST)return State.RECOVERY_REQUIRED;
            if(watch.cancelled||interrupted||!clock.instant().isBefore(watch.deadline)) {
                unknown.release(permit.identity(),TransportUnknownService.Cause.LOCAL_CANCELLED,action(permit.identity(),"cancelled"));return State.UNKNOWN;
            }
            if(result.state()==WorkerAuthorizationService.State.UNKNOWN) {
                unknown.release(permit.identity(),TransportUnknownService.Cause.TRANSPORT_UNKNOWN,action(permit.identity(),"uncertain"));return State.UNKNOWN;
            }
            if(result.state()!=WorkerAuthorizationService.State.SENT||result.outcome()==null)return State.RECOVERY_REQUIRED;
            return State.valueOf(outcomes.consume(permit.identity(),result.outcome()).state().name());
        } catch(RuntimeException unavailable) {
            // The permanent attempt remains authoritative. A caller must run recovery, never resend here.
            return State.RECOVERY_REQUIRED;
        } finally {
            watch.stop();if(renewal!=null)renewal.cancel(false);if(deadline!=null)deadline.cancel(false);
            interrupted=Thread.interrupted()||interrupted;active.set(false);if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private final class Watch {
        private final WorkerAuthorizationService.Permit permit;
        private final Thread sender;
        private final Instant deadline;
        private boolean running=true,cancelled;
        private int sequence;
        Watch(WorkerAuthorizationService.Permit permit,Thread sender){this.permit=permit;this.sender=sender;this.deadline=permit.identity().leaseUntil().minusSeconds(60);}
        synchronized void renew() {
            if(!running)return;
            if(!clock.instant().isBefore(deadline)){cancel();return;}
            try {leases.renew(permit.identity(),action(permit.identity(),"heartbeat-"+(++sequence)));}
            catch(RuntimeException failure){cancel();}
        }
        synchronized void cancel(){if(running){cancelled=true;permit.cancelBeforeSend();sender.interrupt();}}
        synchronized void stop(){running=false;}
    }
    private static SharedWriteExecutor.ActionContext action(WorkerAuthorizationService.Identity id,String step) {
        var value=JsonMapper.shared().createObjectNode();value.put("root_id",id.rootId());value.put("attempt_id",id.attemptId());value.put("dispatch_token",id.dispatchToken());value.put("slot_revision",id.slotRevision());value.put("fencing",id.fencing());value.put("step",step);
        return new SharedWriteExecutor.ActionContext("worker-runner:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,value),id.attemptId(),id.inputHash());
    }
}
