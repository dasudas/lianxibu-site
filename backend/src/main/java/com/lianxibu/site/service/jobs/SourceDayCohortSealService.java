package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSourceDayCohortSeals;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Original complete day seal; configured run reservation and collector admission remain separate. */
public final class SourceDayCohortSealService {
    public static final class Sealed {
        private final SourceDayCohortPlan plan;private final Instant sealedAt;
        private Sealed(SourceDayCohortPlan plan,Instant sealedAt){this.plan=plan;this.sealedAt=sealedAt;}
        public SourceDayCohortPlan plan(){return plan;}public Instant sealedAt(){return sealedAt;}
        @Override public String toString(){return "SealedSourceDayCohorts[redacted]";}
    }
    private final SourceDayRosterRegistrationService registration;private final JdbcSourceDayRosters directories;
    private final JdbcSourceDayCohortSeals seals;private final SharedWriteExecutor writes;private final TransactionTemplate reads;private final Clock clock;
    public SourceDayCohortSealService(SourceDayRosterRegistrationService registration,JdbcSourceDayRosters directories,JdbcSourceDayCohortSeals seals,SharedWriteExecutor writes,PlatformTransactionManager manager,Clock clock){
        this.registration=Objects.requireNonNull(registration);this.directories=Objects.requireNonNull(directories);this.seals=Objects.requireNonNull(seals);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Sealed find(LocalDate day){
        outside();return reads.execute(status->readObserved(day));
    }
    /** Borrow the caller's explicit readonly RR snapshot; no current source configuration is consulted. */
    public Sealed readObserved(LocalDate day){
        readonly();Objects.requireNonNull(day);var archive=seals.read(day);return archive==null?null:verify(archive,directories.read(day));
    }
    /** Recheck the complete original bundle, directory and seal time within the same RR snapshot. */
    public Sealed verifyObserved(Sealed original){
        readonly();if(original==null)throw unavailable();var fresh=readObserved(original.plan().directory().day());
        if(fresh==null||!fresh.plan().bundleJson().equals(original.plan().bundleJson())||!fresh.plan().bundleHash().equals(original.plan().bundleHash())||!fresh.plan().directory().ownerId().equals(original.plan().directory().ownerId())||!fresh.sealedAt().equals(original.sealedAt()))throw unavailable();return original;
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw unavailable();}
    public Sealed seal(LocalDate day){
        outside();var previous=find(day);if(previous!=null)return previous;
        var original=registration.freeze(day);var plan=SourceDayCohortPlan.derive(original);Instant preparedAt=clock.instant().truncatedTo(ChronoUnit.MICROS);
        String bundle=plan.bundleJson(),hash=plan.bundleHash();var prepared=JdbcSourceDayCohortSeals.prepare(bundle,hash,preparedAt);
        var action=new SharedWriteExecutor.ActionContext("source-day-cohort:"+day,original.ownerId(),hash);
        var committed=writes.execute(action,key->seals.read(day),context->{
            Instant current=clock.instant().truncatedTo(ChronoUnit.MICROS);
            if(current.isBefore(preparedAt)||!current.isBefore(SourceDedupBarrier.deadline(day)))throw unavailable();return seals.save(prepared);
        });
        // A concurrent original or an uncertain commit is verified against durable original facts.
        var directory=reads.execute(status->directories.read(day));return verify(committed,directory);
    }
    private static Sealed verify(JdbcSourceDayCohortSeals.Archive archive,JdbcSourceDayRosters.Frozen directory){
        if(archive==null||directory==null||!archive.day().equals(directory.day())||!archive.ownerId().equals(directory.ownerId()))throw unavailable();
        return new Sealed(SourceDayCohortPlan.readBundle(archive.bundleJson(),archive.bundleHash(),directory),archive.sealedAt());
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("source day cohort seal service owns its transactions");}
    private static IllegalStateException unavailable(){return new IllegalStateException("original source day cohort seal unavailable");}
    @Override public String toString(){return "SourceDayCohortSealService[redacted]";}
}
