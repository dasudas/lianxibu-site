package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CollectionGenerationInputTest {
    CollectionHandoffIntegrationTest fixture;
    CollectionHandoffProofService proofs;
    final FrozenGenerationSettings settings=new FrozenGenerationSettings(1,List.of(),List.of());
    int requests;
    CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationTest();}
    @BeforeEach void setup(){fixture=fixture();fixture.setup();}
    CollectionHandoffProofService.Proof handoff(){
        fixture.handoff();
        proofs=new CollectionHandoffProofService(fixture.f.source,new CollectionHandoffService(new JdbcCollectionHandoff(fixture.f.source),f->{throw new AssertionError();}));
        return fixture.f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-read","fictional-run",null),k->null,a->proofs.read("fictional-run"));
    }
    Prepare command(CollectionHandoffProofService.Proof p,boolean empty){
        var context=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.NEWS_DEDUP));
        if(empty)for(String name:List.of("item_ids","candidates_by_item","eligible_high_value_item_ids","allowed_citation_ids","version_eligible_item_ids","reference_targets"))context.putArray(name);
        return new Prepare(fixture.f.jdbc.queryForObject("SELECT logical_key FROM jobs WHERE id=?",String.class,p.intentId()),
                1,Stage.NEWS_DEDUP,p.facts().businessDate(),p.facts().sourceId(),List.of(),
                empty?List.of():List.of(new Evidence("fictional-item","article_revision",p.facts().businessDate(),fixture.f.clock.instant().minusSeconds(10),
                        CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse("{}")),"{}",true,false,"accepted",List.of())),
                List.of(),false,settings.semanticHash(),empty?null:FrozenApplicationContext.fromJson(context),List.of(p));
    }
    GenerationJdbcStore store(Prepare c){return new GenerationJdbcStore(fixture.f.source,fixture.f.clock,"fictional-owner",FrozenMemberBindingsTest.ROOT,c,proofs::read);}
    GenerationFlowService flow(Prepare c,FrozenStore store,GenerationFlowService.CollectionProofSource source){
        return new GenerationFlowService(fixture.f.writes,store,(stage,snapshot,hash)->{
            requests++;var r=JsonMapper.shared().createObjectNode();r.put("provider","fictional-provider");r.put("model","fictional-model");r.put("input_hash",hash);
            r.putArray("messages").addObject().put("content",snapshot);r.putObject("parameters");r.putObject("response_format");r.put("schema_version","fictional-v1");r.put("prompt_version","fictional-v1");return r.toString();
        },fixture.f.clock,()->true,null,(owner,cmd,cutoff,snapshot)->{
            var bindings=(ObjectNode)FrozenMemberBindingsTest.snapshot().get(FrozenMemberBindings.FIELD);
            if(c.evidence().isEmpty())bindings.putArray("members");
            return JsonMapper.shared().createObjectNode().set(FrozenMemberBindings.FIELD,bindings);
        },()->settings,source);
    }
    SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,FrozenMemberBindingsTest.ROOT,null);}
    @Test void realCollectionQueuesWithoutInventedAiDependencyAndReplaysOriginalInput(){
        var p=handoff();var c=command(p,false);var s=store(c);var f=flow(c,s,proofs::read).prepare(c,action("fictional-prepare"));
        assertEquals("queued",f.state());assertEquals(1,requests);assertEquals(List.of(p),FrozenCollectionManifest.read(CanonicalJson.parse(f.snapshotJson()).get(FrozenCollectionManifest.FIELD)));
        assertEquals(1,fixture.f.count("record_kind='logical_task'"));assertEquals(0,fixture.f.count("purpose='dependency'"));
        assertEquals(f,s.committed("fictional-prepare"));assertEquals(f,flow(c,store(c),run->{throw new AssertionError("replay must use original input");}).prepare(c,action("fictional-prepare")));
        assertEquals(1,requests);assertEquals(p.intentId(),fixture.f.jdbc.queryForObject("SELECT intent_id FROM jobs WHERE record_kind='logical_task'",String.class));
        var changed=new CollectionHandoffProofService.Proof(p.intentId(),p.dispositionId(),"e".repeat(64),p.facts());
        var different=new Prepare(c.logicalKey(),1,c.stage(),c.businessDate(),c.sourceId(),List.of(),c.evidence(),List.of(),false,c.settingsHash(),c.applicationContext(),List.of(changed));
        assertThrows(RuntimeException.class,()->store(different).committed("fictional-prepare"));
    }
    @Test void trialAndWrongSourceDateGenerationOrPayloadCannotBecomeProductionInputs(){
        var p=handoff();var base=command(p,false);int original=fixture.f.count("1=1");
        var forged=new CollectionHandoffProofService.Proof(p.intentId(),p.dispositionId(),"e".repeat(64),p.facts());
        for(var c:List.of(new Prepare(base.logicalKey(),2,base.stage(),base.businessDate(),base.sourceId(),base.dependencies(),base.evidence(),base.expectedDependencies(),false,base.settingsHash(),base.applicationContext(),base.collectionProofs()),
                new Prepare(base.logicalKey(),1,base.stage(),base.businessDate().plusDays(1),base.sourceId(),base.dependencies(),base.evidence(),List.of(),false,base.settingsHash(),base.applicationContext(),base.collectionProofs()),
                new Prepare(base.logicalKey(),1,base.stage(),base.businessDate(),"fictional-wrong",base.dependencies(),base.evidence(),List.of(),false,base.settingsHash(),base.applicationContext(),base.collectionProofs()),
                new Prepare(base.logicalKey(),1,base.stage(),base.businessDate(),base.sourceId(),base.dependencies(),base.evidence(),List.of(),false,base.settingsHash(),base.applicationContext(),List.of(forged)))) {
            assertThrows(RuntimeException.class,()->flow(c,store(c),proofs::read).prepare(c,action("fictional-rejected")));
            assertEquals(original,fixture.f.count("1=1"));
        }
        fixture.f.jdbc.update("DELETE FROM jobs");fixture.f.jdbc.update("UPDATE source_runs SET activation_stage='trial',job_refs_json='[]'");
        var trial=command(handoff(),false);assertThrows(RuntimeException.class,()->flow(trial,store(trial),proofs::read).prepare(trial,action("fictional-trial")));
        assertEquals(0,fixture.f.count("record_kind='logical_task'"));assertEquals(0,requests);
    }
    @Test void missingProviderOrProofCannotUseLegacyAiRootsInProductionPreparation(){
        var p=handoff();var c=command(p,false);
        assertThrows(RuntimeException.class,()->flow(c,store(c),null));
        var absent=new Prepare(c.logicalKey(),1,c.stage(),c.businessDate(),c.sourceId(),List.of(new Dependency("fictional-not-collection",Stage.NEWS_DEDUP,"complete",fixture.f.clock.instant())),c.evidence(),List.of(),false,c.settingsHash(),c.applicationContext());
        assertThrows(RuntimeException.class,()->flow(absent,store(absent),proofs::read).prepare(absent,action("fictional-missing")));
        assertEquals(0,fixture.f.count("record_kind='logical_task'"));
    }
    @Test void persistenceIndependentlyRechecksCollectionProviderAfterRequestConstruction(){
        var p=handoff();var c=command(p,false);int original=fixture.f.count("1=1");
        var fake=new CollectionHandoffProofService.Proof(p.intentId(),p.dispositionId(),"e".repeat(64),p.facts());
        for(var store:List.of(new GenerationJdbcStore(fixture.f.source,fixture.f.clock,"fictional-owner",FrozenMemberBindingsTest.ROOT,c,run->fake),
                new GenerationJdbcStore(fixture.f.source,fixture.f.clock,"fictional-owner",FrozenMemberBindingsTest.ROOT,c))) {
            assertThrows(RuntimeException.class,()->flow(c,store,proofs::read).prepare(c,action("fictional-store-proof")));
            assertEquals(original,fixture.f.count("1=1"));
        }
        assertEquals(2,requests);
    }
    @Test void emptyAndPartialCollectionsKeepProofWithoutChargingAnAiAttempt(){
        for(boolean partial:List.of(false,true)) {
            setup();fixture.f.jdbc.update("UPDATE source_runs SET delivery_state=?,expected_count=?,accepted_count=0,excluded_count=?,fetch_state=?",
                    partial?"no_usable_input":"empty_success",partial?2:0,partial?2:0,partial?"failed":"succeeded");
            var p=handoff();var c=command(p,true);var frozen=flow(c,store(c),proofs::read).prepare(c,action("fictional-empty"));
            assertEquals(partial?"coverage_only":"no_updates",frozen.state());assertEquals(partial?"partial":"complete",frozen.coverageState());
            assertEquals(partial?List.of("collection:fictional-run"):List.of(),frozen.missing());assertNull(frozen.requestJson());
            assertEquals(List.of(p),FrozenCollectionManifest.read(CanonicalJson.parse(frozen.snapshotJson()).get(FrozenCollectionManifest.FIELD)));
            assertEquals(0,fixture.f.count("record_kind='ai_attempt'"));assertEquals(0,requests);
        }
    }
    @Test void rehashedDeletionAndReceiptFailureRollbackAllPreparedRows(){
        var p=handoff();var c=command(p,false);int original=fixture.f.count("1=1");
        var s=store(c);var intercept=new FrozenStore(){public Frozen committed(String key){return null;}public void save(Frozen f,String key){
            var input=(ObjectNode)CanonicalJson.parse(f.snapshotJson());input.remove(FrozenCollectionManifest.FIELD);
            String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);var request=(ObjectNode)CanonicalJson.parse(f.requestJson());request.put("input_hash",hash);
            s.save(new Frozen(f.ownerId(),f.logicalKey(),f.generation(),f.stage(),f.cutoff(),f.coverageState(),f.missing(),JdbcFrozenInputs.canonical(input),hash,JdbcFrozenInputs.canonical(request),f.priority(),f.eligibleAt(),f.state()),key);
        }};
        assertThrows(RuntimeException.class,()->flow(c,intercept,proofs::read).prepare(c,action("fictional-rehash")));assertEquals(original,fixture.f.count("1=1"));
        fixture.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_receipt_failure CHECK(target_type<>'generation_prepare')");
        assertThrows(RuntimeException.class,()->flow(c,s,proofs::read).prepare(c,action("fictional-failed-receipt")));assertEquals(original,fixture.f.count("1=1"));
    }
}
