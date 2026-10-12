package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.content.ReportInputProfile;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure complete report INPUT and original command recovery. This type grants no native writer permission. */
public final class ReportPreparationInputProfile {
 public static final String FIELD="report_preparation_command_v1";
 private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
 private static final Set<String> FIELDS=Set.of("schema_version","dependencies","expected_dependencies","explicit_supplement");
 private static final Set<String> DEPENDENCY=Set.of("id","stage","state","completed_at");
 public record Complete(Prepare command,String snapshotJson){@Override public String toString(){return "CompleteReportPreparationInput[redacted]";}}
 private ReportPreparationInputProfile(){}
 public static Complete create(ReportInputWriterAdmissionService.Captured original,FrozenGenerationSettings settings,String contentId,Long baseRevision,boolean supplement){
  Objects.requireNonNull(original);Objects.requireNonNull(settings);WireValues.id(contentId,false);
  var scope=original.scope();var dependencies=original.dependencies();var expected=original.sourceProof().verifiedDependencyIds();
  var command=new Prepare(scope.logicalKey(),scope.generation(),scope.stage(),scope.day(),null,dependencies,List.of(),expected,supplement,settings.semanticHash());
  var frozen=new ReportInputSnapshotService((c,t)->original.sourceProof()).freeze(command,original.cutoff());
  var snapshot=(ObjectNode)CanonicalJson.parse(frozen.snapshotJson());var profile=snapshot.get(ReportInputProfile.FIELD);
  var targets=new TreeMap<String,JsonNode>();
  for(var member:snapshot.get("members")){
   String id=WireValues.id(member.get("id"),false);var refs=original.referenceTargetsByEvidence().get(id);if(refs==null)throw invalid();
   for(String ref:refs){var value=CanonicalJson.parse(ref);targets.put(JdbcFrozenInputs.canonical(value),value);}
  }
  var context=JsonMapper.shared().createObjectNode().put("schema_version","frozen-application-context-v1").put("stage",scope.stage().name().toLowerCase(Locale.ROOT)).put("generation",scope.generation());
  var content=context.putObject("target_content").put("kind","report").put("id",contentId);if(baseRevision==null)content.putNull("base_revision");else content.put("base_revision",baseRevision);
  context.putNull("target_entity_id");context.put("period_start",WireValues.text(profile.get("period_start")));context.put("period_end",WireValues.text(profile.get("period_end")));
  for(String field:List.of("item_ids","candidates_by_item","eligible_high_value_item_ids","version_eligible_item_ids"))context.putArray(field);
  var citations=new TreeSet<String>();var references=context.putArray("reference_targets");for(var target:targets.values()){references.add(target.deepCopy());if(!citations.add(AnalysisPublicMetadataEnricher.citationId(target)))throw invalid();}
  var allowed=context.putArray("allowed_citation_ids");citations.forEach(allowed::add);context.put("references_complete",true);
  snapshot.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(dependencies,expected));
  snapshot.set(FrozenApplicationContext.FIELD,FrozenApplicationContext.fromJson(context).toJson());snapshot.set(FrozenGenerationSettings.FIELD,settings.toJson());
  var proof=snapshot.putObject(FIELD).put("schema_version","report-preparation-command-v1").put("explicit_supplement",supplement);
  var deps=proof.putArray("dependencies");for(var dep:dependencies)deps.addObject().put("id",dep.id()).put("stage",dep.stage().name().toLowerCase(Locale.ROOT)).put("state",dep.state()).put("completed_at",MICROS.format(dep.completedAt()));
  var wanted=proof.putArray("expected_dependencies");expected.forEach(wanted::add);return complete(snapshot);
 }
 public static Complete complete(JsonNode original){
  var snapshot=(ObjectNode)CanonicalJson.parse(CanonicalJson.bytes(original));ReportInputProfile.validated(snapshot);
  var proof=snapshot.get(FIELD);WireValues.object(proof,FIELDS,FIELDS);if(!"report-preparation-command-v1".equals(WireValues.text(proof.get("schema_version")))||!proof.get("explicit_supplement").isBoolean())throw invalid();
  if(CanonicalJson.bytes(proof).length>262144)throw new GenerationFlowService.CapacityGuarded();
  var stage=Stage.valueOf(WireValues.text(snapshot.get("stage")).toUpperCase(Locale.ROOT));var day=WireValues.businessDate(snapshot.get("business_date"));
  var scope=new ReportInputCaptureService.Scope(WireValues.text(snapshot.get("logical_key")),WireValues.nonnegativeInteger(snapshot.get("generation")),stage,day);
  var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));var settings=FrozenGenerationSettings.read(snapshot.get(FrozenGenerationSettings.FIELD));
  if(context.stage()!=stage||context.generation()!=scope.generation()||!context.referencesComplete()||!settings.semanticHash().equals(WireValues.sha256(snapshot.get("settings_hash"))))throw invalid();
  var publicProfile=snapshot.get(ReportInputProfile.FIELD);if(!context.periodStart().equals(WireValues.text(publicProfile.get("period_start")))||!context.periodEnd().equals(WireValues.text(publicProfile.get("period_end"))))throw invalid();
  Instant cutoff=WireValues.utcMicroseconds(snapshot.get("cutoff"));Instant planned=day.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
  boolean supplement=proof.get("explicit_supplement").booleanValue();if(cutoff.isBefore(planned)||!supplement&&!cutoff.equals(planned))throw invalid();
  var dependencies=new ArrayList<Dependency>();var ids=new HashSet<String>();var values=proof.get("dependencies");if(!values.isArray()||values.size()>1000)throw invalid();
  for(var value:values){WireValues.object(value,DEPENDENCY,DEPENDENCY);String id=WireValues.id(value.get("id"),false),wire=WireValues.text(value.get("stage"));Stage dependencyStage=Stage.valueOf(wire.toUpperCase(Locale.ROOT));Instant first=WireValues.utcMicroseconds(value.get("completed_at"));
   if(!wire.equals(dependencyStage.name().toLowerCase(Locale.ROOT))||!ids.add(id)||!"succeeded".equals(WireValues.text(value.get("state")))||first.isAfter(cutoff))throw invalid();
   dependencies.add(new Dependency(id,dependencyStage,"succeeded",first));
  }
  var expected=new ArrayList<String>();var wanted=proof.get("expected_dependencies");if(!wanted.isArray()||wanted.size()>1000)throw invalid();var distinct=new HashSet<String>();
  for(var value:wanted){String id=WireValues.id(value,false);if(!distinct.add(id))throw invalid();expected.add(id);}if(!distinct.equals(ids))throw invalid();
  for(var member:snapshot.get("members"))for(var reference:member.get("references"))if(!ids.contains(WireValues.id(reference,false)))throw invalid();
  FrozenDependencyManifest.read(snapshot.get(FrozenDependencyManifest.FIELD));
  if(!Arrays.equals(CanonicalJson.bytes(snapshot.get(FrozenDependencyManifest.FIELD)),CanonicalJson.bytes(FrozenDependencyManifest.create(dependencies,expected))))throw invalid();
  var command=new Prepare(scope.logicalKey(),scope.generation(),stage,day,null,List.copyOf(dependencies),List.of(),List.copyOf(expected),supplement,settings.semanticHash(),context);
  return new Complete(command,JdbcFrozenInputs.canonical(snapshot));
 }
 private static IllegalStateException invalid(){return new IllegalStateException("complete original report preparation profile unavailable");}
}
