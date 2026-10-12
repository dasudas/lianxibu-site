package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** Formal program preparation authority surrounds the existing atomic body/ready/version write. */
public final class ReportProgramResultHandler implements ResultApplicationService.Handler {
 private final ResultApplicationService.Handler delegate;private final JdbcFrozenInputs inputs;private final ReportDispatchPermitService permits;private final TransactionTemplate reads;
 public ReportProgramResultHandler(ResultApplicationService.Handler delegate,JdbcFrozenInputs inputs,PlatformTransactionManager tx,ReportDispatchPermitService permits){this.delegate=Objects.requireNonNull(delegate);this.inputs=Objects.requireNonNull(inputs);this.permits=permits;reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 @Override public ResultApplicationService.Handler prepare(ResultApplicationService.Input input,ResultApplicationService.Validated result){return capture(input,false,delegate.prepare(input,result));}
 @Override public ResultApplicationService.Handler prepareManual(ResultApplicationService.Input input){return capture(input,true,delegate.prepareManual(input));}
 private ResultApplicationService.Handler capture(ResultApplicationService.Input input,boolean manual,ResultApplicationService.Handler actual){
  if(TransactionSynchronizationManager.isActualTransactionActive()||input==null||input.stage()==null||!input.stage().report()||actual==null)throw invalid();
  var snapshot=Objects.requireNonNull(reads.execute(s->CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()))));
  if(!snapshot.has(ReportPreparationInputProfile.FIELD)){if(permits!=null)throw invalid();return actual;}
  if(permits==null)throw invalid();var full=ReportPreparationInputProfile.complete(snapshot);var context=full.command().applicationContext();
  if(!input.equals(context.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision())))throw invalid();
  var captured=permits.capture(input.rootId());if(captured==null||!input.inputHash().equals(captured.inputHash())||!full.snapshotJson().equals(captured.prepared().input().snapshotJson()))throw invalid();
  return (value,result,current,mode)->{if(!input.equals(value)||manual!=mode)throw invalid();permits.verifyInWriter(captured);return actual.apply(value,result,current,mode);};
 }
 @Override public ResultApplicationService.Applied apply(ResultApplicationService.Input input,ResultApplicationService.Validated result,ResultApplicationService.Current current,boolean manual){throw invalid();}
 private static ResultApplicationService.Rejected invalid(){return new ResultApplicationService.Rejected();}
}
