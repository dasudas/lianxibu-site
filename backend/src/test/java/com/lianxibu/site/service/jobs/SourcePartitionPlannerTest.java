package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourcePartitionPlannerTest {
    static final String KEY="fictional-source-stage";
    static final Stage STAGE=Stage.NEWS_DEDUP;
    static List<SourcePartitionPlanner.Decision> decisions(MemberUniverse.Universe universe) {
        return universe.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("accepted")?
                SourcePartitionPlanner.Reason.ELIGIBLE:SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED)).toList();
    }
    static String snapshot(FrozenSourceUniverse.Reference ref,List<String> ids,long number,String root,String text) {
        var node=GenerationRequestBuilderTest.snapshot(STAGE);node.put("logical_key",KEY);
        if(number!=0)node.put("stage_part",number);
        node.put("business_date","2026-01-01");node.put("cutoff","2026-01-01T21:00:00Z");node.put("coverage_state","complete");node.putArray("missing");
        node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(),List.of()));
        var context=(ObjectNode)node.get(FrozenApplicationContext.FIELD);var items=context.putArray("item_ids");
        var candidates=context.putArray("candidates_by_item");context.putArray("eligible_high_value_item_ids");
        context.putArray("version_eligible_item_ids");context.putArray("allowed_citation_ids");context.putArray("reference_targets");
        var members=node.putArray("members");var bindings=node.putObject(FrozenMemberBindings.FIELD);bindings.put("schema_version","frozen-member-bindings-v1");var mapping=bindings.putArray("members");
        for(String id:ids) {
            items.add(id);candidates.addObject().put("item_id",id).putArray("entity_ids");
            var member=members.addObject();member.put("id",id);member.put("kind","article_revision");member.put("business_date","2026-01-01");
            member.put("completed_at","2026-01-01T20:00:00Z");member.putArray("references");
            var body=member.putObject("body");body.put("title","Fictional evidence");body.put("text",text);
            member.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));
            String identity="{\"namespace\":\"fictional\",\"key\":\""+id+"\"}";
            var binding=mapping.addObject();binding.put("item_id",id);binding.put("expected_revision",1);binding.put("category","news_tech");
            binding.put("identity_key",identity);binding.put("title","Fictional evidence");binding.putNull("summary");
            binding.put("new_entity_id",FrozenMemberBindings.entityId(root,id,"news_tech",identity));
        }
        var partition=node.putObject(SourcePartitionPlanner.FIELD);partition.put("schema_version","source-partition-v1");
        partition.put("stage_part",number);partition.set("universe_ref",CanonicalJson.parse(ref.json()));
        return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);
    }
    SourcePartitionPlanner.Plan plan(MemberUniverse.Universe universe,String body) {
        var reference=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        return new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,reference,decisions(universe),(ids,n,root)->snapshot(reference,ids,n,root,body));
    }
    @Test void allEligibleMembersAreCoveredOnceInStablePartsIndependentOfDecisionOrder() {
        var universe=FrozenSourceUniverseTest.universe(501,false);var plan=plan(universe,"Fictional small input");
        assertEquals(List.of(100,100,51),plan.chunks().stream().map(c->c.itemIds().size()).toList());
        var included=plan.chunks().stream().flatMap(c->c.itemIds().stream()).toList();
        assertEquals(universe.members().stream().filter(m->m.terminalState().equals("accepted")).map(MemberUniverse.Member::itemId).sorted().toList(),included);
        var shuffled=new ArrayList<>(decisions(universe));Collections.reverse(shuffled);
        var other=new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,plan.reference(),shuffled,(ids,n,root)->snapshot(plan.reference(),ids,n,root,"Fictional small input"));
        assertEquals(plan,other);assertEquals(501,plan.decisions().size());
        assertFalse(plan.toString().contains("Fictional small input"));
        for(int n=0;n<plan.chunks().size();n++){assertEquals(n,plan.chunks().get(n).stagePart());assertEquals(SourcePartitionPlanner.rootId(KEY,1,n),plan.chunks().get(n).rootId());}
    }
    @Test void actualJsonEscapeExpansionSplitsWithoutDroppingTailOrChangingRequests() {
        var universe=FrozenSourceUniverseTest.universe(29,false);var plan=plan(universe,"\"".repeat(40000));
        assertTrue(plan.chunks().size()>1);assertEquals(15,plan.chunks().stream().mapToInt(c->c.itemIds().size()).sum());
        for(var chunk:plan.chunks()) {
            assertTrue(chunk.requestJson().getBytes(StandardCharsets.UTF_8).length<=1048576);
            assertEquals(chunk.requestJson(),new GenerationRequestBuilder().build(STAGE,chunk.snapshotJson(),chunk.inputHash()));
            assertEquals(CanonicalJson.parse(chunk.snapshotJson()),CanonicalJson.parse(CanonicalJson.parse(chunk.requestJson()).path("messages").get(1).path("content").asText()));
        }
    }
    @Test void oneOversizeMemberOrBadSnapshotBlocksTheWholePlan() {
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->plan(FrozenSourceUniverseTest.universe(3,false),"\"".repeat(300000)));
        var universe=FrozenSourceUniverseTest.universe(3,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        for(String mode:List.of("part","ref","member","context","hash","generation"))assertThrows(RuntimeException.class,()->new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,ref,decisions(universe),(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(snapshot(ref,ids,n,root,"Fictional evidence"));
            switch(mode){
                case "part"->((ObjectNode)node.get(SourcePartitionPlanner.FIELD)).put("stage_part",n+1);
                case "ref"->((ObjectNode)node.get(SourcePartitionPlanner.FIELD).get("universe_ref")).put("index_hash","b".repeat(64));
                case "member"->((ObjectNode)node.get("members").get(0)).put("id","fictional-wrong-item");
                case "context"->((ObjectNode)node.get(FrozenApplicationContext.FIELD)).putArray("item_ids");
                case "hash"->((ObjectNode)node.get("members").get(0)).put("body_hash","b".repeat(64));
                case "generation"->node.put("generation",2);
            }
            return node.toString();
        }));
    }
    @Test void completeDecisionsAndExactOriginalReferenceAreMandatory() {
        var universe=FrozenSourceUniverseTest.universe(3,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        var valid=decisions(universe);
        for(var bad:List.of(valid.subList(0,2),List.of(valid.getFirst(),valid.getFirst(),valid.getLast()),List.of(valid.getFirst(),new SourcePartitionPlanner.Decision(universe.members().get(1).itemId(),SourcePartitionPlanner.Reason.ELIGIBLE),valid.getLast())))
            assertThrows(RuntimeException.class,()->new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,ref,bad,(ids,n,root)->{throw new AssertionError("must reject before building");}));
        var wrong=new FrozenSourceUniverse.Reference(ref.ownerId(),ref.runId(),"b".repeat(64),ref.indexHash());
        var blocked=List.of(valid.getFirst(),valid.get(1),new SourcePartitionPlanner.Decision(valid.getLast().itemId(),SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED));
        assertThrows(RuntimeException.class,()->new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,ref,blocked,(ids,n,root)->{throw new AssertionError("dedup retains blocked material for registration and counting");}));
        assertThrows(RuntimeException.class,()->new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,wrong,valid,(ids,n,root)->{throw new AssertionError();}));
        assertThrows(RuntimeException.class,()->new SourcePartitionPlanner().plan(Stage.DAILY_REPORT,KEY,1,universe,ref,valid,(ids,n,root)->{throw new AssertionError();}));
    }
    @Test void noEligibleMembersRetainAllExclusionDecisionsWithoutBuildingAnAiCall() {
        var universe=FrozenSourceUniverseTest.universe(3,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();
        var decisions=universe.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList();
        var plan=new SourcePartitionPlanner().plan(STAGE,KEY,1,universe,ref,decisions,(ids,n,root)->{throw new AssertionError();});
        assertTrue(plan.chunks().isEmpty());assertEquals(3,plan.decisions().size());
        assertTrue(plan(FrozenSourceUniverseTest.universe(0,false),"Fictional").chunks().isEmpty());
    }
}
