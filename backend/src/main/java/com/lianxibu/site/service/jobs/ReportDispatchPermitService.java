package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ReportInputProfile;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** Actual complete program preparation and every genuine no-update day, rechecked under the sole writer entry. */
public final class ReportDispatchPermitService {
 private record Original(ReportPreparedInputService.Prepared prepared,List<ReportNoUpdateProofService.Available> noUpdates,long bytes){private Original{noUpdates=List.copyOf(noUpdates);}}
 public static final class Captured {
  private final Object issuer;private final Original original;
  private Captured(Object issuer,Original original){this.issuer=issuer;this.original=original;}
  public String rootId(){return original.prepared.input().ownerId();}public String inputHash(){return original.prepared.input().inputHash();}
  public ReportPreparedInputService.Prepared prepared(){return original.prepared;}public long originalByteSize(){return original.bytes;}
  @Override public String toString(){return "CapturedProgramReportDispatch[redacted]";}
 }
 private final Object issuer=new Object();private final DataSource source;private final JdbcSharedWriteEntry entry;private final JdbcReportTaskHeaders headers;
 private final ReportPreparedInputService prepared;private final ReportNoUpdateProofService noUpdates;private final TransactionTemplate reads;
 public ReportDispatchPermitService(DataSource source,ReportPreparedInputService prepared,ReportNoUpdateProofService noUpdates,PlatformTransactionManager tx){
  this.source=Objects.requireNonNull(source);this.prepared=Objects.requireNonNull(prepared);this.noUpdates=Objects.requireNonNull(noUpdates);entry=new JdbcSharedWriteEntry(source);headers=new JdbcReportTaskHeaders(source);
  reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setTimeout(1);
 }
 public Captured capture(String root){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var original=reads.execute(s->read(root));if(original==null)return null;
  var input=original.prepared.input();var full=ReportPreparationInputProfile.complete(CanonicalJson.parse(input.snapshotJson()));
  String request=new GenerationRequestBuilder().build(full.command().stage(),input.snapshotJson(),input.inputHash());if(!JdbcFrozenInputs.canonical(CanonicalJson.parse(request)).equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(input.requestJson()))))throw invalid();
  return new Captured(issuer,original);
 }
 /** Native RR lookup for preflight or committed receipt resolution. It grants no caller-built permit. */
 public boolean lookup(String root){return reads.execute(s->read(root))!=null;}
 public Captured verifyObserved(Captured value){readonly();if(value==null||value.issuer!=issuer)throw invalid();var current=read(value.rootId());if(current==null||!value.original.equals(current))throw invalid();return value;}
 public Captured verifyInWriter(Captured value){writer();if(value==null||value.issuer!=issuer)throw invalid();entry.lock();var result=reads.execute(s->verifyObserved(value));writer();return result;}
 private Original read(String root){
  readonly();WireValues.id(root,false);var header=headers.read(root);if(header==null)return null;
  GenerationFlowService.Stage stage;try{stage=GenerationFlowService.Stage.valueOf(header.stage().toUpperCase(Locale.ROOT));}catch(RuntimeException rejected){throw invalid();}
  if(!stage.report())throw invalid();var scope=new ReportInputCaptureService.Scope(header.logicalKey(),header.generation(),stage,header.day());
  if(!root.equals(JdbcReportPreparedInputs.root(scope.logicalKey(),scope.generation())))throw invalid();var original=prepared.read(scope);if(original==null)return null;
  if(!"queued".equals(original.input().state())||original.input().requestJson()==null)return null;
  var input=CanonicalJson.parse(original.input().snapshotJson());ReportInputProfile.validated(input);var coverage=input.get(ReportInputProfile.FIELD).get("coverage");
  var proofs=new ArrayList<ReportNoUpdateProofService.Available>();long bytes=original.input().snapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+original.input().requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+original.receipt().payloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  for(var date:coverage.get("no_update_dates")){var day=WireValues.businessDate(date);var proof=noUpdates.read(new ReportInputCaptureService.Scope("report:daily:"+day,1,GenerationFlowService.Stage.DAILY_REPORT,day));
   if(proof==null||proof.availableAt().isAfter(original.input().cutoff()))throw invalid();proofs.add(proof);bytes=Math.addExact(bytes,proof.originalByteSize());
  }
  if(bytes>32L*1024*1024)throw new GenerationFlowService.CapacityGuarded();return new Original(original,proofs,bytes);
 }
 private void writer(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.hasResource(source))throw invalid();var connection=DataSourceUtils.getConnection(source);try{if(!DataSourceUtils.isConnectionTransactional(connection,source)||connection.getAutoCommit()||connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw invalid();}catch(SQLException rejected){throw invalid();}finally{DataSourceUtils.releaseConnection(connection,source);}}
 private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("original program report dispatch proof unavailable");}
 @Override public String toString(){return "ReportDispatchPermitService[redacted]";}
}
