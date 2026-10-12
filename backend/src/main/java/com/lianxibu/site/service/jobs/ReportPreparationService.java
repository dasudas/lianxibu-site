package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;

import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Complete initial daily/week/month preparation. Model request construction is outside every writer.
 * Existing originals precede settings, source, clocks, and the current preparation switch. */
public final class ReportPreparationService {
 private final DataSource source;private final ReportPreparedInputService originals;private final ReportInputWriterAdmissionService admission;
 private final SharedWriteExecutor writes;private final SettingsSnapshotSource settings;private final ReportPublicMetadataEnricher metadata;
 private final GenerationRequestBuilder requests;private final Clock clock;private final BooleanSupplier allowed;private final ReportPreparationContentBaselineService current;
 public ReportPreparationService(DataSource source,ReportPreparedInputService originals,ReportInputWriterAdmissionService admission,SharedWriteExecutor writes,
  SettingsSnapshotSource settings,ReportPublicMetadataEnricher metadata,GenerationRequestBuilder requests,Clock clock,BooleanSupplier allowed){
  this.source=Objects.requireNonNull(source);this.originals=Objects.requireNonNull(originals);this.admission=Objects.requireNonNull(admission);
  this.writes=Objects.requireNonNull(writes);this.settings=Objects.requireNonNull(settings);this.metadata=Objects.requireNonNull(metadata);
  this.requests=Objects.requireNonNull(requests);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);current=new ReportPreparationContentBaselineService(source);
 }
 public ReportPreparedInputService.Prepared prepare(ReportInputCaptureService.Scope scope){
  Objects.requireNonNull(scope);String root=JdbcReportPreparedInputs.root(scope.logicalKey(),scope.generation()),key=JdbcReportPreparedInputs.action(root);
  var action=new SharedWriteExecutor.ActionContext(key,root,null);
  var saved=writes.executePrepared(action,k->{if(!key.equals(k))throw invalid();var existing=originals.lookup(scope);return existing==null?null:existing.input();},()->{
   try{return build(scope,action);}catch(RuntimeException failure){var stored=originals.find(scope);if(stored==null)throw failure;return a->stored.input();}
  });
  var committed=originals.find(scope);if(committed==null||!committed.input().equals(saved))throw invalid();return committed;
 }
 private SharedWriteExecutor.Work<Frozen> build(ReportInputCaptureService.Scope scope,SharedWriteExecutor.ActionContext action){
  if(TransactionSynchronizationManager.isActualTransactionActive()||scope.generation()!=1)throw invalid();
  // The existing generic native writer uses JDBC Timestamp. The formal runtime must launch in UTC;
  // all report business dates and natural periods still use explicit Asia/Shanghai.
  if(!TimeZone.getDefault().toZoneId().getRules().equals(ZoneOffset.UTC.getRules()))throw invalid();
  Instant now=Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.MICROS),cutoff=scope.day().plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
  if(now.isBefore(cutoff))throw new ReportInputSnapshotService.ProofUnavailable();
  var captured=admission.capture(scope,cutoff);if(captured==null)throw new ReportInputSnapshotService.ProofUnavailable();
  var baseCommand=new Prepare(scope.logicalKey(),scope.generation(),scope.stage(),scope.day(),null,captured.dependencies(),List.of(),captured.sourceProof().verifiedDependencyIds(),false,"0".repeat(64));
  String content=contentId(scope);var frozenSettings=writes.execute(action,k->null,a->{requireAllowed();admission.asWriterSource(captured).snapshot(baseCommand,cutoff);requireNewContent(content);return Objects.requireNonNull(settings.freeze());});
  var base=ReportPreparationInputProfile.create(captured,frozenSettings,content,null,false);
  var extension=writes.execute(action,k->null,a->{requireAllowed();admission.asWriterSource(captured).snapshot(base.command(),cutoff);requireSettings(frozenSettings);requireNewContent(content);return metadata.fields(action.preallocatedResourceId(),base.command(),cutoff,CanonicalJson.parse(base.snapshotJson()));});
  WireValues.object(extension,Set.of(FrozenPublicMetadata.FIELD),Set.of(FrozenPublicMetadata.FIELD));
  var full=(ObjectNode)CanonicalJson.parse(base.snapshotJson());full.set(FrozenPublicMetadata.FIELD,extension.get(FrozenPublicMetadata.FIELD).deepCopy());
  var complete=ReportPreparationInputProfile.complete(full);FrozenPublicMetadata.read(full.get(FrozenPublicMetadata.FIELD),complete.command().applicationContext());
  String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,full);boolean hasContent=!full.get("members").isEmpty();String request=hasContent?requests.build(scope.stage(),complete.snapshotJson(),hash):null;
  String state=hasContent?"queued":full.get(ReportInputProfile.FIELD).get("coverage").get("no_update_dates").isEmpty()?"no_input":"coverage_only";
  var input=new Frozen(action.preallocatedResourceId(),scope.logicalKey(),scope.generation(),scope.stage(),cutoff,WireValues.text(full.get("coverage_state")),full.get("missing").valueStream().map(JsonNode::asText).toList(),complete.snapshotJson(),hash,request,scope.stage().priority(),now,state);
  var store=new GenerationJdbcStore(source,Clock.fixed(now,ZoneOffset.UTC),JdbcReportPreparedInputs.ACTOR,input.ownerId(),complete.command());store.primePureInput(input);
  return a->{requireAllowed();admission.asWriterSource(captured).snapshot(complete.command(),cutoff);requireSettings(frozenSettings);requireNewContent(content);
   var latest=metadata.fields(input.ownerId(),complete.command(),cutoff,CanonicalJson.parse(base.snapshotJson()));if(!Arrays.equals(CanonicalJson.bytes(extension),CanonicalJson.bytes(latest)))throw invalid();
   store.save(input,a.actionKey());
   // Validate the original full writer value before returning; the receipt lookup on replay performs native recovery.
   if(!input.equals(store.committed(a.actionKey())))throw invalid();
   return input;
  };
 }
 public static String contentId(ReportInputCaptureService.Scope scope){return JdbcFrozenInputs.stableId("report-content",Objects.requireNonNull(scope).logicalKey());}
 private void requireNewContent(String id){current.requireAbsent(id);}
 private void requireSettings(FrozenGenerationSettings original){if(!original.equals(settings.freeze()))throw invalid();}
 private void requireAllowed(){if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();}
 private static IllegalStateException invalid(){return new IllegalStateException("complete initial native report preparation unavailable");}
 @Override public String toString(){return "ReportPreparationService[redacted]";}
}
