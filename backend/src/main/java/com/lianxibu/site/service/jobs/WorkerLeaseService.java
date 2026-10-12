package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcResultState;
import com.lianxibu.site.repository.jobs.JdbcWorkerLease;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Renews only an already authorized physical attempt. Never claims or sends work. */
public final class WorkerLeaseService {
    private final SharedWriteExecutor writes;
    private final JdbcWorkerLease store;
    private final Clock clock;
    private final String owner;
    public WorkerLeaseService(SharedWriteExecutor writes,JdbcWorkerLease store,Clock clock,String owner) {
        this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException("worker process identity required");this.owner=owner;
    }
    public Instant renew(WorkerAuthorizationService.Identity identity,SharedWriteExecutor.ActionContext action) {
        Objects.requireNonNull(identity);Objects.requireNonNull(action);
        var proof=new JdbcResultState.Identity(identity.rootId(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());
        return writes.execute(action,key->store.committed(key,proof,owner),context->store.renew(context.actionKey(),proof,owner,clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
}
