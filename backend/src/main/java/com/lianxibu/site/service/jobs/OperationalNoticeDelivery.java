package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import java.time.*;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable intent -> controlled log handoff. This never asserts external recipient delivery. */
public final class OperationalNoticeDelivery {
    public record Event(String id,String ruleId,String component,String resourceKey,String level,
                        String action,Instant at,String observedValue,String threshold) {
        public Event {
            WireValues.id(id,false);
            if(ruleId==null||!ruleId.matches("OPS-(0[1-9]|10)")||component==null||resourceKey==null
                    ||component.isBlank()||component.length()>64||resourceKey.isBlank()||resourceKey.length()>512
                    ||!java.util.Set.of("L1","L2").contains(level)||action==null||!action.matches("[a-z_]{1,32}")||at==null)
                throw new IllegalArgumentException("invalid operational event");
            decimal(observedValue);decimal(threshold);
        }
        private static void decimal(String value){if(value!=null&&!value.matches("[0-9]{1,20}(\\.[0-9]{1,8})?"))throw new IllegalArgumentException("invalid measurement");}
    }
    public record Claim(Event event,String token,long attempt,Instant expiresAt) {
        public Claim {Objects.requireNonNull(event);WireValues.id(token,false);if(attempt<1||expiresAt==null)throw new IllegalArgumentException("invalid delivery claim");}
    }
    public interface Store {
        /** Both claim methods run under the shared writer. A permanent action receipt recovers unknown commits. */
        Claim claimed(String actionKey);
        Claim claim(String actionKey,String token,Instant now,Instant expiresAt);
        Boolean finished(String actionKey);
        /** CAS the same live token. Failure preserves the original immutable notice for retry. */
        boolean finish(String actionKey,Claim claim,Instant now,boolean handedOff,Instant retryAt,String errorCode);
    }
    @FunctionalInterface public interface Sink {
        /** Return only after the controlled log destination has durably accepted the event. */
        void append(Event event) throws Exception;
    }
    public enum Outcome { IDLE, LOG_HANDED_OFF, RETRY_PENDING, STALE_CLAIM }
    private final Store store;private final Sink sink;private final SharedWriteExecutor writes;
    private final Clock clock;private final Duration lease,retry;private final Supplier<String> tokens;
    public OperationalNoticeDelivery(Store store,Sink sink,SharedWriteExecutor writes,Clock clock,
                                     Duration lease,Duration retry,Supplier<String> tokens) {
        this.store=Objects.requireNonNull(store);this.sink=Objects.requireNonNull(sink);this.writes=Objects.requireNonNull(writes);
        this.clock=Objects.requireNonNull(clock);this.tokens=Objects.requireNonNull(tokens);
        if(lease==null||retry==null||lease.isNegative()||lease.isZero()||retry.isNegative()||retry.isZero()
                ||lease.compareTo(Duration.ofHours(1))>0||retry.compareTo(Duration.ofDays(1))>0)
            throw new IllegalArgumentException("bounded delivery durations required");
        this.lease=lease;this.retry=retry;
    }
    /** One bounded delivery, for a scheduler which owns pacing and shutdown. */
    public Outcome deliverNext() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("delivery must start outside a transaction");
        String token=WireValues.id(tokens.get(),false);String action="ops-claim:"+token;Instant now=clock.instant();
        var result=writes.execute(new SharedWriteExecutor.ActionContext(action,"ops-delivery",null),
                key->{var value=store.claimed(key);return value==null?null:java.util.Optional.of(value);},
                ignored->java.util.Optional.ofNullable(store.claim(action,token,now,now.plus(lease))));
        if(result.isEmpty())return Outcome.IDLE;
        var claim=result.orElseThrow();
        // Unknown claim commits may be recovered after the lease elapsed; never start a stale handoff.
        if(!clock.instant().isBefore(claim.expiresAt()))return Outcome.STALE_CLAIM;
        boolean handedOff;
        try{sink.append(claim.event());handedOff=true;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();handedOff=false;}
        catch(Exception failed){handedOff=false;}
        final boolean accepted=handedOff;Instant completed=clock.instant();String finish="ops-finish:"+token;
        boolean recorded=writes.execute(new SharedWriteExecutor.ActionContext(finish,"ops-delivery",null),store::finished,
                ignored->store.finish(finish,claim,completed,accepted,accepted?null:completed.plus(retry),accepted?null:"log_handoff_failed"));
        if(!recorded)return Outcome.STALE_CLAIM;
        return accepted?Outcome.LOG_HANDED_OFF:Outcome.RETRY_PENDING;
    }
}
