package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GenerationEnrichmentTest {
 final OperationalPersistenceFixture f=new OperationalPersistenceFixture();
 Frozen saved;int requests;String requestSnapshot;
 final FrozenGenerationSettings settings=new FrozenGenerationSettings(1,List.of(),List.of());
 final CollectionHandoffProofService.Proof collection=new CollectionHandoffProofService.Proof("fictional-intent","fictional-handoff","a".repeat(64),
  new com.lianxibu.site.service.sources.CollectionHandoffService.Facts("fictional-run","fictional-source","daily",LocalDate.of(2026,9,30),1,"news_tech","production",1,"a".repeat(64),"a".repeat(64),"complete","succeeded",1,1,0));
 GenerationFlowService service(GenerationFlowService.InputEnricher enricher){
  return new GenerationFlowService(f.writes,new FrozenStore(){public Frozen committed(String key){return null;}public void save(Frozen v,String key){saved=v;}},(stage,snapshot,hash)->{
   requests++;requestSnapshot=snapshot;assertEquals(hash,CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(snapshot)));
   var r=JsonMapper.shared().createObjectNode();r.put("provider","fictional-provider");r.put("model","fictional-model");r.put("input_hash",hash);r.putArray("messages").addObject().put("content",snapshot);r.putObject("parameters");r.putObject("response_format").put("type","json_object");r.put("schema_version","fictional-v1");r.put("prompt_version","fictional-v1");return r.toString();
  },f.clock,()->true,null,enricher,()->settings,run->collection);
 }
 Prepare command(){return new Prepare(CollectionHandoffProofService.logicalKey(collection.facts()),1,Stage.NEWS_DEDUP,LocalDate.of(2026,9,30),"fictional-source",List.of(),List.of(new Evidence("fictional-item","article_revision",LocalDate.of(2026,9,30),f.clock.instant().minusSeconds(10),"a".repeat(64),"{}",true,false,"accepted",List.of())),List.of(),false,settings.semanticHash(),FrozenApplicationContext.fromJson(FrozenMemberBindingsTest.snapshot().get(FrozenApplicationContext.FIELD)),List.of(collection));}
 Frozen apply(GenerationFlowService s){return s.prepare(command(),new SharedWriteExecutor.ActionContext("fictional-prepare",FrozenMemberBindingsTest.ROOT,null));}
 @Test void extensionIsFrozenBeforeHashRequestAndStorage(){
  var result=apply(service((root,c,cutoff,snapshot)->{assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals(FrozenMemberBindingsTest.ROOT,root);((ObjectNode)snapshot).put("generation",99);return JsonMapper.shared().createObjectNode().set(FrozenMemberBindings.FIELD,FrozenMemberBindingsTest.snapshot().get(FrozenMemberBindings.FIELD));}));
  assertEquals(result,saved);assertEquals(requestSnapshot,result.snapshotJson());assertEquals(1,requests);
  var root=CanonicalJson.parse(result.snapshotJson());assertTrue(root.has(FrozenMemberBindings.FIELD));assertEquals(1,root.get("generation").asInt());assertEquals(result.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,root));assertEquals(result.inputHash(),CanonicalJson.parse(result.requestJson()).get("input_hash").asText());
 }
 @Test void missingUnknownAndMutatedBaseCannotCreateRequest(){
  for(boolean unknown:List.of(false,true)) {var s=service((root,c,cutoff,snapshot)->{((ObjectNode)snapshot).put("generation",99);var extra=JsonMapper.shared().createObjectNode();if(unknown)extra.put("settings_hash","c".repeat(64));return extra;});assertThrows(RuntimeException.class,()->apply(s));assertNull(saved);assertEquals(0,requests);}
 }
}
