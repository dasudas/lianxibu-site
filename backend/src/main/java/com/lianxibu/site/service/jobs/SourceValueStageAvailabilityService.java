package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** First actual observation after reading a complete committed original P2 structure. */
public final class SourceValueStageAvailabilityService {
    private final SourceValueStageProofService proofs;private final JdbcSourceValueStageAvailability storage;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
    public SourceValueStageAvailabilityService(SourceValueStageProofService proofs,JdbcSourceValueStageAvailability storage,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){this.proofs=Objects.requireNonNull(proofs);this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Instant capture(FrozenSourceStagePlan.Reference reference){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var committed=reads.execute(status->proofs.readCommitted(reference));if(committed==null)return null;
        Instant now=clock.instant();if(now.isBefore(committed.minimumObservationTime()))throw invalid();int remainder=now.getNano()%1000;Instant observed=remainder==0?now:now.plusNanos(1000-remainder);var proof=committed.result();String target=proof.observationKeyJson();
        Instant first=writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceValueStageAvailability.key(target),proof.planRef().ownerId(),proof.hash()),key->storage.read(target),action->{storage.save(target,observed);return observed;});if(first.isBefore(committed.minimumObservationTime()))throw invalid();return first;
    }
    public Instant availableAt(SourceValueStageProofService.Committed proof,Instant cutoff){
        if(proof==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();Instant observed=storage.read(proof.result().observationKeyJson());if(observed!=null&&observed.isBefore(proof.minimumObservationTime()))throw invalid();return observed!=null&&!observed.isAfter(cutoff)?observed:null;
    }
    private static IllegalStateException invalid(){return new IllegalStateException("source value observation must follow original commit");}
}
