package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class DispatchReadinessServiceTest {
 GenerationJdbcStoreTest f; DispatchReadinessService service; boolean references=true;
 ContentReadService contents; SourceMemberEvidenceService members;
 final FrozenGenerationSettings settings=new FrozenGenerationSettings(1,List.of(),List.of());
 GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
 @BeforeEach void setup(){f=fixture();f.fixture();contents=mock(ContentReadService.class);members=mock(SourceMemberEvidenceService.class);var inputs=new JdbcFrozenInputs(f.source);service=new DispatchReadinessService(new JdbcDispatchInputs(f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"fictional-worker"),contents,members,target->references);}
 Frozen prepare(Stage stage){return prepare(stage,0);}
 Frozen prepare(Stage stage,long part){
  var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(stage)));
  var body=CanonicalJson.parse("{\"title\":\"Fictional original title\"}");
  var command=new Prepare("fictional-dispatch",1,stage,LocalDate.of(2026,1,1),null,List.of(),List.of(new Evidence("fictional-item","analysis",LocalDate.of(2026,1,1),Instant.parse("2026-01-01T20:00:00Z"),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),body.toString(),true,false,"accepted",List.of())),List.of(),true,settings.semanticHash(),context,List.of(),part);
  var store=new GenerationJdbcStore(f.source,f.clock,"fictional-owner",f.OWNER,command);
  return new GenerationFlowService(f.writes,store,(kind,input,hash)->"{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"content\":\"fictional\"}],\"parameters\":{},\"response_format\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",f.clock,()->true,null,(root,c,cutoff,snapshot)->{
   var extension=JsonMapper.shared().createObjectNode();
   if(stage==Stage.NEWS_VALUE)extension.set(FrozenMemberBindings.FIELD,FrozenMemberBindingsTest.snapshot().get(FrozenMemberBindings.FIELD));
   if(stage.analysis()||stage.report())extension.set("public_metadata_v1",CanonicalJson.parse("{\"schema_version\":\"frozen-public-metadata-v1\",\"citations\":[{\"id\":\"fictional-citation\",\"target\":{\"kind\":\"article_revision\",\"id\":\"fictional-citation\"},\"title\":\"Fictional source\",\"source_url\":\"https://example.invalid/story\"}],\"interpretation\":"+(stage.report()?"null":"{\"name\":\"Fictional agent\",\"provider\":null,\"tags\":[],\"description\":null,\"evidence_time\":null,\"metrics\":[],\"stars\":null,\"stars_growth\":null}")+"}"));return extension;
  },()->settings).prepare(command,f.action());
 }
 boolean ready(){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-check","fictional-root",f.HASH),key->null,action->service.ready(f.OWNER));}
 @Test void originalInputIsReadyOnlyInWriterAndReferencesRemainCurrent(){prepare(Stage.AD_HOC_AI);assertThrows(RuntimeException.class,()->service.ready(f.OWNER));assertTrue(ready());references=false;assertFalse(ready());assertEquals(0,f.count("target_type='worker_send'"));}
 @Test void replacementIntentBlocksOldRootBeforeNewBodyExists(){prepare(Stage.AD_HOC_AI);f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-new","fictional-new",f.HASH),key->null,a->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-next-intent","fictional-dispatch",2,"ad_hoc_ai",LocalDate.of(2026,1,1)),f.clock.instant());return true;});assertFalse(ready());}
 @Test void lineageMissingAndRootAliasAreNotAccepted(){prepare(Stage.AD_HOC_AI);assertThrows(RuntimeException.class,()->f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-alias","fictional-root",f.HASH),k->null,a->service.ready("FICTIONAL-root")));f.jdbc.update("DELETE FROM jobs WHERE target_type='generation_lineage'");assertThrows(RuntimeException.class,this::ready);}
 @Test void storedIdentityAndInputHashMustStillAgree(){prepare(Stage.AD_HOC_AI);f.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",f.OWNER);assertThrows(RuntimeException.class,this::ready);}
 @Test void newContentBaselineCannotOverwriteExistingDraft(){prepare(Stage.AGENT_ANALYSIS);when(contents.applicationVersion("agent","fictional-analysis")).thenReturn(Optional.empty());assertTrue(ready());when(contents.applicationVersion("agent","fictional-analysis")).thenReturn(Optional.of(new ContentReadService.CurrentVersion("agent","fictional-analysis",1,1,"fictional-old",new byte[32],null,"draft")));assertFalse(ready());}
 @Test void genericReportCannotPassProductionInputGate(){
  var old=f.command(false);var c=new Prepare(old.logicalKey(),1,old.stage(),old.businessDate(),null,List.of(),old.evidence(),List.of(),true,settings.semanticHash(),FrozenApplicationContext.fromJson(CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.DAILY_REPORT))));
  var persistent=new GenerationJdbcStore(f.source,f.clock,"fictional-owner",f.OWNER,c);
  var legacy=new FrozenStore(){public Frozen committed(String k){return null;}public void save(Frozen v,String k){
   var snapshot=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(v.snapshotJson());snapshot.set(FrozenGenerationSettings.FIELD,settings.toJson());snapshot.set("public_metadata_v1",CanonicalJson.parse("{\"schema_version\":\"frozen-public-metadata-v1\",\"citations\":[{\"id\":\"fictional-citation\",\"target\":{\"kind\":\"article_revision\",\"id\":\"fictional-citation\"},\"title\":\"Fictional source\",\"source_url\":\"https://example.invalid/story\"}],\"interpretation\":null}"));
   var hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot);var request=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(v.requestJson());request.put("input_hash",hash);persistent.save(new Frozen(v.ownerId(),v.logicalKey(),1,v.stage(),v.cutoff(),v.coverageState(),v.missing(),JdbcFrozenInputs.canonical(snapshot),hash,JdbcFrozenInputs.canonical(request),v.priority(),v.eligibleAt(),v.state()),k);
  }};
  f.flow(legacy).prepare(c,f.action());assertThrows(RuntimeException.class,this::ready);
 }
 @Test void nonDedupInputRejectsUnexpectedCollectionManifestEvenAfterRehash(){
  var frozen=prepare(Stage.AD_HOC_AI);
  f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-rehash","fictional-root",f.HASH),k->null,a->{
   var input=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(frozen.snapshotJson());input.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(List.of()));
   f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND purpose='input'",f.OWNER);f.jdbc.update("UPDATE jobs SET input_snapshot_json=NULL WHERE id=?",f.OWNER);
   new JdbcFrozenInputs(f.source).save(f.OWNER,JdbcFrozenInputs.canonical(input),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input),f.clock.instant());return true;
  });assertThrows(RuntimeException.class,this::ready);
 }
 @Test void exactMemberRevisionAndOriginalPayloadRequired(){prepare(Stage.NEWS_VALUE);var fact=new SourceMemberEvidenceService.Fact("fictional-item",2,"news_tech","fictional-canonical-identity","Fictional original title",null,"{\"title\":\"Fictional original title\"}");when(members.read(anySet(),any())).thenReturn(List.of(fact));assertTrue(ready());when(members.read(anySet(),any())).thenReturn(List.of(new SourceMemberEvidenceService.Fact(fact.id(),3,fact.category(),fact.identityKey(),fact.title(),null,fact.payloadJson())));assertFalse(ready());}
 @Test void originalNonzeroPartIsRequiredAlongsideMemberAndLineageProof(){
  prepare(Stage.NEWS_VALUE,1);when(members.read(anySet(),any())).thenReturn(List.of(new SourceMemberEvidenceService.Fact("fictional-item",2,"news_tech","fictional-canonical-identity","Fictional original title",null,"{\"title\":\"Fictional original title\"}")));
  assertTrue(ready());assertEquals(1,new JdbcDispatchInputs(f.source).read(f.OWNER).stagePart());f.jdbc.update("UPDATE jobs SET stage_part=2 WHERE id=?",f.OWNER);assertThrows(RuntimeException.class,this::ready);
 }
 @Test void rehashedChangedOrNonIntegerSnapshotPartCannotMatchOriginalZeroRoot(){
  var frozen=prepare(Stage.AD_HOC_AI);
  for(String value:List.of("1","true","\"0\"")){
   f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-part-rehash-"+value.replace("\"",""),f.OWNER,f.HASH),k->null,a->{
    var input=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(frozen.snapshotJson());input.set("stage_part",CanonicalJson.parse(value));
    f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND purpose='input'",f.OWNER);f.jdbc.update("UPDATE jobs SET input_snapshot_json=NULL WHERE id=?",f.OWNER);
    new JdbcFrozenInputs(f.source).save(f.OWNER,JdbcFrozenInputs.canonical(input),CanonicalJson.hash(CanonicalJson.Domain.INPUT,input),f.clock.instant());return true;
   });assertThrows(RuntimeException.class,this::ready);
  }
 }
}
