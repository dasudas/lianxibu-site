package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Pure immutable owner response from the original accepted report and its whole INPUT. */
public final class ReportSupplementResponse {
 private ReportSupplementResponse(){}
 public static ContentService.Job job(ReportSupplementPreparationService.Result result){
  var coverage=coverage(result);var accepted=result.accepted();var input=result.prepared().input();
  return new ContentService.Job(accepted.intentId(),"generation_intent",accepted.intentRevision(),input.stage().name().toLowerCase(Locale.ROOT),0,accepted.state(),accepted.intentId(),accepted.intentRevision(),accepted.contentId(),null,input.requestJson()==null?"no_input":"ready",null,accepted.cutoff(),coverage,null,0,null,0,false,accepted.slotState(),accepted.slotRevision(),accepted.occupiedAttemptId(),null,null,null,List.of(),List.of());
 }
 public static ContentService.ActionResult action(ReportSupplementPreparationService.Result result){
  var coverage=coverage(result);var accepted=result.accepted();if(accepted.baseRevision()==null)throw invalid();
  return new ContentService.ActionResult(accepted.operationId(),new ContentService.ResourceRef("report",accepted.contentId(),null),accepted.baseRevision(),accepted.generation(),accepted.cutoff(),coverage,List.of(new ContentService.ResourceRef("logical_task",accepted.rootId(),null),new ContentService.ResourceRef("generation_intent",accepted.intentId(),null)),null);
 }
 private static ContentService.Coverage coverage(ReportSupplementPreparationService.Result result){
  Objects.requireNonNull(result);var accepted=Objects.requireNonNull(result.accepted());var prepared=Objects.requireNonNull(result.prepared());var input=prepared.input();var full=CanonicalJson.parse(input.snapshotJson());var complete=ReportPreparationInputProfile.complete(full);var context=complete.command().applicationContext();FrozenPublicMetadata.read(full.get(FrozenPublicMetadata.FIELD),context);
  if(!complete.command().explicitSupplement()||accepted.intentRevision()<1||accepted.intentRevision()>CanonicalJson.MAX_SAFE_INTEGER||!accepted.rootId().equals(input.ownerId())||accepted.generation()!=input.generation()||!accepted.intentId().equals(JdbcFrozenInputs.stableId("intent",input.logicalKey()+":"+input.generation()))||!accepted.contentId().equals(context.contentId())||!Objects.equals(accepted.baseRevision(),context.baseContentRevision())||!accepted.cutoff().equals(input.cutoff())||!accepted.inputHash().equals(input.inputHash())||!accepted.state().equals(prepared.receipt().state())||!accepted.originalReceiptHash().equals(HexFormat.of().formatHex(JdbcGenerationLineage.hash(prepared.receipt().payloadJson())))||!accepted.businessInputHash().equals(ReportBusinessInputHash.hash(input.snapshotJson(),input.requestJson()))||!Set.of("free","occupied").contains(accepted.slotState())||accepted.slotRevision()<0||accepted.slotRevision()>CanonicalJson.MAX_SAFE_INTEGER||accepted.slotState().equals("free")!=(accepted.occupiedAttemptId()==null))throw invalid();if(accepted.occupiedAttemptId()!=null)WireValues.id(accepted.occupiedAttemptId(),false);
  var node=full.get(ReportInputProfile.FIELD).get("coverage");if(node.get("missing_inputs").size()>1000)throw new GenerationFlowService.CapacityGuarded();var missing=new ArrayList<ContentService.MissingInput>();for(var value:node.get("missing_inputs"))missing.add(new ContentService.MissingInput(WireValues.text(value.get("kind")),value.get("id").isNull()?null:WireValues.id(value.get("id"),false),value.get("business_date").isNull()?null:WireValues.businessDate(value.get("business_date")),WireValues.text(value.get("reason"))));
  return new ContentService.Coverage(WireValues.text(node.get("state")),List.copyOf(missing),dates(node.get("missing_dates")),dates(node.get("no_update_dates")));
 }
 private static List<java.time.LocalDate> dates(JsonNode values){if(values.size()>366)throw invalid();var dates=new ArrayList<java.time.LocalDate>();for(var value:values)dates.add(WireValues.businessDate(value));return List.copyOf(dates);}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("original report supplement response unavailable");}
}
