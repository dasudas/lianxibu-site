package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NewsValueInputProfileTest {
    static void installMaterialView(ObjectNode snapshot,String sourceId){
        long generation=snapshot.get("generation").asLong(),part=snapshot.has("stage_part")?snapshot.get("stage_part").asLong():0;String root=SourcePartitionPlanner.rootId(snapshot.get("logical_key").asText(),generation,part);
        var facts=new ArrayList<SourceMemberEvidenceService.Fact>();var bindings=new HashMap<String,ObjectNode>();for(var value:snapshot.get(FrozenMemberBindings.FIELD).get("members"))bindings.put(value.get("item_id").asText(),(ObjectNode)value);
        for(var value:snapshot.get("members")){var item=(ObjectNode)value;String id=item.get("id").asText();var binding=bindings.get(id);var old=item.get("body");var body=old.has("body_text")?(ObjectNode)old.deepCopy():(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);
            if(!old.has("body_text")){body.put("title",binding.get("title").asText());body.put("source_url","https://example.invalid/"+id);body.set("source_summary",binding.get("summary").deepCopy());body.put("body_text",old.path("text").asText("Fictional evidence"));}
            var identity=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.set("key",body.get("source_url"));String key=JdbcFrozenInputs.canonical(identity);binding.put("identity_key",key);binding.put("new_entity_id",FrozenMemberBindings.entityId(root,id,"news_tech",key));
            item.put("kind","source_member");item.set("body",body);item.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));facts.add(new SourceMemberEvidenceService.Fact(id,binding.get("expected_revision").asLong(),"news_tech",key,binding.get("title").asText(),binding.get("summary").isNull()?null:binding.get("summary").asText(),JdbcFrozenInputs.canonical(body)));
        }
        snapshot.put("source_id",sourceId);snapshot.set(NewsValueInputProfile.FIELD,NewsValueInputProfile.create(sourceId,facts));
    }
    static ObjectNode snapshot(String summary,String body){
        var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,FrozenSourceUniverseTest.universe(1,false)).reference();
        String root=SourcePartitionPlanner.rootId(SourcePartitionPlannerTest.KEY,1,0);
        var snapshot=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,List.of("fictional-item"),0,root,"Fictional original"));snapshot.put("stage","news_value");snapshot.put("source_id","fictional-source");((ObjectNode)snapshot.get(FrozenApplicationContext.FIELD)).put("stage","news_value");
        var payload=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);payload.put("title","Fictional evidence");if(summary==null)payload.putNull("source_summary");else payload.put("source_summary",summary);if(body==null)payload.putNull("body_text");else payload.put("body_text",body);
        var identity=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.put("key","https://example.invalid/story");String key=JdbcFrozenInputs.canonical(identity);
        var binding=(ObjectNode)snapshot.get(FrozenMemberBindings.FIELD).get("members").get(0);binding.put("identity_key",key);if(summary==null)binding.putNull("summary");else binding.put("summary",summary);binding.put("new_entity_id",FrozenMemberBindings.entityId(root,"fictional-item","news_tech",key));
        var member=(ObjectNode)snapshot.get("members").get(0);member.put("kind","source_member");member.set("body",payload);member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload));
        var fact=new SourceMemberEvidenceService.Fact("fictional-item",1,"news_tech",key,"Fictional evidence",summary,JdbcFrozenInputs.canonical(payload));
        snapshot.set(NewsValueInputProfile.FIELD,NewsValueInputProfile.create("fictional-source",List.of(fact)));return snapshot;
    }
    @Test void verifiedViewPreservesOriginalHashAndEvidenceButExcludesBodyTail(){
        var input=snapshot(null,"😀".repeat(800)+"Fictional forbidden body tail");String original=JdbcFrozenInputs.canonical(input);var view=NewsValueInputProfile.modelView(input);
        assertEquals(original,JdbcFrozenInputs.canonical(input));assertEquals("news-value-material-view-v1",view.get("model_input_view").asText());assertFalse(view.get("members").get(0).has("body"));assertFalse(JdbcFrozenInputs.canonical(view).contains("forbidden body tail"));
        assertEquals(input.get("members").get(0).get("body_hash"),view.get("members").get(0).get("body_hash"));assertEquals("😀".repeat(800),view.get(NewsValueInputProfile.FIELD).get("materials").get(0).get("text").asText());
    }
    @Test void longSummaryAndExplicitMissingMaterialRemainWithoutJoiningBody(){
        String summary="Fictional long "+"x".repeat(2400);var view=NewsValueInputProfile.modelView(snapshot(summary,"Fictional hidden body"));assertFalse(JdbcFrozenInputs.canonical(view).contains("hidden body"));assertEquals(summary,view.get(NewsValueInputProfile.FIELD).get("materials").get(0).get("text").asText());
        var missing=NewsValueInputProfile.modelView(snapshot(null,null));assertTrue(missing.get(NewsValueInputProfile.FIELD).get("materials").get(0).get("text").isNull());
    }
    @Test void materialBodyHashMemberBindingAndSourceTamperingReject(){
        for(String change:List.of("material","hash","body","revision","id","binding","source","stage","part","extra-profile","extra-input","missing-profile")){
            var input=snapshot(null,"Fictional original body");var material=(ObjectNode)input.get(NewsValueInputProfile.FIELD).get("materials").get(0);
            switch(change){case "material"->material.put("text","Fictional changed view");case "hash"->material.put("original_body_hash","b".repeat(64));case "body"->((ObjectNode)input.get("members").get(0)).put("body_hash","b".repeat(64));case "revision"->material.put("expected_revision",2);case "id"->material.put("item_id","fictional-other-item");case "binding"->((ObjectNode)input.get(FrozenMemberBindings.FIELD).get("members").get(0)).put("title","Fictional changed title");case "source"->input.put("source_id","fictional-other-source");case "stage"->input.put("stage","news_dedup");case "part"->input.put("stage_part",1);case "extra-profile"->((ObjectNode)input.get(NewsValueInputProfile.FIELD)).put("unknown",true);case "extra-input"->input.put("unreviewed_full_body","Fictional hidden raw body");case "missing-profile"->input.remove(NewsValueInputProfile.FIELD);}
            assertThrows(RuntimeException.class,()->NewsValueInputProfile.modelView(input));
        }
    }
    @Test void createRequiresAnExactNonemptyBoundedUniqueFactSet(){
        var input=snapshot("Fictional summary","Fictional body");var binding=input.get(FrozenMemberBindings.FIELD).get("members").get(0);var fact=new SourceMemberEvidenceService.Fact("fictional-item",1,"news_tech",binding.get("identity_key").asText(),"Fictional evidence","Fictional summary",JdbcFrozenInputs.canonical(input.get("members").get(0).get("body")));
        assertThrows(RuntimeException.class,()->NewsValueInputProfile.create("fictional-source",List.of()));assertThrows(RuntimeException.class,()->NewsValueInputProfile.create("fictional-source",List.of(fact,fact)));
        assertThrows(RuntimeException.class,()->NewsValueInputProfile.create("fictional-source",Collections.nCopies(101,fact)));
        var profile=(ObjectNode)input.get(NewsValueInputProfile.FIELD);profile.putArray("materials");assertThrows(RuntimeException.class,()->NewsValueInputProfile.validated(input));
    }
}
