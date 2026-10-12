package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ReportInputProfile;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Program-proven complete no-input day, with a real first observation strictly after native commit.
 * No query count, root timestamp, caller DTO or unproved no-input state can authorize a no-update date. */
public final class ReportNoUpdateProofService {
 public static final class Available {
  private final Object issuer;private final ReportPreparedInputService.Prepared prepared;private final JdbcReportNoUpdateFirst.Fact first;
  private Available(Object issuer,ReportPreparedInputService.Prepared prepared,JdbcReportNoUpdateFirst.Fact first){this.issuer=issuer;this.prepared=prepared;this.first=first;}
  public ReportInputCaptureService.Scope scope(){return prepared.scope();}public String rootId(){return prepared.input().ownerId();}
  public String inputHash(){return prepared.input().inputHash();}public Instant availableAt(){return first.observedAt();}
  public long originalByteSize(){return prepared.input().snapshotJson().getBytes(StandardCharsets.UTF_8).length+prepared.receipt().payloadJson().getBytes(StandardCharsets.UTF_8).length+first.payloadJson().getBytes(StandardCharsets.UTF_8).length;}
  @Override public boolean equals(Object other){return other instanceof Available value&&issuer==value.issuer&&prepared.equals(value.prepared)&&first.equals(value.first);}
  @Override public int hashCode(){return Objects.hash(System.identityHashCode(issuer),prepared,first);}
  @Override public String toString(){return "ProgramProvenReportNoUpdate[redacted]";}
 }
 private final Object issuer=new Object();private final ReportPreparedInputService originals;private final JdbcReportNoUpdateFirst facts;
 private final JdbcReportTaskHeaders headers;private final SharedWriteExecutor writes;private final Clock clock;private final TransactionTemplate reads;
 public ReportNoUpdateProofService(DataSource source,ReportPreparedInputService originals,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
  Objects.requireNonNull(source);this.originals=Objects.requireNonNull(originals);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);facts=new JdbcReportNoUpdateFirst(source);headers=new JdbcReportTaskHeaders(source);
  reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setTimeout(1);
 }
 public Available find(ReportInputCaptureService.Scope scope){outside();return reads.execute(s->read(scope));}
 public Available read(ReportInputCaptureService.Scope scope){
  readonly();scope(scope);String root=JdbcReportPreparedInputs.root(scope.logicalKey(),scope.generation());var first=facts.read(root);if(first==null)return null;
  var original=qualified(scope);if(original==null)throw invalid();bind(original,first);return new Available(issuer,original,first);
 }
 public Available verifyObserved(Available value){readonly();if(value==null||value.issuer!=issuer)throw invalid();var fresh=read(value.scope());if(fresh==null||!value.prepared.equals(fresh.prepared)||!value.first.equals(fresh.first))throw invalid();return value;}
 public Available observe(ReportInputCaptureService.Scope scope){
  outside();scope(scope);var prior=find(scope);if(prior!=null)return prior;
  var original=reads.execute(s->qualified(scope));if(original==null)return null;
  Instant now=ceil(Objects.requireNonNull(clock.instant()));if(now.isBefore(original.receipt().recordedAt()))throw invalid();
  var proposed=JdbcReportNoUpdateFirst.fact(original.input().ownerId(),scope.logicalKey(),scope.generation(),scope.day(),original.input().inputHash(),original.input().cutoff(),original.receipt().recordedAt(),now);
  var action=new SharedWriteExecutor.ActionContext(JdbcReportNoUpdateFirst.action(proposed.rootId()),proposed.rootId(),proposed.inputHash());
  var saved=writes.execute(action,k->reads.execute(s->read(scope)),a->{
   var current=reads.execute(s->qualified(scope));if(current==null||!original.equals(current))throw invalid();
   facts.save(proposed);return new Available(issuer,original,proposed);
  });
  var committed=find(scope);if(committed==null||!committed.prepared.equals(saved.prepared))throw invalid();return committed;
 }
 private ReportPreparedInputService.Prepared qualified(ReportInputCaptureService.Scope scope){
  readonly();var prepared=originals.read(scope);if(prepared==null||!"no_input".equals(prepared.input().state()))return null;
  var input=prepared.input();var snapshot=CanonicalJson.parse(input.snapshotJson());ReportInputProfile.validated(snapshot);
  var coverage=snapshot.get(ReportInputProfile.FIELD).get("coverage");if(!input.missing().isEmpty()||!snapshot.get("members").isEmpty()
   ||!coverage.get("missing_inputs").isEmpty()||!coverage.get("missing_dates").isEmpty()||!coverage.get("no_update_dates").isEmpty())return null;
  if(!"no_input".equals(input.coverageState())||input.requestJson()!=null||!"no_input".equals(coverage.get("state").asText()))throw invalid();
  var header=headers.read(input.ownerId());if(header==null||!"no_input".equals(header.state())||header.calls()!=0||!"no_input".equals(header.inputState())||header.application()!=null||header.resultHash()!=null||header.appliedAt()!=null)throw invalid();
  return prepared;
 }
 private static void bind(ReportPreparedInputService.Prepared original,JdbcReportNoUpdateFirst.Fact fact){var scope=original.scope();if(!original.input().ownerId().equals(fact.rootId())||!scope.logicalKey().equals(fact.logicalKey())||scope.generation()!=fact.generation()||!scope.day().equals(fact.day())||!original.input().inputHash().equals(fact.inputHash())||!original.input().cutoff().equals(fact.cutoff())||!original.receipt().recordedAt().equals(fact.preparedAt())||fact.observedAt().isBefore(fact.preparedAt()))throw invalid();}
 private static Instant ceil(Instant value){int rest=value.getNano()%1000;return rest==0?value:value.plusNanos(1000-rest);}
 private static void scope(ReportInputCaptureService.Scope value){if(value==null||value.stage()!=GenerationFlowService.Stage.DAILY_REPORT||value.generation()!=1)throw invalid();}
 private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
 private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("program proven report no update unavailable");}
}
