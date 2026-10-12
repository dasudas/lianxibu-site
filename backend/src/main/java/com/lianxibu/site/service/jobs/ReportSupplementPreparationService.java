package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Owner-only complete supplementary report preparation. Existing action originals precede all mutable providers. */
public final class ReportSupplementPreparationService {
 public static final class Rejected extends RuntimeException {public Rejected(String code){super(code);}}
 public record Result(JdbcReportSupplementActions.Accepted accepted,ReportPreparedInputService.Prepared prepared){@Override public String toString(){return "PreparedReportSupplement[redacted]";}}
 private final DataSource source;private final JdbcTemplate sql;private final ReportPreparedInputService originals;
 private final ReportSupplementBaselineService baselines;private final ReportInputWriterAdmissionService admission;
 private final JdbcReportSupplementActions actions;private final SharedWriteExecutor writes;private final SettingsSnapshotSource settings;
 private final ReportPublicMetadataEnricher metadata;private final GenerationRequestBuilder requests;private final Clock clock;
 private final BooleanSupplier allowed;private final TransactionTemplate reads;
 public ReportSupplementPreparationService(DataSource source,PlatformTransactionManager manager,ReportPreparedInputService originals,
  ReportSupplementBaselineService baselines,ReportInputWriterAdmissionService admission,JdbcReportSupplementActions actions,
  SharedWriteExecutor writes,SettingsSnapshotSource settings,ReportPublicMetadataEnricher metadata,GenerationRequestBuilder requests,Clock clock,BooleanSupplier allowed){
  this.source=Objects.requireNonNull(source);sql=new JdbcTemplate(source);this.originals=Objects.requireNonNull(originals);this.baselines=Objects.requireNonNull(baselines);this.admission=Objects.requireNonNull(admission);this.actions=Objects.requireNonNull(actions);this.writes=Objects.requireNonNull(writes);this.settings=Objects.requireNonNull(settings);this.metadata=Objects.requireNonNull(metadata);this.requests=Objects.requireNonNull(requests);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
  reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public Result prepare(Actor actor,JdbcReportSupplementActions.Command command,String requestKey){
  owner(actor);Objects.requireNonNull(command);if(!actor.userId().equals(command.actor()))throw new JdbcReportSupplementActions.Conflict();
  String key=JdbcReportSupplementActions.action(requestKey);var action=new SharedWriteExecutor.ActionContext(key,JdbcReportSupplementActions.operation(key),null);
  var accepted=writes.executePrepared(action,k->committed(k,command),()->{
   try{return build(command,action);}catch(RuntimeException failure){var saved=reads.execute(status->committed(key,command));if(saved==null)throw failure;return context->saved;}
  });
  var result=reads.execute(status->recover(key,command));if(result==null||!accepted.equals(result.accepted()))throw invalid();return result;
 }
 private JdbcReportSupplementActions.Accepted committed(String key,JdbcReportSupplementActions.Command command){var result=recover(key,command);return result==null?null:result.accepted();}
 private Result recover(String key,JdbcReportSupplementActions.Command command){
  var accepted=actions.read(key,command);if(accepted==null)return null;var scope=scope(command.scope(),accepted.generation());var prepared=originals.lookup(scope);if(prepared==null)throw invalid();
  var input=prepared.input();var complete=ReportPreparationInputProfile.complete(CanonicalJson.parse(input.snapshotJson()));var context=complete.command().applicationContext();
  if(!complete.command().explicitSupplement()||!accepted.rootId().equals(input.ownerId())||!accepted.inputHash().equals(input.inputHash())||!accepted.cutoff().equals(input.cutoff())||!accepted.state().equals(prepared.receipt().state())||!accepted.contentId().equals(context.contentId())||!Objects.equals(accepted.baseRevision(),context.baseContentRevision())||!accepted.originalReceiptHash().equals(HexFormat.of().formatHex(JdbcGenerationLineage.hash(prepared.receipt().payloadJson())))||!accepted.businessInputHash().equals(ReportBusinessInputHash.hash(input.snapshotJson(),input.requestJson())))throw invalid();
  return new Result(accepted,prepared);
 }
 private SharedWriteExecutor.Work<JdbcReportSupplementActions.Accepted> build(JdbcReportSupplementActions.Command command,SharedWriteExecutor.ActionContext action){
  if(TransactionSynchronizationManager.isActualTransactionActive()||!TimeZone.getDefault().toZoneId().getRules().equals(ZoneOffset.UTC.getRules()))throw invalid();requireAllowed();
  var before=baselines.capture(command.scope(),command.expectedIntentRevision(),command.expectedContentId(),command.expectedContentRevision());
  Instant now=Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.MICROS);if(!now.isAfter(before.original().input().cutoff()))throw invalid();
  var scope=before.nextScope();var captured=admission.captureSupplement(scope,now);if(captured==null)throw new ReportInputSnapshotService.ProofUnavailable();
  String root=JdbcReportPreparedInputs.root(scope.logicalKey(),scope.generation());var baseCommand=new Prepare(scope.logicalKey(),scope.generation(),scope.stage(),scope.day(),null,captured.dependencies(),List.of(),captured.sourceProof().verifiedDependencyIds(),true,"0".repeat(64));
  var preferences=writes.execute(action,k->null,a->{requireAllowed();baselines.requireCurrent(before);admission.asWriterSource(captured).snapshot(baseCommand,now);return Objects.requireNonNull(settings.freeze());});
  var base=ReportPreparationInputProfile.create(captured,preferences,before.content().id(),before.content().revision(),true);
  var extension=writes.execute(action,k->null,a->{requireAllowed();baselines.requireCurrent(before);admission.asWriterSource(captured).snapshot(base.command(),now);requireSettings(preferences);return metadata.fields(root,base.command(),now,CanonicalJson.parse(base.snapshotJson()));});
  WireValues.object(extension,Set.of(FrozenPublicMetadata.FIELD),Set.of(FrozenPublicMetadata.FIELD));var full=(ObjectNode)CanonicalJson.parse(base.snapshotJson());full.set(FrozenPublicMetadata.FIELD,extension.get(FrozenPublicMetadata.FIELD).deepCopy());
  var complete=ReportPreparationInputProfile.complete(full);FrozenPublicMetadata.read(full.get(FrozenPublicMetadata.FIELD),complete.command().applicationContext());
  if(full.get(ReportInputProfile.FIELD).get("coverage").get("missing_inputs").size()>1000)throw new GenerationFlowService.CapacityGuarded();
  String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,full);boolean hasContent=!full.get("members").isEmpty();String request=hasContent?requests.build(scope.stage(),complete.snapshotJson(),hash):null;
  String state=hasContent?"queued":full.get(ReportInputProfile.FIELD).get("coverage").get("no_update_dates").isEmpty()?"no_input":"coverage_only";
  var input=new Frozen(root,scope.logicalKey(),scope.generation(),scope.stage(),now,WireValues.text(full.get("coverage_state")),full.get("missing").valueStream().map(JsonNode::asText).toList(),complete.snapshotJson(),hash,request,scope.stage().priority(),now,state);
  String business=ReportBusinessInputHash.hash(input.snapshotJson(),input.requestJson());if(business.equals(ReportBusinessInputHash.hash(before.original().input().snapshotJson(),before.original().input().requestJson())))throw new Rejected("input_unchanged");
  var store=new GenerationJdbcStore(source,Clock.fixed(now,ZoneOffset.UTC),JdbcReportPreparedInputs.ACTOR,root,complete.command());store.primePureInput(input);
  return context->{
   requireAllowed();baselines.requireCurrent(before);admission.asWriterSource(captured).snapshot(complete.command(),now);requireSettings(preferences);
   var current=metadata.fields(root,complete.command(),now,CanonicalJson.parse(base.snapshotJson()));if(!Arrays.equals(CanonicalJson.bytes(extension),CanonicalJson.bytes(current)))throw invalid();
   store.save(input,JdbcReportPreparedInputs.action(root));if(!input.equals(store.committed(JdbcReportPreparedInputs.action(root))))throw invalid();
   long nextRevision=before.latest().revision()+1;String nextIntent=JdbcFrozenInputs.stableId("intent",scope.logicalKey()+":"+scope.generation());
   if(sql.update("UPDATE jobs SET revision=? WHERE id=? AND record_kind='generation_intent' AND generation=? AND revision=0",nextRevision,nextIntent,scope.generation())!=1)throw invalid();
   var rows=sql.query("SELECT CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 65536 THEN action_payload ELSE NULL END FROM jobs WHERE id=? AND record_kind='disposition' AND target_type='generation_prepare' AND operator_id=? LIMIT 2",(r,n)->r.getString(1),JdbcFrozenInputs.stableId("prepare-action",JdbcReportPreparedInputs.action(root)),JdbcReportPreparedInputs.ACTOR);if(rows.size()!=1||rows.getFirst()==null)throw invalid();
   String receipt=JdbcFrozenInputs.canonical(CanonicalJson.parse(rows.getFirst()));
   var slot=new JdbcWorkerState(source).slot();if(slot.id()!=1||slot.fencing()<0||slot.fencing()>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();
   var accepted=new JdbcReportSupplementActions.Accepted(action.preallocatedResourceId(),root,JdbcFrozenInputs.stableId("intent",scope.logicalKey()+":"+scope.generation()),scope.generation(),nextRevision,before.content().id(),before.content().revision(),now,hash,business,state,HexFormat.of().formatHex(JdbcGenerationLineage.hash(receipt)),slot.state(),slot.revision(),slot.attempt());
   actions.append(action.actionKey(),command,accepted,now);if(!accepted.equals(actions.read(action.actionKey(),command)))throw invalid();return accepted;
  };
 }
 private static ReportInputCaptureService.Scope scope(JdbcLatestReportIntent.Scope scope,long generation){return new ReportInputCaptureService.Scope(scope.logicalKey(),generation,Stage.valueOf(scope.type().toUpperCase(Locale.ROOT)),scope.day());}
 private void requireSettings(FrozenGenerationSettings original){if(!original.equals(settings.freeze()))throw invalid();}
 private void requireAllowed(){if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();}
 private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
 private static IllegalStateException invalid(){return new IllegalStateException("complete native report supplement unavailable");}
 @Override public String toString(){return "ReportSupplementPreparationService[redacted]";}
}
