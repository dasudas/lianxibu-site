package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** An upper bound is captured only after a successful commit-visible original proof read. */
public final class SourceDedupAvailabilityService {
 private final SourceDedupResultProofService proofs;private final JdbcSourceFragmentAvailability storage;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
 public SourceDedupAvailabilityService(SourceDedupResultProofService proofs,JdbcSourceFragmentAvailability storage,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
  this.proofs=Objects.requireNonNull(proofs);this.storage=Objects.requireNonNull(storage);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
  reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Instant capture(String root){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
  var proof=reads.execute(status->proofs.read(root));if(proof==null)return null;
  Instant now=clock.instant();int remainder=now.getNano()%1000;Instant observed=remainder==0?now:now.plusNanos(1000-remainder);String target=target(proof);
  return writes.execute(new SharedWriteExecutor.ActionContext(JdbcSourceFragmentAvailability.key(target),proof.rootId(),proof.result().inputHash()),key->storage.read(target),action->{storage.save(target,observed);return observed;});
 }
 public Instant availableAt(SourceDedupFragmentProof proof,Instant cutoff){
  if(proof==null||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();Instant observed=storage.read(target(proof));return observed!=null&&!observed.isAfter(cutoff)?observed:null;
 }
 private static String target(SourceDedupFragmentProof p){
  var n=JsonMapper.shared().createObjectNode().put("schema_version","source-fragment-key-v1").put("root_id",p.rootId()).put("input_hash",p.result().inputHash()).put("result_hash",p.result().resultHash())
    .put("member_handoff_hash",p.handoffHash()).put("stage","news_dedup").put("generation",p.generation()).put("stage_part",p.stagePart());return JdbcFrozenInputs.canonical(n);
 }
 private static IllegalStateException invalid(){return new IllegalStateException("source fragment observation must follow original commit");}
}
