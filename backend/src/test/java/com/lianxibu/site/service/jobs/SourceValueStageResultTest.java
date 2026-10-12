package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SourceValueStageResultTest {
    final NewsValueSourcePlanBuilderTest f=new NewsValueSourcePlanBuilderTest();
    SourcePartitionPlanner.Plan plan(NewsValueSourcePlanBuilderTest.Fixture x){return new NewsValueSourcePlanBuilder("2").build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),x.dependency(),1);}
    FrozenSourceStagePlan.Reference ref(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u){return FrozenSourceStagePlan.encode(p,u).reference();}
    List<SourceValueFragmentProof> proofs(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u){return p.chunks().stream().map(c->proof(p,u,c)).toList();}
    ObjectNode payload(SourcePartitionPlanner.Chunk c){
        var payload=JsonMapper.shared().createObjectNode().put("kind","value").put("value_result_version","2").put("summary","Fictional value fragment summary");var rows=payload.putArray("items");payload.putArray("selected_item_ids");
        for(String id:c.itemIds())rows.addObject().put("item_id",id).put("category","model").put("valuable",true).put("high_value",false).put("reason","Fictional ordinary value evidence");return payload;
    }
    SourceValueFragmentProof proof(SourcePartitionPlanner.Plan p,MemberUniverse.Universe u,SourcePartitionPlanner.Chunk c){
        var snapshot=CanonicalJson.parse(c.snapshotJson());var input=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).input(c.rootId(),"fictional-whole-value",c.inputHash(),0);var bindings=FrozenMemberBindings.read(snapshot,input);
        var payload=payload(c);
        String envelope=ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(payload));var result=ResultApplicationService.validate(input,envelope);
        var handoff=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",c.rootId()).put("input_hash",c.inputHash()).put("result_hash",result.resultHash()).put("stage","news_value").put("generation",p.generation()).put("stage_part",c.stagePart());handoff.set("payload",payload);var facts=handoff.putArray("members");
        for(String id:c.itemIds()){
            var original=u.members().stream().filter(m->m.itemId().equals(id)).findFirst().orElseThrow();String entity=null;for(var n:snapshot.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members"))if(n.get("item_id").asText().equals(id))entity=n.get("entity_id").asText();
            facts.add(JsonMapper.shared().valueToTree(new Fact(id,u.runId(),u.sourceId(),original.sourceItemId(),"news_tech",entity,"ready",bindings.get(id).expectedRevision()+2,p.scope().businessDate(),null,p.generation(),"production")));
        }
        return SourceValueFragmentProof.read(p,u,c.rootId(),envelope,JdbcFrozenInputs.canonical(handoff));
    }
    @Test void everyOriginalV2PartAndDecisionIsPresentWithIndependentOrdinaryValue(){
        var x=f.fixture(501);var p=plan(x);var parts=new ArrayList<>(proofs(p,x.universe()));assertTrue(parts.size()>1);var result=SourceValueStageResult.create(p,x.universe(),ref(p,x.universe()),parts);var n=CanonicalJson.parse(result.json());
        assertEquals(501,result.decisions().size());assertEquals(p.decisions().stream().filter(d->d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE).count(),result.members().size());assertEquals(parts.size(),n.get("fragments").size());
        for(var part:n.get("fragments"))for(var item:part.get("payload").get("items")){assertTrue(item.get("valuable").booleanValue());assertFalse(item.get("high_value").booleanValue());assertEquals("model",item.get("category").asText());}
        assertEquals(CanonicalJson.parse(p.scope().stageContextJson()),n.get("stage_context"));assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,n),result.hash());Collections.reverse(parts);assertEquals(result.json(),SourceValueStageResult.create(p,x.universe(),ref(p,x.universe()),parts).json());
        assertThrows(UnsupportedOperationException.class,()->result.members().clear());assertThrows(UnsupportedOperationException.class,()->result.fragments().clear());assertFalse(result.toString().contains("Fictional value fragment summary"));
    }
    @Test void originalZeroAndNoEligibleMembersKeepTheCompleteScopeAndDecisions(){
        for(int count:List.of(0,5)){
            var x=f.fixture(count);var entries=x.selection().entries().stream().map(e->e.reason()==com.lianxibu.site.service.articles.NewsValueEligibilityService.Reason.COLLECTOR_EXCLUDED?e:new com.lianxibu.site.service.articles.NewsValueEligibilityService.Entry(e.itemId(),com.lianxibu.site.service.articles.NewsValueEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null,null,false)).toList();
            var selection=new com.lianxibu.site.service.articles.NewsValueEligibilityService.Selection(x.universe().runId(),x.universe().sourceId(),f.day,f.cutoff,entries);var p=new NewsValueSourcePlanBuilder("2").build(x.universe(),x.ref(),selection,new FrozenGenerationSettings(1,List.of(),List.of()),x.dependency(),1);
            var result=SourceValueStageResult.create(p,x.universe(),ref(p,x.universe()),List.of());assertEquals(count,result.decisions().size());assertTrue(result.members().isEmpty());assertEquals(0,CanonicalJson.parse(result.json()).get("stage_part_count").asInt());
        }
    }
    @Test void originalPartialCollectionRemainsExplicit(){
        var x=f.fixture(5);var p=new NewsValueSourcePlanBuilder("2").build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(1,List.of(),List.of()),x.dependency(),1,true);var n=CanonicalJson.parse(SourceValueStageResult.create(p,x.universe(),ref(p,x.universe()),proofs(p,x.universe())).json());
        assertEquals("partial",n.get("stage_context").get("coverage_state").asText());assertEquals("collection:"+x.universe().runId(),n.get("stage_context").get("missing").get(0).asText());
    }
    @Test void missingExtraDuplicateAndDifferentOriginalInputsCannotBecomeFullCoverage(){
        var x=f.fixture(501);var p=plan(x);var original=proofs(p,x.universe());var reference=ref(p,x.universe());
        for(var bad:List.of(original.subList(0,1),List.of(original.getFirst(),original.getFirst()),List.of(original.getFirst(),original.getLast(),original.getFirst())))assertThrows(RuntimeException.class,()->SourceValueStageResult.create(p,x.universe(),reference,bad));
        var changed=new NewsValueSourcePlanBuilder("2").build(x.universe(),x.ref(),x.selection(),new FrozenGenerationSettings(2,List.of("fictional-change"),List.of()),x.dependency(),1);assertThrows(RuntimeException.class,()->SourceValueStageResult.create(p,x.universe(),reference,proofs(changed,x.universe())));
    }
    @Test void exactWholePlanReferenceStageAndP1ContextAreMandatory(){
        var x=f.fixture(5);var p=plan(x);var reference=ref(p,x.universe());var parts=proofs(p,x.universe());
        assertThrows(RuntimeException.class,()->SourceValueStageResult.create(p,x.universe(),new FrozenSourceStagePlan.Reference(reference.ownerId(),"b".repeat(64),reference.indexHash()),parts));
        var bareScope=new SourcePartitionPlanner.Scope(p.scope().businessDate(),p.scope().cutoff(),p.scope().settings());var bare=new SourcePartitionPlanner.Plan(p.stage(),p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),bareScope);assertThrows(RuntimeException.class,()->SourceValueStageResult.create(bare,x.universe(),reference,parts));
        var wrong=new SourcePartitionPlanner.Plan(GenerationFlowService.Stage.NEWS_DEDUP,p.logicalKey(),p.generation(),p.reference(),p.decisions(),p.chunks(),p.scope());assertThrows(RuntimeException.class,()->SourceValueStageResult.create(wrong,x.universe(),reference,parts));
    }
    @Test void capacityFailureNeverReturnsAPartialResult(){
        var x=f.fixture(5);var p=plan(x);var parts=proofs(p,x.universe());assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceValueStageResult.createWithinCapacity(p,x.universe(),ref(p,x.universe()),parts,2048));assertNotNull(SourceValueStageResult.create(p,x.universe(),ref(p,x.universe()),parts));
    }
}
