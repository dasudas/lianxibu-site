package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ReportSupplementContentBaselineService;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Latest original intent and native content CAS baseline. Source permission is checked separately. */
public final class ReportSupplementBaselineService {
 public static final class Conflict extends RuntimeException {public Conflict(){super("revision_conflict");}}
 public static final class Captured {
  private final ReportSupplementBaselineService issuer;private final JdbcLatestReportIntent.Latest latest;
  private final ReportPreparedInputService.Prepared original;private final ReportSupplementContentBaselineService.Key content;
  private Captured(ReportSupplementBaselineService issuer,JdbcLatestReportIntent.Latest latest,ReportPreparedInputService.Prepared original,ReportSupplementContentBaselineService.Key content){this.issuer=issuer;this.latest=latest;this.original=original;this.content=content;}
  public JdbcLatestReportIntent.Latest latest(){return latest;}
  public ReportPreparedInputService.Prepared original(){return original;}
  public ReportSupplementContentBaselineService.Key content(){return content;}
  public ReportInputCaptureService.Scope nextScope(){var s=latest.scope();return new ReportInputCaptureService.Scope(s.logicalKey(),latest.generation()+1,GenerationFlowService.Stage.valueOf(s.type().toUpperCase(Locale.ROOT)),s.day());}
  @Override public String toString(){return "SupplementReportBaseline[redacted]";}
 }
 private final DataSource source;private final JdbcLatestReportIntent intents;private final ReportPreparedInputService originals;
 private final ReportSupplementContentBaselineService contents;private final TransactionTemplate reads;
 public ReportSupplementBaselineService(DataSource source,PlatformTransactionManager manager,JdbcLatestReportIntent intents,ReportPreparedInputService originals,ReportSupplementContentBaselineService contents){
  this.source=Objects.requireNonNull(source);this.intents=Objects.requireNonNull(intents);this.originals=Objects.requireNonNull(originals);this.contents=Objects.requireNonNull(contents);
  reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setTimeout(1);
 }
 public Captured capture(JdbcLatestReportIntent.Scope scope,long expectedIntentRevision,String expectedContentId,Long expectedContentRevision){
  if(TransactionSynchronizationManager.isActualTransactionActive()||expectedIntentRevision<0||expectedIntentRevision>CanonicalJson.MAX_SAFE_INTEGER||(expectedContentId==null)!=(expectedContentRevision==null)||expectedContentRevision!=null&&(expectedContentRevision<1||expectedContentRevision>=CanonicalJson.MAX_SAFE_INTEGER))throw invalid();
  Objects.requireNonNull(scope);return reads.execute(status->{
   var latest=intents.read(scope);if(latest==null||latest.generation()>=CanonicalJson.MAX_SAFE_INTEGER||latest.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!Set.of("queued","no_input","coverage_only").contains(latest.state()))throw invalid();if(latest.revision()!=expectedIntentRevision)throw new Conflict();
   var currentScope=new ReportInputCaptureService.Scope(scope.logicalKey(),latest.generation(),GenerationFlowService.Stage.valueOf(scope.type().toUpperCase(Locale.ROOT)),scope.day());
   var original=originals.read(currentScope);String id=ReportPreparationService.contentId(currentScope);
   if(original==null||!latest.cutoff().equals(original.input().cutoff())||!latest.state().equals(original.receipt().state())||!id.equals(ReportPreparationInputProfile.complete(CanonicalJson.parse(original.input().snapshotJson())).command().applicationContext().contentId()))throw invalid();
   var content=contents.read(id,scope.type().replace("_report",""),scope.day());
   if(expectedContentId==null?content.revision()!=null:!id.equals(expectedContentId)||!expectedContentRevision.equals(content.revision()))throw new Conflict();
   return new Captured(this,latest,original,content);
  });
 }
 public void requireCurrent(Captured captured){
  if(captured==null||captured.issuer!=this||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||!TransactionSynchronizationManager.hasResource(source))throw invalid();
  var scope=captured.latest.scope();var latest=intents.applicationRead(scope);if(latest==null)throw invalid();if(!captured.latest.equals(latest))throw new Conflict();
  var original=originals.lookup(captured.original.scope());if(!captured.original.equals(original))throw invalid();
  var content=contents.applicationRead(captured.content.id(),scope.type().replace("_report",""),scope.day());if(!captured.content.equals(content)){if(Objects.equals(captured.content.revision(),content.revision()))throw invalid();throw new Conflict();}
 }
 private static IllegalStateException invalid(){return new IllegalStateException("supplement report intent and content baseline unavailable");}
 @Override public String toString(){return "ReportSupplementBaselineService[redacted]";}
}
