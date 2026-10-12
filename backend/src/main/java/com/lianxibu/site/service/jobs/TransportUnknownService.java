package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcResultState;
import com.lianxibu.site.repository.jobs.JdbcTransportUnknown;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Call only after the owning sender has ended or cancelled its local network wait. */
public final class TransportUnknownService {
    public enum Cause { TRANSPORT_UNKNOWN, LOCAL_CANCELLED }
    public record Result(boolean slotReleased,int callCount) { }
    private final SharedWriteExecutor writes;
    private final JdbcTransportUnknown store;
    private final Clock clock;
    private final String owner;
    public TransportUnknownService(SharedWriteExecutor writes,JdbcTransportUnknown store,Clock clock,String owner) {
        this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException("worker process identity required");this.owner=owner;
    }
    public Result release(WorkerAuthorizationService.Identity identity,Cause cause,SharedWriteExecutor.ActionContext action) {
        Objects.requireNonNull(identity);Objects.requireNonNull(cause);Objects.requireNonNull(action);
        var proof=new JdbcResultState.Identity(identity.rootId(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());
        var receipt=writes.execute(action,key->store.committed(key,proof,owner,cause.name()),context->store.release(context.actionKey(),proof,owner,cause.name(),clock.instant().truncatedTo(ChronoUnit.MICROS)));
        return new Result(receipt.slotReleased(),receipt.callCount());
    }
}
