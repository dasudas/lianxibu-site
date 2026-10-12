package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Captures a complete source directory once; subsequent operations use the original committed day. */
public final class SourceDayRosterRegistrationService {
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final JdbcSourceDayRosters storage;private final SourceDayRoster.Api input;
    private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public SourceDayRosterRegistrationService(JdbcSourceDayRosters storage,SourceDayRoster.Api input,SharedWriteExecutor writes,
                                             PlatformTransactionManager manager,Clock clock){
        this.storage=Objects.requireNonNull(storage);this.input=Objects.requireNonNull(input);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public JdbcSourceDayRosters.Frozen find(LocalDate day){outside();return reads.execute(s->storage.read(day));}
    public JdbcSourceDayRosters.Frozen freeze(LocalDate day){
        outside();var original=find(day);if(original!=null)return original;
        SourceDayRoster.Evidence evidence=reads.execute(s->input.read());if(evidence==null)throw unavailable();
        Instant registered=clock.instant().truncatedTo(ChronoUnit.MICROS);window(day,evidence.capturedAt());window(day,registered);
        if(registered.isBefore(evidence.capturedAt()))throw unavailable();
        var action=new SharedWriteExecutor.ActionContext("source-day-roster:"+day,JdbcSourceDayRosters.owner(day),evidence.hash());
        return writes.execute(action,key->storage.read(day),context->{
            Instant current=clock.instant().truncatedTo(ChronoUnit.MICROS);window(day,current);if(current.isBefore(registered))throw unavailable();
            return storage.save(day,evidence,registered);
        });
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("source day roster registration owns its transactions");}
    private static void window(LocalDate day,Instant time){
        Objects.requireNonNull(day);Objects.requireNonNull(time);
        if(time.isBefore(day.plusDays(1).atTime(0,10).atZone(SHANGHAI).toInstant())||!time.isBefore(day.plusDays(1).atTime(4,30).atZone(SHANGHAI).toInstant()))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("source day roster registration unavailable");}
    @Override public String toString(){return "SourceDayRosterRegistrationService[redacted]";}
}
