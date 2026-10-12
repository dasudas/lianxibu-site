package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.sources.SourceMemberStageService.Fact;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class CatalogSourceDedupFragmentProofTest {
    CatalogSourcePlanBuilderTest fixture(String category,int count){var f=new CatalogSourcePlanBuilderTest();f.category=category;f.fixture(count,"Fictional retained catalog document");return f;}
    ResultApplicationService.Input input(SourcePartitionPlanner.Chunk c){return FrozenApplicationContext.fromJson(CanonicalJson.parse(c.snapshotJson()).get(FrozenApplicationContext.FIELD)).input(c.rootId(),"fictional-proof",c.inputHash(),0);}
    ObjectNode payload(SourcePartitionPlanner.Chunk c,String category){
        var n=JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","Fictional catalog result");var items=n.putArray("items");int index=0;
        for(String id:c.itemIds()){String choice=List.of("new","existing","needs_review").get(index++%3);var row=items.addObject().put("item_id",id).put("category",category).put("decision",choice);
            if(choice.equals("existing"))row.put("candidate_entity_id","fictional-candidate");else row.putNull("candidate_entity_id");
            row.put("reason","Fictional source proof").put("confidence","1").put("suggested_title","Fictional catalog title").putNull("suggested_summary").put("reanalyze_recommended",false);
        }return n;
    }
    String envelope(SourcePartitionPlanner.Chunk c,String category){return ResultApplicationService.providerEnvelope(input(c),canonical(payload(c,category)));}
    ObjectNode handoff(CatalogSourcePlanBuilderTest f,SourcePartitionPlanner.Plan p,SourcePartitionPlanner.Chunk c){
        var result=ResultApplicationService.validate(input(c),envelope(c,f.category));var n=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",c.rootId()).put("input_hash",c.inputHash()).put("result_hash",result.resultHash()).put("stage",p.stage().name().toLowerCase(Locale.ROOT)).put("generation",p.generation()).put("stage_part",c.stagePart());
        n.set("payload",CanonicalJson.parse(result.payloadJson()));var rows=n.putArray("members");int index=0;
        for(String id:c.itemIds()){
            var original=f.original.members().stream().filter(m->m.itemId().equals(id)).findFirst().orElseThrow();int choice=index++%3;String entity=choice==2?null:choice==1?"fictional-candidate":"fictional-created-"+id;
            rows.add(JsonMapper.shared().valueToTree(new Fact(id,f.original.runId(),f.original.sourceId(),original.sourceItemId(),f.category,entity,choice==2?"needs_review":"ready",4,p.scope().businessDate(),null,p.generation(),"production")));
        }return n;
    }
    SourceDedupFragmentProof read(CatalogSourcePlanBuilderTest f,SourcePartitionPlanner.Plan p,SourcePartitionPlanner.Chunk c,ObjectNode n){return SourceDedupFragmentProof.read(p,f.original,c.rootId(),envelope(c,f.category),canonical(n));}
    @Test void allFourCatalogCategoriesBindOriginalDecisionsAndEveryNativeFact(){
        for(String category:List.of("agent","skill","workflow","model")){var f=fixture(category,5);var p=f.build();var c=p.chunks().getFirst();var n=handoff(f,p,c);var proof=read(f,p,c,n);
            assertEquals(c.itemIds(),proof.members().stream().map(Fact::itemId).toList());assertTrue(proof.members().stream().allMatch(m->category.equals(m.category())));
            assertEquals("fictional-candidate",proof.members().get(1).entityId());assertEquals("needs_review",proof.members().get(2).state());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,n),proof.handoffHash());
            assertThrows(UnsupportedOperationException.class,()->proof.members().clear());
        }
    }
    @Test void otherCategoryStageOriginalRevisionAndUnknownFieldsCannotGrantProof(){
        for(String category:List.of("agent","skill","workflow","model")){var f=fixture(category,5);var p=f.build();var c=p.chunks().getFirst();
            for(String mode:List.of("stage","category","revision","extra","duplicate","missing","result_hash","entityId")){
                var n=handoff(f,p,c);var row=(ObjectNode)n.get("members").get(1);
                switch(mode){case "stage"->n.put("stage",category.equals("agent")?"workflow_dedup":"agent_dedup");case "category"->row.put(mode,category.equals("agent")?"workflow":"agent");case "revision"->row.put(mode,5);case "extra"->n.put("extra",true);case "duplicate"->((tools.jackson.databind.node.ArrayNode)n.get("members")).set(1,n.get("members").get(0).deepCopy());case "missing"->((tools.jackson.databind.node.ArrayNode)n.get("members")).remove(0);case "result_hash"->n.put(mode,"0".repeat(64));case "entityId"->row.put(mode,"fictional-other");}
                assertThrows(RuntimeException.class,()->read(f,p,c,n),category+":"+mode);
            }
        }
    }
    @Test void completeOriginal203MembersBindEachDistinctChunkAndNeverSubstituteAnother(){
        var f=fixture("skill",203);var p=f.build();assertEquals(List.of(100,2),p.chunks().stream().map(c->c.itemIds().size()).toList());var first=p.chunks().getFirst();var last=p.chunks().getLast();
        assertEquals(102,p.chunks().stream().mapToInt(c->read(f,p,c,handoff(f,p,c)).members().size()).sum());
        assertThrows(RuntimeException.class,()->SourceDedupFragmentProof.read(p,f.original,last.rootId(),envelope(first,f.category),canonical(handoff(f,p,first))));
        var n=handoff(f,p,last);var nodes=new ArrayList<JsonNode>();n.get("members").forEach(nodes::add);Collections.reverse(nodes);var rows=n.putArray("members");nodes.forEach(rows::add);assertEquals(last.itemIds(),read(f,p,last,n).members().stream().map(Fact::itemId).toList());
    }
    @Test void originalP1ResultBytesAndHandoffHashRemainExact(){
        var old=new SourceDedupFragmentProofTest();var p=old.plan(old.universe);var c=p.chunks().getFirst();var n=old.handoff(p,old.universe,c);var actual=old.read(p,old.universe,c,n);
        assertEquals("a8630fda567e97bfea32b895cc71cc8ec80a1833550961c9c4d03e72d6b47169",actual.result().resultHash());assertEquals("8a190cc68edd13b336fb922e452e5504e0cd67cbe5b515752689d93fa6175349",actual.handoffHash());assertTrue(actual.members().stream().allMatch(m->"news_tech".equals(m.category())));
    }
    static String canonical(JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}
}
