package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.SourceDedupBarrier.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupBarrierTest {
    private static final LocalDate DAY=LocalDate.of(2026,9,20);
    private static final Instant REGISTERED=Instant.parse("2026-09-20T16:10:00.000001Z");
    private static final Instant EARLY=Instant.parse("2026-09-20T18:00:00.000001Z");
    private static final String HASH="a".repeat(64),PROOF="b".repeat(64);
    private static Member enabled(String id){return new Member(id,Inclusion.ENABLED,3L,HASH,"fictional-run-"+id,2L);}
    private static Cohort cohort(){return SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,List.of(enabled("fictional-a"),enabled("fictional-b"),
            new Member("fictional-disabled",Inclusion.DISABLED,3L,HASH,null,null),new Member("fictional-missing-config",Inclusion.UNCONFIGURED,null,null,null,null)));}
    private static Progress fact(String id,State state,Instant available){return new Progress("news_tech",DAY,id,3,HASH,"fictional-run-"+id,2,state,available,PROOF);}
    private static String hash(String json){return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,CanonicalJson.parse(json));}

    @Test void wholeCohortWaitsForEveryEnabledSourceThenClosesEarlyWithExplicitFailure() {
        var cohort=cohort();var a=fact("fictional-a",State.COMPLETE,EARLY.minusSeconds(1));
        assertNull(SourceDedupBarrier.close(cohort,EARLY,List.of(a)));
        var closed=SourceDedupBarrier.close(cohort,EARLY,List.of(a,fact("fictional-b",State.FAILED,EARLY)));
        assertTrue(closed.early());assertEquals(EARLY,closed.cutoff());assertEquals(4,closed.entries().size());
        assertEquals(List.of(Decision.INCLUDED,Decision.MISSING,Decision.EXCLUDED,Decision.EXCLUDED),closed.entries().stream().map(Entry::decision).toList());
        assertEquals(List.of("complete","failed","disabled","unconfigured"),closed.entries().stream().map(Entry::reason).toList());
        assertThrows(UnsupportedOperationException.class,()->closed.entries().clear());
        assertTrue(cohort.toString().contains("redacted"));assertTrue(a.toString().contains("redacted"));assertTrue(closed.toString().contains("redacted"));
    }
    @Test void fixedShanghaiDeadlineIncludesExactBoundaryAndExcludesOneMicrosecondLate() {
        Instant deadline=SourceDedupBarrier.deadline(DAY);assertEquals(Instant.parse("2026-09-20T20:30:00Z"),deadline);
        var closed=SourceDedupBarrier.close(cohort(),deadline.plusSeconds(100),List.of(fact("fictional-a",State.PARTIAL,deadline),fact("fictional-b",State.COMPLETE,deadline.plusNanos(1000))));
        assertFalse(closed.early());assertEquals(deadline,closed.cutoff());assertEquals(Decision.INCLUDED,closed.entries().getFirst().decision());
        assertEquals("partial",closed.entries().getFirst().reason());assertEquals("late",closed.entries().get(1).reason());assertEquals(Decision.MISSING,closed.entries().get(1).decision());
        var missing=SourceDedupBarrier.close(cohort(),deadline,List.of());assertEquals("not_completed",missing.entries().getFirst().reason());
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.close(null,deadline,List.of()));
    }
    @Test void unknownsStayPendingBeforeDeadlineAndNeverBecomeSuccessAfterDeadline() {
        for(var state:List.of(State.WAITING,State.UNKNOWN_EXTERNAL,State.BLOCKED_CONFIGURATION,State.RESULT_CONFLICT,State.CANCEL_REQUESTED)){
            var facts=List.of(fact("fictional-a",State.COMPLETE,EARLY),fact("fictional-b",state,EARLY));
            assertNull(SourceDedupBarrier.close(cohort(),EARLY,facts));
            var closed=SourceDedupBarrier.close(cohort(),SourceDedupBarrier.deadline(DAY),facts);
            assertEquals(Decision.MISSING,closed.entries().get(1).decision());assertEquals(state.name().toLowerCase(Locale.ROOT),closed.entries().get(1).reason());
        }
        for(var state:List.of(State.EMPTY_SUCCESS,State.CANCELLED,State.RETRY_EXHAUSTED,State.NEEDS_REVIEW)){
            var closed=SourceDedupBarrier.close(cohort(),EARLY,List.of(fact("fictional-a",State.COMPLETE,EARLY),fact("fictional-b",state,EARLY)));
            assertNotNull(closed);assertEquals(state==State.EMPTY_SUCCESS?Decision.INCLUDED:Decision.MISSING,closed.entries().get(1).decision());
        }
    }
    @Test void bindsOriginalSourceRunGenerationConfigurationCategoryAndBusinessDay() {
        var original=fact("fictional-a",State.COMPLETE,EARLY);
        var wrong=List.of(new Progress("agent",DAY,original.sourceId(),3,HASH,original.runId(),2,State.COMPLETE,EARLY,PROOF),
                new Progress("news_tech",DAY.minusDays(1),original.sourceId(),3,HASH,original.runId(),2,State.COMPLETE,EARLY,PROOF),
                new Progress("news_tech",DAY,original.sourceId(),4,HASH,original.runId(),2,State.COMPLETE,EARLY,PROOF),
                new Progress("news_tech",DAY,original.sourceId(),3,"c".repeat(64),original.runId(),2,State.COMPLETE,EARLY,PROOF),
                new Progress("news_tech",DAY,original.sourceId(),3,HASH,"fictional-other-run",2,State.COMPLETE,EARLY,PROOF),
                new Progress("news_tech",DAY,original.sourceId(),3,HASH,original.runId(),3,State.COMPLETE,EARLY,PROOF),
                fact("fictional-disabled",State.COMPLETE,EARLY),fact("fictional-not-in-cohort",State.COMPLETE,EARLY),
                fact("fictional-a",State.COMPLETE,REGISTERED.minusNanos(1000)),fact("fictional-a",State.COMPLETE,EARLY.plusNanos(1000)));
        for(var value:wrong)assertThrows(RuntimeException.class,()->SourceDedupBarrier.close(cohort(),EARLY,List.of(value)));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.close(cohort(),EARLY,List.of(original,original)));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.close(cohort(),REGISTERED.minusNanos(1000),List.of()));
    }
    @Test void immutableCanonicalCohortAndClosureRoundTripRejectAliasesAndRecomputedCorruption() {
        var cohort=cohort();var reversed=new ArrayList<>(cohort.members());Collections.reverse(reversed);
        assertEquals(cohort.json(),SourceDedupBarrier.freeze("news_tech",DAY,REGISTERED,reversed).json());
        var reread=SourceDedupBarrier.readCohort(cohort.json(),cohort.hash());assertEquals(cohort.hash(),reread.hash());
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.readCohort(cohort.json(),"d".repeat(64)));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.readCohort(" "+cohort.json(),cohort.hash()));
        var extra=(ObjectNode)CanonicalJson.parse(cohort.json());extra.put("extra",true);String bad=new String(CanonicalJson.bytes(extra),java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.readCohort(bad,hash(bad)));
        var closed=SourceDedupBarrier.close(cohort,EARLY,List.of(fact("fictional-a",State.COMPLETE,EARLY),fact("fictional-b",State.EMPTY_SUCCESS,EARLY)));
        assertEquals(closed.json(),SourceDedupBarrier.readClosure(closed.json(),closed.hash(),reread).json());
        for(String change:List.of("cohort_hash","decision","cutoff","source_id","unknown_state")){
            var node=(ObjectNode)CanonicalJson.parse(closed.json());var entry=(ObjectNode)node.get("sources").get(0);
            switch(change){case "cohort_hash"->node.put("cohort_hash","e".repeat(64));case "decision"->entry.put("decision","missing");
                case "cutoff"->node.put("cutoff","2026-09-20T20:30:00.000000Z");case "source_id"->entry.put("source_id","fictional-alias");
                case "unknown_state"->((ObjectNode)entry.get("progress")).put("state","UNKNOWN_EXTERNAL");}
            String modified=new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(RuntimeException.class,()->SourceDedupBarrier.readClosure(modified,hash(modified),cohort));
        }
        var later=SourceDedupBarrier.close(cohort,SourceDedupBarrier.deadline(DAY).plusSeconds(10),List.of());
        assertNotEquals(closed.hash(),later.hash());assertEquals(closed.json(),SourceDedupBarrier.readClosure(closed.json(),closed.hash(),cohort).json());
    }
    @Test void provenEmptySetClosesWithoutPlaceholderAndInvalidShapesOrCapacityRefuse() {
        var empty=SourceDedupBarrier.freeze("skill",DAY,REGISTERED,List.of());
        var closed=SourceDedupBarrier.close(empty,EARLY,List.of());assertTrue(closed.early());assertTrue(closed.entries().isEmpty());
        for(String category:List.of("news_tech","agent","skill","workflow","model"))assertEquals(category,SourceDedupBarrier.freeze(category,DAY,REGISTERED,List.of()).category());
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.freeze("other",DAY,REGISTERED,List.of()));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.freeze("agent",DAY,SourceDedupBarrier.deadline(DAY),List.of()));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.freeze("agent",DAY,REGISTERED.plusNanos(1),List.of()));
        assertThrows(RuntimeException.class,()->SourceDedupBarrier.freeze("agent",DAY,REGISTERED,List.of(enabled("same"),enabled("same"))));
        assertThrows(RuntimeException.class,()->new Member("fictional",Inclusion.ENABLED,3L,HASH,null,null));
        assertThrows(RuntimeException.class,()->new Member("fictional",Inclusion.UNCONFIGURED,3L,HASH,null,null));
        assertThrows(RuntimeException.class,()->new Member("fictional",Inclusion.ENABLED,3L,HASH,"fictional-run",0L));
        var tooMany=new ArrayList<Member>();for(int i=0;i<10001;i++)tooMany.add(new Member("fictional-"+i,Inclusion.UNCONFIGURED,null,null,null,null));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupBarrier.freeze("model",DAY,REGISTERED,tooMany));
        var tooLarge=new ArrayList<Member>();for(int i=0;i<2500;i++){String suffix=String.format(Locale.ROOT,"%04d",i);tooLarge.add(new Member("🟣".repeat(76)+suffix,Inclusion.ENABLED,3L,HASH,"🟣".repeat(60)+suffix,2L));}
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupBarrier.freeze("model",DAY,REGISTERED,tooLarge));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupBarrier.readCohort(" ".repeat(1048577),HASH));
    }
}
