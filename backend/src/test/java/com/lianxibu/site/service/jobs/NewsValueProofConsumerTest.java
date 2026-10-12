package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class NewsValueProofConsumerTest {
    NewsValueSourcePlanPreparationTest f;SourcePartitionPlanner.Plan plan;SourceStagePreparationService parts;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=new NewsValueSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueProofConsumerTest.this.fixture();}};f.setup();plan=f.prepare();
        parts=new SourceStagePreparationService(f.f.source,f.plans,f.p1.universes,f.p1.collections::read,f.clock,"fictional-owner");
    }
    GenerationFlowService.Frozen prepare(){return f.p1.tx(()->parts.preparePart(f.intent,0,"fictional-p2-part"));}
    void damageObservation(){f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);}
    GenerationJdbcStore store(){
        var c=plan.chunks().getFirst();var snapshot=CanonicalJson.parse(c.snapshotJson());var deps=FrozenDependencyManifest.read(snapshot.get(FrozenDependencyManifest.FIELD));var evidence=new ArrayList<GenerationFlowService.Evidence>();
        for(var m:snapshot.get("members"))evidence.add(new GenerationFlowService.Evidence(m.get("id").asText(),m.get("kind").asText(),LocalDate.parse(m.get("business_date").asText()),Instant.parse(m.get("completed_at").asText()),m.get("body_hash").asText(),JdbcFrozenInputs.canonical(m.get("body")),true,false,"accepted",List.of()));
        var command=new GenerationFlowService.Prepare(plan.logicalKey(),plan.generation(),plan.stage(),plan.scope().businessDate(),f.p1.collection.facts().sourceId(),deps.dependencies(),evidence,deps.expectedIds(),false,plan.scope().settings().semanticHash(),FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)),List.of(),0);
        return new GenerationJdbcStore(f.f.source,f.clock,"fictional-owner",c.rootId(),command,f.p1.collections::read);
    }
    DispatchReadinessService dispatch(){
        return dispatch(f.dependencies);
    }
    DispatchReadinessService dispatch(SourceDedupDependencyService proof){
        var inputs=new JdbcFrozenInputs(f.f.source);
        return new DispatchReadinessService(new JdbcDispatchInputs(f.f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.f.source),f.clock,"fictional-worker"),mock(ContentReadService.class),f.p1.members,target->{throw new AssertionError("no synthetic references");},f.p1.collections::read,new SourcePartitionProofService(f.plans),true,proof);
    }
    @Test void originalP2PartUsesOriginalRequestAndRemainsUncalled(){
        var frozen=prepare();assertEquals(plan.chunks().getFirst().snapshotJson(),frozen.snapshotJson());assertEquals(plan.chunks().getFirst().requestJson(),frozen.requestJson());
        assertEquals(frozen,f.readonly(()->store().committed("fictional-p2-part")));assertEquals(frozen,f.readonly(()->parts.committedPart(f.intent,0,"fictional-p2-part")));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void missingOriginalObservationRefusesANewP2RootWithoutWriting(){
        int roots=f.f.count("record_kind IN ('logical_task','ai_attempt')");damageObservation();assertThrows(RuntimeException.class,this::prepare);assertEquals(roots,f.f.count("record_kind IN ('logical_task','ai_attempt')"));
    }
    @Test void committedPartAndDirectStoreCannotReplayWithDamagedOriginalObservation(){
        prepare();int count=f.f.count("1=1");damageObservation();assertThrows(RuntimeException.class,()->f.readonly(()->store().committed("fictional-p2-part")));assertThrows(RuntimeException.class,()->f.readonly(()->parts.committedPart(f.intent,0,"fictional-p2-part")));assertEquals(count,f.f.count("1=1"));
    }
    @Test void dispatchMustRecheckNativeP1ObservationBeforeEverySend(){
        var frozen=prepare();assertTrue(f.p1.tx(()->dispatch().ready(frozen.ownerId())));int count=f.f.count("1=1");damageObservation();assertThrows(RuntimeException.class,()->f.p1.tx(()->dispatch().ready(frozen.ownerId())));assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void declaringCompleteNewValueInputRequiresTheActualProvider(){
        var frozen=prepare();assertThrows(RuntimeException.class,()->f.p1.tx(()->dispatch(null).ready(frozen.ownerId())));assertTrue(f.p1.tx(()->dispatch().ready(frozen.ownerId())));
    }
    @Test void currentMemberRevisionChangeStillMakesTheOriginalRequestUnsendable(){
        var frozen=prepare();f.f.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE terminal_state='accepted'");assertFalse(f.p1.tx(()->dispatch().ready(frozen.ownerId())));assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
}
