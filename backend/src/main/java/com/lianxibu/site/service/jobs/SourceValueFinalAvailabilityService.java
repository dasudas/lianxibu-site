package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSourceValueFinalAvailability;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** First real observation after the complete final receipt transaction has committed. */
public final class SourceValueFinalAvailabilityService {
    private final SourceValueFinalizationService finals;private final JdbcSourceValueFinalAvailability storage;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
    public SourceValueFinalAvailabilityService(SourceValueFinalizationService finals,JdbcSourceValueFinalAvailability storage,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){this.finals=Objects.requireNonNull(finals);this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Instant capture(FrozenSourceStagePlan.Reference reference){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var committed=reads.execute(status->finals.readCommitted(reference));if(committed==null)return null;String target=committed.observationKeyJson();
        Instant now=clock.instant();if(now.isBefore(committed.minimumObservationTime()))throw invalid();int remainder=now.getNano()%1000;Instant observed=remainder==0?now:now.plusNanos(1000-remainder);
        Instant first=writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceValueFinalAvailability.key(target),committed.receipt().position().ownerId(),committed.result().hash()),key->storage.read(target),action->{storage.save(target,observed);return observed;});if(first.isBefore(committed.minimumObservationTime()))throw invalid();return first;
    }
    public Instant availableAt(SourceValueFinalizationService.Committed proof,Instant cutoff){
        if(proof==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();Instant observed=storage.read(proof.observationKeyJson());if(observed!=null&&observed.isBefore(proof.minimumObservationTime()))throw invalid();return observed!=null&&!observed.isAfter(cutoff)?observed:null;
    }
    private static IllegalStateException invalid(){return new IllegalStateException("final source value observation must follow its original commit");}
}
