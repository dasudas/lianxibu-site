package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import com.lianxibu.site.service.articles.ValueApplicationService.Value;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ValueClassificationConsumerTest {
    ValueClassificationWriteTest base;NewsValueSourcePlanPreparationTest f;SourcePartitionPlanner.Plan plan;
    ResultApplicationService.Input input;ValueResultHandler handler;MemberResultDownstream downstream;List<Value> values;JsonNode payload;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    void beforePrepare(){ }
    @BeforeEach void setup() throws Exception {
        base=new ValueClassificationWriteTest(){@Override GenerationJdbcStoreTest fixture(){return ValueClassificationConsumerTest.this.fixture();}};base.setup();f=base.f;
        f.f.jdbc.execute("ALTER TABLE source_run_items ADD analysis_job_id VARCHAR(64)");f.f.jdbc.execute("ALTER TABLE source_run_items ADD applied_at TIMESTAMP(6)");
        beforePrepare();plan=f.prepare();var frozen=f.p1.tx(()->new SourceStagePreparationService(f.f.source,f.plans,f.p1.universes,f.p1.collections::read,f.clock,"fictional-owner").preparePart(f.intent,0,"fictional-value-consumer-part"));
        input=FrozenApplicationContext.fromJson(CanonicalJson.parse(frozen.snapshotJson()).get(FrozenApplicationContext.FIELD)).input(frozen.ownerId(),"fictional-value-consumer",frozen.inputHash(),0);
        downstream=new MemberResultDownstream(new JdbcFrozenInputs(f.f.source),new JdbcMemberStageResults(f.f.source),new SourceMemberStageService(new JdbcSourceMemberStages(f.f.source),f.clock),f.clock);
        handler=handler(f.dependencies);payload=payload("agent");
    }
    ValueResultHandler handler(SourceDedupDependencyService proof){return new ValueResultHandler(base.service,new JdbcFrozenInputs(f.f.source)::read,(i,r,v)->{values=v;downstream.afterApplied(i,r,v);},proof);}
    JsonNode payload(String category){
        var p=JsonMapper.shared().createObjectNode().put("kind","value").put("summary","Fictional category evidence");p.putArray("selected_item_ids");p.putArray("items").addObject().put("item_id",base.item).put("category",category).put("high_value",false).put("reason","Fictional classification evidence");return p;
    }
    ResultApplicationService.Validated result(){return ResultApplicationService.validate(input,ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(payload)));}
    ResultApplicationService.Applied apply(){var result=result();return f.p1.tx(()->handler.apply(input,result,new ResultApplicationService.Current(1,input.inputHash(),1,null,true,false,null,result.resultHash(),input.attemptId(),true,null),false));}
    int receipts(){return f.f.count("target_type='source_member_stage' AND target_key_json LIKE '%"+input.rootId()+"%'");}
    void empty(){base.empty();assertEquals(0,receipts());assertNull(f.f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id=?",String.class,base.item));}
    @Test void everyClassificationProducesValueAndPermanentFactsWithOriginalNativeEntity() throws Exception {
        boolean first=true;for(String category:List.of("news_tech","agent","skill","workflow","model")){
            if(!first)setup();first=false;payload=payload(category);assertEquals(new ResultApplicationService.Applied(null,null),apply());
            var decision=CanonicalJson.parse(f.f.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,base.item));assertEquals(category,decision.get("category").asText());assertEquals("member-value-v2",decision.get("schema_version").asText());
            var receipt=f.p1.tx(()->new JdbcMemberStageResults(f.f.source).read(input.rootId()));assertEquals("news_tech",receipt.get("members").get(0).get("category").asText());assertEquals(base.entity,receipt.get("members").get(0).get("entityId").asText());assertEquals(base.revision+2,receipt.get("members").get(0).get("revision").asLong());assertEquals(category,receipt.get("payload").get("items").get(0).get("category").asText());
            assertEquals(2,f.f.jdbc.queryForObject("SELECT occurrence_count FROM analyzed_entities WHERE id=?",Integer.class,base.entity));
        }
    }
    @Test void wrongCurrentEntityAndMissingNativeObservationRejectBeforeAnyValueWrite(){
        f.f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-other-entity' WHERE id=?",base.item);assertThrows(RuntimeException.class,this::apply);empty();f.f.jdbc.update("UPDATE source_run_items SET entity_id=? WHERE id=?",base.entity,base.item);
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::apply);empty();
    }
    @Test void declaringCompleteInputWithoutTheActualDependencyProviderIsRejected(){handler=handler(null);assertThrows(RuntimeException.class,this::apply);empty();}
    @Test void aFailedPermanentReceiptRollsBackValueAndMemberLocators(){
        String receiptId=JdbcFrozenInputs.stableId("member-stage","member-stage:"+input.rootId());f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_value_receipt_failure CHECK(id<>'"+receiptId+"')");assertThrows(RuntimeException.class,this::apply);empty();
    }
    @Test void originalPermanentFactsReplayWithoutCurrentRebuildAndRehashedEntityTamperIsRejected(){
        apply();f.f.jdbc.update("UPDATE source_run_items SET revision=20,analysis_job_id='fictional-later' WHERE id=?",base.item);var result=result();int count=f.f.count("1=1");f.p1.tx(()->{downstream.afterApplied(input,result,values);return true;});assertEquals(count,f.f.count("1=1"));assertEquals(20,f.f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Integer.class,base.item));
        var receipt=f.p1.tx(()->new JdbcMemberStageResults(f.f.source).read(input.rootId()));((tools.jackson.databind.node.ObjectNode)receipt.get("members").get(0)).put("entityId","fictional-swapped-entity");String raw=JdbcFrozenInputs.canonical(receipt);String id=JdbcFrozenInputs.stableId("member-stage","member-stage:"+input.rootId());
        f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,()->f.p1.tx(()->{downstream.afterApplied(input,result,values);return true;}));assertEquals(count,f.f.count("1=1"));
    }
}
