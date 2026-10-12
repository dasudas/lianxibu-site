package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.jobs.JdbcOwnerAdHocCreation.Accepted;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Pure construction from an opaque original owner acceptance. */
public final class OwnerAdHocInputBuilder {
 public static final class Prepared {
  private final Accepted accepted;private final Prepare command;private final Frozen frozen;
  private Prepared(Accepted accepted,Prepare command,Frozen frozen){this.accepted=accepted;this.command=command;this.frozen=frozen;}
  public Accepted accepted(){return accepted;}public Prepare command(){return command;}public Frozen frozen(){return frozen;}
  @Override public String toString(){return "PreparedTemporaryInput[redacted]";}
 }
 public Prepared build(Accepted accepted){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("temporary input construction requires no transaction");
  Objects.requireNonNull(accepted);var identity=accepted.command();var cutoff=accepted.cutoff();var day=cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
  var json=JsonMapper.shared();var body=json.createObjectNode().put("prompt",identity.prompt());
  var context=json.createObjectNode().put("schema_version","frozen-application-context-v1").put("stage","ad_hoc_ai").put("generation",1).putNull("target_content").putNull("target_entity_id").putNull("period_start").putNull("period_end").put("references_complete",true);
  for(var field:List.of("item_ids","candidates_by_item","eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))context.putArray(field);
  var application=FrozenApplicationContext.fromJson(context);var evidence=new Evidence(identity.target(),"ad_hoc_request",day,cutoff,CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),JdbcFrozenInputs.canonical(body),true,false,"accepted",List.of());
  var command=new Prepare(identity.logicalKey(),1,Stage.AD_HOC_AI,day,null,List.of(),List.of(evidence),List.of(),false,accepted.settings().semanticHash(),application,List.of(),0);
  var snapshot=json.createObjectNode().put("schema_version","1").put("logical_key",command.logicalKey()).put("generation",1).put("stage","ad_hoc_ai").put("business_date",day.toString()).put("cutoff",cutoff.toString()).put("settings_hash",accepted.settings().semanticHash()).put("coverage_state","complete");snapshot.putArray("missing");
  var member=snapshot.putArray("members").addObject().put("id",evidence.id()).put("kind",evidence.kind()).put("business_date",day.toString()).put("completed_at",cutoff.toString()).put("body_hash",evidence.bodyHash());member.set("body",body);member.putArray("references");
  snapshot.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(),List.of()));snapshot.set(FrozenApplicationContext.FIELD,context);snapshot.set(FrozenGenerationSettings.FIELD,accepted.settings().toJson());
  String input=JdbcFrozenInputs.canonical(snapshot),hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);String request=new GenerationRequestBuilder().build(Stage.AD_HOC_AI,input,hash);
  var frozen=new Frozen(identity.rootId(),identity.logicalKey(),1,Stage.AD_HOC_AI,cutoff,"complete",List.of(),input,hash,request,14,GenerationFlowService.nextEligible(cutoff),"queued");return new Prepared(accepted,command,frozen);
 }
}
