package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original complete native authority is rechecked while the sole cooperating writer entry is held.
 * A separate readonly RR transaction reads committed source facts; the caller's writable RC
 * transaction and entry lock remain suspended and resume unchanged until business commit. */
public final class ReportInputWriterAdmissionService {
 public static final class Captured {
  private final Object issuer;private final ReportInputCaptureService.Captured original;
  private Captured(Object issuer,ReportInputCaptureService.Captured original){this.issuer=issuer;this.original=original;}
  public ReportInputCaptureService.Scope scope(){return original.scope();}public Instant cutoff(){return original.cutoff();}
  /** Read facts for pure input construction; writer authority still requires asWriterSource. */
  public SourceProof sourceProof(){return original.sourceProof();}
  public List<Dependency> dependencies(){return original.dependencies();}
  public Map<String,List<String>> referenceTargetsByEvidence(){return original.referenceTargetsByEvidence();}
  @Override public String toString(){return "CapturedReportWriterAdmission[redacted]";}
 }
 private final Object issuer=new Object();private final DataSource source;private final JdbcSharedWriteEntry entry;
 private final ReportInputCaptureService inputs;private final TransactionTemplate freshReads;
 public ReportInputWriterAdmissionService(DataSource source,PlatformTransactionManager manager,ReportInputCaptureService inputs){
  this.source=Objects.requireNonNull(source);entry=new JdbcSharedWriteEntry(source);this.inputs=Objects.requireNonNull(inputs);
  freshReads=new TransactionTemplate(Objects.requireNonNull(manager));freshReads.setReadOnly(true);
  freshReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);freshReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  freshReads.setTimeout(1);
 }
 public Captured capture(ReportInputCaptureService.Scope scope,Instant cutoff){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var original=inputs.capture(scope,cutoff);
  return original==null?null:new Captured(issuer,original);
 }
 public Captured captureSupplement(ReportInputCaptureService.Scope scope,Instant cutoff){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var original=inputs.captureSupplement(scope,cutoff);
  return original==null?null:new Captured(issuer,original);
 }
 /** Database-only callback for a prepared report. No caller DTO can mint this authority. */
 public Source asWriterSource(Captured captured){
  if(captured==null||captured.issuer!=issuer)throw invalid();return (command,cutoff)->{
   writer();var original=captured.original;var scope=original.scope();
   if(command==null||command.explicitSupplement()!=original.explicitSupplement()||command.sourceId()!=null||command.stagePart()!=0||!scope.logicalKey().equals(command.logicalKey())
      ||scope.generation()!=command.generation()||scope.stage()!=command.stage()||!scope.day().equals(command.businessDate())
      ||!original.cutoff().equals(cutoff)||!original.dependencies().equals(command.dependencies())
      ||command.expectedDependencies()==null||!new HashSet<>(original.sourceProof().verifiedDependencyIds()).equals(new HashSet<>(command.expectedDependencies()))
      ||command.evidence()==null||!command.evidence().isEmpty()||command.collectionProofs()==null||!command.collectionProofs().isEmpty())throw invalid();
   // This native lock also protects standalone writable callers; a plain transaction is insufficient.
   entry.lock();SourceProof proof=freshReads.execute(status->inputs.verifyObserved(original).sourceProof());
   writer();if(proof==null)throw invalid();return proof;
  };
 }
 private void writer(){
  if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
    ||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
  if(!TransactionSynchronizationManager.hasResource(source))throw invalid();Connection connection=DataSourceUtils.getConnection(source);
  try{if(!DataSourceUtils.isConnectionTransactional(connection,source)||connection.getAutoCommit()||connection.isReadOnly()
     ||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw invalid();}
  catch(SQLException failure){throw invalid();}finally{DataSourceUtils.releaseConnection(connection,source);}
 }
 private static IllegalStateException invalid(){return new IllegalStateException("original native report writer admission unavailable");}
 @Override public String toString(){return "ReportInputWriterAdmissionService[redacted]";}
}
