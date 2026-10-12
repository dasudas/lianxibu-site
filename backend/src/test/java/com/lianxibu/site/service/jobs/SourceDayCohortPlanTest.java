package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import com.lianxibu.site.repository.jobs.JdbcSourceDayRosters;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceDayCohortPlanTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant registered=Instant.parse("2026-09-20T16:10:00.000002Z");final String configHash="a".repeat(64);
    SourceDayRoster.RawSource source(String id,String category,String stage,boolean effective,Instant valid){return new SourceDayRoster.RawSource(id,category,2,3,stage,"disabled",valid,false,effective,effective?configHash:null);}
    JdbcSourceDayRosters.Frozen frozen(List<SourceDayRoster.RawSource> sources){return new JdbcSourceDayRosters.Frozen(day,JdbcSourceDayRosters.owner(day),registered,new SourceDayRoster.Evidence(registered.minusNanos(1000),sources.size(),sources));}
    @Test void completeMixedDirectoryRetainsFiveCategoriesAllMembersAndUnassignedCoverage(){
        var original=frozen(List.of(source("fictional-news","news_tech","production",true,registered.plusSeconds(10)),source("fictional-agent","agent","production",true,registered.plusSeconds(10)),source("fictional-disabled","skill","disabled",true,registered.plusSeconds(10)),source("fictional-trial","workflow","trial",true,registered.plusSeconds(10)),source("fictional-expired","model","production",true,registered),source("fictional-incomplete","agent","production",false,null),source("fictional-unassigned",null,"production",true,registered.plusSeconds(10)),source("fictional-legacy",null,"disabled",false,null)));
        var plan=SourceDayCohortPlan.derive(original);assertEquals(5,plan.cohorts().size());assertEquals(8,plan.directory().evidence().sourceCount());assertEquals(2,plan.requests().size());
        assertEquals(8,plan.cohorts().stream().mapToInt(c->c.members().size()).sum()+plan.unassignedSourceIds().size());assertEquals(List.of("fictional-legacy","fictional-unassigned"),plan.unassignedSourceIds());
        assertEquals(SourceDedupBarrier.Inclusion.DISABLED,plan.cohort("skill").members().getFirst().inclusion());assertEquals(configHash,plan.cohort("skill").members().getFirst().configHash());
        assertEquals(SourceDedupBarrier.Inclusion.UNCONFIGURED,plan.cohort("model").members().getFirst().inclusion());assertNull(plan.cohort("model").members().getFirst().configHash());
        for(var request:plan.requests()){assertEquals(original.evidence().hash(),request.rosterHash());assertEquals(registered,request.registeredAt());assertTrue(request.runId().matches("[0-9a-f]{64}"));}
        assertFalse(plan.toString().contains("fictional-news"));assertThrows(UnsupportedOperationException.class,()->plan.requests().clear());
    }
    @Test void trueEmptyDirectoryHasFiveActualEmptyCohortsAndAbsenceIsAnError(){
        var plan=SourceDayCohortPlan.derive(frozen(List.of()));assertEquals(0,plan.directory().evidence().sourceCount());assertEquals(0,plan.requests().size());assertEquals(0,plan.unassignedSourceIds().size());
        assertTrue(plan.cohorts().stream().allMatch(c->c.members().isEmpty()));assertThrows(RuntimeException.class,()->SourceDayCohortPlan.derive(null));
        var unassigned=SourceDayCohortPlan.derive(frozen(List.of(source("fictional-legacy",null,"disabled",false,null))));assertEquals(1,unassigned.unassignedSourceIds().size());assertNotEquals(plan.hash(),unassigned.hash());
    }
    @Test void deterministicIdentityAndHashBindOriginalDirectoryNotCurrentP1Subset(){
        var original=frozen(List.of(source("fictional-a","agent","production",true,registered.plusSeconds(10))));var plan=SourceDayCohortPlan.derive(original);
        assertEquals(plan.metadataJson(),SourceDayCohortPlan.derive(original).metadataJson());assertEquals(plan.requests().getFirst().runId(),plan.cohort("agent").members().getFirst().runId());assertEquals(1,plan.cohort("agent").members().getFirst().runGeneration());
        assertNotEquals(plan.hash(),SourceDayCohortPlan.derive(frozen(List.of(source("fictional-a","agent","disabled",true,registered.plusSeconds(10))))).hash());
        assertEquals(plan.hash(),SourceDayCohortPlan.read(plan.metadataJson(),plan.hash(),original).hash());
    }
    @Test void oneFinishedSourceDoesNotCloseOriginalTwoSourceCohortEarly(){
        var plan=SourceDayCohortPlan.derive(frozen(List.of(source("fictional-a","news_tech","production",true,registered.plusSeconds(10)),source("fictional-b","news_tech","production",true,registered.plusSeconds(10)))));var cohort=plan.cohort("news_tech");var first=cohort.members().getFirst();
        var fact=new SourceDedupBarrier.Progress("news_tech",day,first.sourceId(),first.configRevision(),first.configHash(),first.runId(),first.runGeneration(),SourceDedupBarrier.State.COMPLETE,registered.plusSeconds(1),"b".repeat(64));
        assertNull(SourceDedupBarrier.close(cohort,registered.plusSeconds(2),List.of(fact)));var closure=SourceDedupBarrier.close(cohort,SourceDedupBarrier.deadline(day),List.of(fact));assertEquals(1,closure.entries().stream().filter(e->e.decision()==SourceDedupBarrier.Decision.MISSING).count());
    }
    @Test void rehashedManifestCannotReplaceCountCohortOrUnassignedOriginal(){
        var original=frozen(List.of(source("fictional-unassigned",null,"disabled",false,null)));var plan=SourceDayCohortPlan.derive(original);
        var count=(ObjectNode)CanonicalJson.parse(plan.metadataJson());count.put("source_count",0);reject(count,original);
        var category=(ObjectNode)CanonicalJson.parse(plan.metadataJson());((ObjectNode)category.get("categories").get(0)).put("cohort_hash","b".repeat(64));reject(category,original);
        var unknown=(ObjectNode)CanonicalJson.parse(plan.metadataJson());((ObjectNode)unknown.get("unassigned").get(0)).put("source_id","FICTIONAL-unassigned");reject(unknown,original);
    }
    private void reject(ObjectNode node,JdbcSourceDayRosters.Frozen original){String json=new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);assertThrows(RuntimeException.class,()->SourceDayCohortPlan.read(json,CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,node),original));}
    @Test void physicalOwnerAndOriginalTimesAreRequired(){
        var original=frozen(List.of());assertThrows(RuntimeException.class,()->SourceDayCohortPlan.derive(new JdbcSourceDayRosters.Frozen(day,"fictional-owner",registered,original.evidence())));
        assertThrows(RuntimeException.class,()->SourceDayCohortPlan.derive(new JdbcSourceDayRosters.Frozen(day,original.ownerId(),registered.minusNanos(2000),original.evidence())));
        assertThrows(RuntimeException.class,()->SourceDayCohortPlan.derive(new JdbcSourceDayRosters.Frozen(day,original.ownerId(),Instant.parse("2026-09-20T20:30:00.000000Z"),original.evidence())));
    }
    @Test void wholeDirectoryHashIsComputedOnceForMultipleEnabledSources(){
        var rows=new ArrayList<SourceDayRoster.RawSource>();for(int i=0;i<6;i++)rows.add(source("fictional-enabled-"+i,"news_tech","production",true,registered.plusSeconds(10)));
        var original=frozen(rows);var evidence=org.mockito.Mockito.spy(original.evidence());var plan=SourceDayCohortPlan.derive(new JdbcSourceDayRosters.Frozen(day,original.ownerId(),registered,evidence));
        assertEquals(6,plan.requests().size());org.mockito.Mockito.verify(evidence,org.mockito.Mockito.times(1)).hash();
    }
}
