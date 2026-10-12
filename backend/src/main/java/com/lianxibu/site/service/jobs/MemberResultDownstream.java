package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Permanent fragment completion. A later coordinator owns source-wide aggregation. */
public final class MemberResultDownstream implements DedupResultHandler.Downstream,ValueResultHandler.Downstream {
 private final JdbcFrozenInputs inputs;private final JdbcMemberStageResults results;private final SourceMemberStageService members;private final Clock clock;private final Map<String,CatalogVersionJudgmentLedger> versionLedgers;
 public MemberResultDownstream(JdbcFrozenInputs inputs,JdbcMemberStageResults results,SourceMemberStageService members,Clock clock){this(inputs,results,members,clock,Map.of());}
 public MemberResultDownstream(JdbcFrozenInputs inputs,JdbcMemberStageResults results,SourceMemberStageService members,Clock clock,Map<String,CatalogVersionJudgmentLedger> versionLedgers){this.inputs=Objects.requireNonNull(inputs);this.results=Objects.requireNonNull(results);this.members=Objects.requireNonNull(members);this.clock=Objects.requireNonNull(clock);this.versionLedgers=Map.copyOf(Objects.requireNonNull(versionLedgers));if(!Set.of("agent","skill","workflow","model").containsAll(this.versionLedgers.keySet()))throw invalid();}
 @Override public void afterApplied(Input input,Validated result,List<Decision> decisions,Set<String> reanalyze){
  if(input==null||"2".equals(input.dedupResultVersion()))throw invalid();dedup(input,result,decisions,reanalyze,null);
 }
 @Override public void afterCatalogApplied(Input input,Validated result,List<Decision> decisions,CatalogVersionBatchJudgmentWriter.Outcome versions){
  if(input==null||!"2".equals(input.dedupResultVersion())||versions==null)throw invalid();dedup(input,result,decisions,versions.reanalysisItems(),versions);
 }
 private void dedup(Input input,Validated result,List<Decision> decisions,Set<String> reanalyze,CatalogVersionBatchJudgmentWriter.Outcome versions){
  var frozen=validate(input,result);if(!input.stage().dedup()||decisions==null||reanalyze==null)throw invalid();
  if(versions!=null)verifyVersions(input,result,versions);
  var decisionById=new HashMap<String,Decision>();for(var d:decisions)if(d==null||decisionById.putIfAbsent(d.itemId(),d)!=null)throw invalid();if(!decisionById.keySet().equals(input.itemIds()))throw invalid();
  var expected=new ArrayList<SourceMemberStageService.Expected>();var recommended=new HashSet<String>();
  for(var item:CanonicalJson.parse(result.payloadJson()).get("items")){
   String id=item.get("item_id").asText();var d=decisionById.get(id);var f=frozen.get(id);boolean review="needs_review".equals(item.get("decision").asText());
   if(!Objects.equals(d.state(),review?"needs_review":"ready")||review!=(d.entityId()==null)||"existing".equals(item.get("decision").asText())&&!Objects.equals(d.entityId(),item.get("candidate_entity_id").asText()))throw invalid();
   expected.add(new SourceMemberStageService.Expected(id,f.expectedRevision()+1,f.category(),d.entityId(),d.state()));if(item.get("reanalyze_recommended").booleanValue())recommended.add(id);
  }
  if(versions==null&&!recommended.equals(reanalyze))throw invalid();persist(input,result,frozen,()->members.complete(input.rootId(),expected));
 }
 /** First-ledger authority is resolved from the exact original frozen key, never the latest proposal. */
 private void verifyVersions(Input input,Validated result,CatalogVersionBatchJudgmentWriter.Outcome versions){
  String category=input.stage().name().toLowerCase(Locale.ROOT).replace("_dedup","");var ledger=versionLedgers.get(category);if(ledger==null)throw invalid();
  var snapshot=CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()));var original=FrozenCatalogVersionProjections.read(snapshot.get(FrozenCatalogVersionProjections.FIELD),input);
  var selected=new HashSet<String>();var byKey=new HashMap<String,CatalogVersionJudgmentLedger.Stored>();
  for(var item:CanonicalJson.parse(result.payloadJson()).get("items")){
   if(item.get("version_changed").isNull())continue;String id=item.get("item_id").asText();var entry=original.entries().get(id);var supplied=versions.judgments().get(id);
   if(entry==null||supplied==null||!selected.add(id)||!entry.entityId().equals(item.get("candidate_entity_id").asText()))throw invalid();
   var stored=byKey.computeIfAbsent(entry.judgmentKey(),key->ledger.lockAndRead(key).orElseThrow(MemberResultDownstream::invalid));
   if(!category.equals(stored.category())||!entry.entityId().equals(stored.entityId())||!entry.judgmentKey().equals(supplied.judgmentKey())||!stored.json().equals(supplied.json()))throw invalid();
  }
  if(!selected.equals(versions.judgments().keySet()))throw invalid();
 }
 @Override public void afterApplied(Input input,Validated result,List<Value> values){
  var frozen=validate(input,result);if(input.stage()!=GenerationFlowService.Stage.NEWS_VALUE||values==null)throw invalid();
  var snapshot=CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()));var entityIds=new HashMap<String,String>();
  if(snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(NewsValueInputProfile.ELIGIBILITY_FIELD)) {
   NewsValueInputProfile.complete(snapshot);for(var member:snapshot.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members"))entityIds.put(member.get("item_id").asText(),member.get("entity_id").asText());
   if(!entityIds.keySet().equals(frozen.keySet()))throw invalid();
  }
  var byId=new HashMap<String,Value>();for(var value:values)if(value==null||byId.putIfAbsent(value.itemId(),value)!=null)throw invalid();if(!byId.keySet().equals(input.itemIds()))throw invalid();
  var payload=CanonicalJson.parse(result.payloadJson());var selected=new HashSet<String>();payload.get("selected_item_ids").forEach(n->selected.add(n.asText()));var expected=new ArrayList<SourceMemberStageService.Ready>();
  for(var item:payload.get("items")){String id=item.get("item_id").asText();var f=frozen.get(id);var value=byId.get(id);if(value.expectedRevision()!=f.expectedRevision()||!f.category().equals(value.memberCategory())||!value.category().equals(item.get("category").asText())||value.highValue()!=item.get("high_value").booleanValue()||value.selected()!=selected.contains(id)||!Objects.equals(value.reason(),item.get("reason").asText())
      ||!Objects.equals(value.valuable(),"2".equals(input.valueResultVersion())?Boolean.valueOf(item.get("valuable").booleanValue()):null)
      ||(entityIds.isEmpty()?value.entityId()!=null||!f.category().equals(value.category()):!Objects.equals(entityIds.get(id),value.entityId())))throw invalid();expected.add(new SourceMemberStageService.Ready(id,f.expectedRevision()+1,f.category()));}
  persist(input,result,frozen,()->members.completeReady(input.rootId(),expected),entityIds);
 }
 private Map<String,FrozenMemberBindings.Member> validate(Input input,Validated result){
  JdbcFrozenInputs.requireTx();if(input==null||result==null)throw invalid();var snapshot=CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()));var frozen=FrozenMemberBindings.read(snapshot,input);
  var envelope=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",result.inputHash()).put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw invalid();return frozen;
 }
 private void persist(Input input,Validated result,Map<String,FrozenMemberBindings.Member> frozen,Supplier<List<SourceMemberStageService.Fact>> apply){
  persist(input,result,frozen,apply,Map.of());
 }
 private void persist(Input input,Validated result,Map<String,FrozenMemberBindings.Member> frozen,Supplier<List<SourceMemberStageService.Fact>> apply,Map<String,String> entityIds){
  var root=results.root(input.rootId());String stage=input.stage().name().toLowerCase(Locale.ROOT);if(root.generation()!=input.generation()||root.part()<0||root.part()>CanonicalJson.MAX_SAFE_INTEGER||!stage.equals(root.stage()))throw invalid();
  var payload=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",input.rootId()).put("input_hash",input.inputHash()).put("result_hash",result.resultHash()).put("stage",stage).put("generation",input.generation()).put("stage_part",root.part());payload.set("payload",CanonicalJson.parse(result.payloadJson()));
  var old=results.read(input.rootId());if(old!=null){if(!old.isObject()||old.size()!=payload.size()+1||!old.has("members"))throw invalid();var base=((ObjectNode)old).deepCopy();base.remove("members");if(!JdbcFrozenInputs.canonical(payload).equals(JdbcFrozenInputs.canonical(base)))throw invalid();checkFacts(old.get("members"),frozen,entityIds);return;}
  payload.set("members",JsonMapper.shared().valueToTree(apply.get()));checkFacts(payload.get("members"),frozen,entityIds);results.save(input.rootId(),payload,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
 }
 private static void checkFacts(JsonNode facts,Map<String,FrozenMemberBindings.Member> expected,Map<String,String> entityIds){
  if(facts==null||!facts.isArray()||facts.size()!=expected.size())throw invalid();var seen=new HashSet<String>();
  for(var fact:facts){var fields=Set.of("itemId","runId","sourceId","sourceItemId","category","entityId","state","revision","businessDate","targetBusinessDate","generation","activationStage");WireValues.object(fact,fields,fields);
   String id=WireValues.id(fact.get("itemId"),false);var original=expected.get(id);if(original==null||!seen.add(id)||WireValues.nonnegativeInteger(fact.get("revision"))!=original.expectedRevision()+2||!original.category().equals(fact.path("category").asText())||!entityIds.isEmpty()&&!Objects.equals(entityIds.get(id),fact.path("entityId").asText()))throw invalid();}
 }
 private static Rejected invalid(){return new Rejected();}
}
