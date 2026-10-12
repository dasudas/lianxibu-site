package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.sources.JdbcSourceDayRunRegistrations;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A durable original reservation is separate from the later full-cohort claim permission. */
public final class SourceDayRunRegistrationService implements SourceDayRunRegistration.Api {
    private record Outcome(SourceDayRunRegistration.Reservation reservation){ }
    private final JdbcSourceDayRunRegistrations storage;private final SharedWriteExecutor writes;
    private final TransactionTemplate reads;private final Clock clock;private final Predicate<String> allowed;
    public SourceDayRunRegistrationService(JdbcSourceDayRunRegistrations storage,SharedWriteExecutor writes,
                                           PlatformTransactionManager manager,Clock clock,Predicate<String> allowed){
        this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public SourceDayRunRegistration.Reservation lookup(SourceDayRunRegistration.Request request){outside();Objects.requireNonNull(request);return reads.execute(s->storage.lookup(request));}
    @Override public SourceDayRunRegistration.Reservation reserve(SourceDayRunRegistration.Request request){
        outside();Objects.requireNonNull(request);
        var context=new SharedWriteExecutor.ActionContext(JdbcSourceDayRunRegistrations.action(request),request.runId(),request.hash());
        Outcome outcome=writes.execute(context,key->{var original=storage.lookup(request);return original==null?null:new Outcome(original);},action->{
            if(!allowed.test(request.source().id()))return new Outcome(null);
            return new Outcome(storage.reserve(request,clock.instant().truncatedTo(ChronoUnit.MICROS)));
        });return outcome.reservation();
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("source day run registration owns its transactions");}
    @Override public String toString(){return "SourceDayRunRegistrationService[redacted]";}
}
