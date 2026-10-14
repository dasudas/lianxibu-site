package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcAttemptFailure;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcResultState;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Known failures only. Unknown outcomes must use the separate recovery workflow. */
public final class AttemptFailureService {
    public enum Failure { TRANSIENT, INVALID_RESULT, CONFIGURATION }
    public record Result(String state,String nextAttemptId,Instant eligibleAt) { }
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final SharedWriteExecutor writes;
    private final JdbcAttemptFailure store;
    private final Clock clock;
    private final String owner;
    public AttemptFailureService(SharedWriteExecutor writes,JdbcAttemptFailure store,Clock clock,String owner) {
        this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException("worker owner required");this.owner=owner;
    }
    public Result fail(WorkerAuthorizationService.Identity identity,Failure failure,Integer retryAfter,SharedWriteExecutor.ActionContext action) {
        Objects.requireNonNull(identity);Objects.requireNonNull(failure);Objects.requireNonNull(action);int delay=retryAfter==null?0:retryAfter;
        if(delay<0||delay>3600)throw new IllegalArgumentException("retry delay invalid");
        var id=new JdbcResultState.Identity(identity.rootId(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());
        var receipt=writes.execute(action,key->store.committed(key,id,owner,failure.name(),delay),ctx->{
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);var current=store.current(id,owner,now);
            String state,error;String next=null;Instant eligible=null;
            if(current.generation()!=current.latestGeneration()){state="superseded";error="superseded";}
            else if(failure==Failure.CONFIGURATION){state="blocked_configuration";error="provider_configuration";}
            else if(current.calls()>=3){state="failed";error="retry_exhausted";}
            else {state="waiting_retry";error=failure==Failure.INVALID_RESULT?"invalid_result":"provider_failure";
                next=JdbcFrozenInputs.stableId("attempt",id.rootId()+":"+(current.calls()+1));eligible=nextEligible(now,current.calls(),delay);}
            var result=new JdbcAttemptFailure.Receipt(state,next,eligible);
            return store.finish(ctx.actionKey(),id,owner,failure.name(),delay,current,result,error,now);
        });
        return new Result(receipt.state(),receipt.nextAttemptId(),receipt.eligibleAt());
    }
    static Instant nextEligible(Instant now,int calls,int retryAfter) {
        return nextEligible(now,calls,retryAfter,ReleaseAcceptanceWindow.current());
    }
    static Instant nextEligible(Instant now,int calls,int retryAfter,ReleaseAcceptanceWindow window) {
        if(now==null||calls<1||calls>2||retryAfter<0||retryAfter>3600)throw new IllegalArgumentException("retry schedule invalid");
        return Objects.requireNonNull(window).nextEligible(now.plusSeconds(Math.max(calls==1?30:60,retryAfter)));
    }
}
