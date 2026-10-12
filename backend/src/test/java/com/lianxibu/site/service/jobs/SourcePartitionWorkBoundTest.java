package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourcePartitionWorkBoundTest {
    @Test void completeSmallMembersNeedOneBuildPerFullPartRatherThanOnePerMember(){
        var u=FrozenSourceUniverseTest.universe(501,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,u).reference();var calls=new AtomicInteger();var plan=new SourcePartitionPlanner().plan(SourcePartitionPlannerTest.STAGE,SourcePartitionPlannerTest.KEY,1,u,ref,SourcePartitionPlannerTest.decisions(u),(ids,part,root)->{calls.incrementAndGet();return SourcePartitionPlannerTest.snapshot(ref,ids,part,root,"Fictional complete body");});assertEquals(List.of(100,100,51),plan.chunks().stream().map(c->c.itemIds().size()).toList());assertEquals(3,calls.get());new SourcePartitionPlanner().validate(plan,u);
    }
    @Test void oversizedTrialPrefixCanBeSubdividedWithFullEscapedOriginalFields(){
        var u=FrozenSourceUniverseTest.universe(301,false);var ref=FrozenSourceUniverse.encode(FrozenSourceUniverseTest.OWNER,u).reference();var calls=new AtomicInteger();String text="\"".repeat(20000);var plan=new SourcePartitionPlanner().plan(SourcePartitionPlannerTest.STAGE,SourcePartitionPlannerTest.KEY,1,u,ref,SourcePartitionPlannerTest.decisions(u),(ids,part,root)->{calls.incrementAndGet();return SourcePartitionPlannerTest.snapshot(ref,ids,part,root,text);});
        assertEquals(u.members().stream().filter(m->m.terminalState().equals("accepted")).map(m->m.itemId()).sorted().toList(),plan.chunks().stream().flatMap(c->c.itemIds().stream()).toList());assertTrue(calls.get()<151);assertTrue(plan.chunks().size()>1);for(var c:plan.chunks()){assertTrue(c.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1048576);assertEquals(text,com.lianxibu.site.domain.protocol.CanonicalJson.parse(c.snapshotJson()).get("members").get(0).get("body").get("text").asText());}new SourcePartitionPlanner().validate(plan,u);
    }
}
