package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Pure fictional planning only, never an opaque SQL business proof or model call. */
class SourcePartialCapacityPlanTest {
    @Test void completeTenThousandMemberPlanCanHaveMoreThanOneHundredAdaptiveRoots(){
        var original=FrozenSourceUniverseTest.universe(10000,false);var members=original.members().stream().map(m->new MemberUniverse.Member(m.itemId(),m.memberId(),m.sourceItemId(),m.observationId(),m.payloadHash(),m.replacesObservationId(),m.partNo(),"accepted")).toList();var universe=new MemberUniverse.Universe(original.runId(),original.sourceId(),original.manifestHash(),original.parts(),members,10000,0);
        var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,universe).reference();var large=Set.copyOf(members.stream().map(MemberUniverse.Member::itemId).sorted().limit(6).toList());
        var plan=new SourcePartitionPlanner().plan(GenerationFlowService.Stage.NEWS_DEDUP,SourcePartitionPlannerTest.KEY,1,universe,ref,SourcePartitionPlannerTest.decisions(universe),(ids,n,root)->{
            var snapshot=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional small input"));for(var member:snapshot.get("members"))if(large.contains(member.get("id").asText())){var body=(ObjectNode)member.get("body");body.put("text","\"".repeat(40000));((ObjectNode)member).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));}return SourceDedupFragmentProofTest.canonical(snapshot);
        });
        assertTrue(plan.chunks().size()>100);assertEquals(10000,plan.chunks().stream().mapToInt(c->c.itemIds().size()).sum());assertEquals(10000,plan.decisions().size());assertEquals(members.stream().map(MemberUniverse.Member::itemId).sorted().toList(),plan.chunks().stream().flatMap(c->c.itemIds().stream()).toList());new SourcePartitionPlanner().validate(plan,universe);assertNotNull(FrozenSourceStagePlan.encode(plan,universe));
    }
}
