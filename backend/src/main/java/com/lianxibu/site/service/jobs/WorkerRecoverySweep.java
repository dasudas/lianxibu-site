package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcWorkerRecovery;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.Cause;
import java.time.Clock;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Bounded scan only; the recovery service rechecks every decision under the shared writer. */
public final class WorkerRecoverySweep {
    public record Result(int scanned,int recovered,int pending,int failed,boolean more) { }
    private final JdbcWorkerRecovery candidates;
    private final UnknownCallRecoveryService.Store store;
    private final UnknownCallRecoveryService recovery;
    private final Clock clock;
    public WorkerRecoverySweep(JdbcWorkerRecovery candidates,UnknownCallRecoveryService.Store store,UnknownCallRecoveryService recovery,Clock clock) {
        this.candidates=Objects.requireNonNull(candidates);this.store=Objects.requireNonNull(store);this.recovery=Objects.requireNonNull(recovery);this.clock=Objects.requireNonNull(clock);
    }
    public Result scan() {
        var ids=candidates.runningAttemptIds(201);int scanned=0,recovered=0,pending=0,failed=0;
        for(String id:ids.stream().limit(200).toList()) {
            scanned++;
            try {
                var value=store.attempt(id);
                if(value==null||!id.equals(value.attemptId()))throw new IllegalStateException("original recovery proof missing");
                if(!"running".equals(value.attemptState()))continue;
                var now=clock.instant();Cause cause;
                if(value.ownerEpoch()!=null&&store.stoppedOwner(value.ownerEpoch()))cause=Cause.STOPPED_PROCESS;
                else if(value.startedAt()!=null&&!now.isBefore(value.startedAt().plusSeconds(90)))cause=Cause.TIMEOUT;
                else if(value.leaseUntil()!=null&&!now.isBefore(value.leaseUntil()))cause=Cause.LEASE_EXPIRED;
                else {pending++;continue;}
                var target=JsonMapper.shared().createObjectNode();target.put("root_id",value.rootId());target.put("attempt_id",id);target.put("owner_epoch",value.ownerEpoch());
                target.put("dispatch_token",value.dispatchToken());target.put("slot_revision",value.slotRevision());target.put("fencing",value.fencing());target.put("cause",cause.name());
                String key="worker-recovery-sweep:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,target);
                var result=recovery.release(id,cause,new SharedWriteExecutor.ActionContext(key,id,null));
                if(result.state()==UnknownCallRecoveryService.State.UNKNOWN_EXTERNAL)recovered++;
                else if(result.state()!=UnknownCallRecoveryService.State.ALREADY_TERMINAL)failed++;
            } catch(RuntimeException unavailable) {failed++;}
        }
        return new Result(scanned,recovered,pending,failed,ids.size()>200);
    }
}
