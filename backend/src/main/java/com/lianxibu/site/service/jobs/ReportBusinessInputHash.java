package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.content.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure report business identity. A new cutoff or target management revision alone never adds model budget. */
public final class ReportBusinessInputHash {
 private ReportBusinessInputHash(){}
 public static String hash(String snapshotJson,String preparedRequestJson){
  var snapshot=CanonicalJson.parse(Objects.requireNonNull(snapshotJson));var complete=ReportPreparationInputProfile.complete(snapshot);
  FrozenPublicMetadata.read(snapshot.get(FrozenPublicMetadata.FIELD),complete.command().applicationContext());
  String inputHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);
  var definition=JsonMapper.shared().createObjectNode();
  if(preparedRequestJson==null){
   if(!snapshot.get("members").isEmpty())throw invalid();
   definition.put("schema_version","report-no-model-input-v1");
  }else{
   var request=CanonicalJson.parse(preparedRequestJson);var fields=Set.of("provider","model","input_hash","schema_version","prompt_version","json_schema","response_format","parameters","messages");WireValues.object(request,fields,fields);
   if(!inputHash.equals(WireValues.sha256(request.get("input_hash")))||CanonicalJson.bytes(request).length>1048576)throw invalid();
   var messages=request.get("messages");if(!messages.isArray()||messages.size()!=2)throw invalid();for(var message:messages)WireValues.object(message,Set.of("role","content"),Set.of("role","content"));
   if(!"system".equals(WireValues.text(messages.get(0).get("role")))||!"user".equals(WireValues.text(messages.get(1).get("role")))||!JdbcFrozenInputs.canonical(snapshot).equals(WireValues.text(messages.get(1).get("content"))))throw invalid();
   for(String name:List.of("provider","model","schema_version","prompt_version","json_schema","response_format","parameters"))definition.set(name,request.get(name).deepCopy());
   definition.put("system_message",WireValues.text(messages.get(0).get("content")));
  }
  var business=JsonMapper.shared().createObjectNode().put("schema_version","report-business-input-v1");
  for(String name:List.of("logical_key","stage","business_date","settings_hash","coverage_state","missing"))business.set(name,snapshot.get(name).deepCopy());
  var members=business.putArray("members");for(var member:snapshot.get("members")){var value=(ObjectNode)member.deepCopy();value.remove("completed_at");members.add(value);}
  business.set(ReportInputProfile.FIELD,snapshot.get(ReportInputProfile.FIELD).deepCopy());
  var decisions=business.putArray("report_candidate_decisions_v1");for(var item:snapshot.get("report_candidate_decisions_v1")){var value=(ObjectNode)item.deepCopy();value.remove("count_cutoff");decisions.add(value);}
  var dependencies=business.putArray("dependencies");var manifest=FrozenDependencyManifest.read(snapshot.get(FrozenDependencyManifest.FIELD));
  for(var dependency:manifest.dependencies())dependencies.addObject().put("id",dependency.id()).put("stage",dependency.stage().name()).put("state",dependency.state());
  var expected=business.putArray("expected_dependencies");manifest.expectedIds().forEach(expected::add);
  business.set("reference_targets",complete.command().applicationContext().toJson().get("reference_targets").deepCopy());
  business.set(FrozenPublicMetadata.FIELD,snapshot.get(FrozenPublicMetadata.FIELD).deepCopy());
  business.set("definition",definition);
  return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,business);
 }
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("complete report business input unavailable");}
}
