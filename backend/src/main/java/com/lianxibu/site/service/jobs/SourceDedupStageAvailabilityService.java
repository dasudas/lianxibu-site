package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Whole-source upper bound, including an original complete zero-fragment plan. */
public final class SourceDedupStageAvailabilityService {
 private final SourceDedupStageProofService proofs;private final JdbcSourceStageAvailability storage;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
 public SourceDedupStageAvailabilityService(SourceDedupStageProofService proofs,JdbcSourceStageAvailability storage,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
  this.proofs=Objects.requireNonNull(proofs);this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Instant capture(FrozenSourceStagePlan.Reference reference){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var committed=reads.execute(status->proofs.readCommitted(reference));if(committed==null)return null;var proof=committed.proof();
  Instant now=clock.instant();if(now.isBefore(committed.minimumObservationTime()))throw invalid();int remainder=now.getNano()%1000;Instant observed=remainder==0?now:now.plusNanos(1000-remainder);String target=proof.observationKeyJson();
  return checked(writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceStageAvailability.key(target),proof.planRef().ownerId(),proof.hash()),key->checked(storage.read(target),committed.minimumObservationTime()),action->{storage.save(target,observed);return observed;}),committed.minimumObservationTime());
 }
 public Instant availableAt(SourceDedupStageResult proof,Instant cutoff){
  if(proof==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();var committed=proofs.readCommitted(proof.planRef());
  if(committed==null||!committed.proof().json().equals(proof.json())||!committed.proof().hash().equals(proof.hash()))throw invalid();
  Instant observed=checked(storage.read(proof.observationKeyJson()),committed.minimumObservationTime());return observed!=null&&!observed.isAfter(cutoff)?observed:null;
 }
 private static Instant checked(Instant observed,Instant minimum){if(observed!=null&&observed.isBefore(minimum))throw invalid();return observed;}
 private static IllegalStateException invalid(){return new IllegalStateException("complete source stage observation must follow original commit");}
}
