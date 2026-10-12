package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrozenSourcePlanValidationBoundaryTest {
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    FrozenSourceStagePlanServiceTest fixturePlan(){var f=new FrozenSourceStagePlanServiceTest(){@Override GenerationJdbcStoreTest fixture(){return FrozenSourcePlanValidationBoundaryTest.this.fixture();}};f.setup();return f;}
    @Test void completePureValidationPrecedesTheWriterAndExactArchiveSurvivesRestart(){
        var f=fixturePlan();int count=f.count(),reads=f.sourceReads;
        var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);
        assertEquals(f.plan,validated.plan());assertEquals(FrozenSourceStagePlan.encode(f.plan,f.original).reference(),validated.reference());
        assertEquals(count,f.count());assertEquals(reads,f.sourceReads);assertEquals("ValidatedSourcePlan[redacted]",validated.toString());
        var reference=f.tx(()->f.service.freezeValidated(validated));count=f.count();
        assertEquals(reference,f.tx(()->f.service().freezeValidated(validated)));assertEquals(count,f.count());
        assertEquals(f.plan,f.readonly(()->f.service().readImmutable(reference)));assertEquals(reads,f.sourceReads);
        assertEquals(0,f.fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void changedProviderRequestIsRejectedBeforeAnyWriterOrArchive(){
        var f=fixturePlan();int count=f.count();var c=f.plan.chunks().getFirst();var chunks=new ArrayList<>(f.plan.chunks());
        chunks.set(0,new SourcePartitionPlanner.Chunk(c.stagePart(),c.rootId(),c.itemIds(),c.snapshotJson(),c.inputHash(),"{}"));
        var changed=new SourcePartitionPlanner.Plan(f.plan.stage(),f.plan.logicalKey(),f.plan.generation(),f.plan.reference(),f.plan.decisions(),chunks,f.plan.scope());
        assertThrows(RuntimeException.class,()->FrozenSourceStagePlanService.prevalidate(changed,f.original));assertEquals(count,f.count());
        assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));
    }
    @Test void validationNeverAuthorizesNonWriterCallsAndOuterFailureRollsBackEverything(){
        var f=fixturePlan();var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);int count=f.count();
        assertThrows(RuntimeException.class,()->f.service.freezeValidated(validated));assertThrows(RuntimeException.class,()->f.readonly(()->f.service.freezeValidated(validated)));
        assertThrows(RuntimeException.class,()->f.tx(()->{f.service.freezeValidated(validated);throw new IllegalStateException("fictional outer rollback");}));
        assertEquals(count,f.count());assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));
        assertEquals(validated.reference(),f.tx(()->f.service.freezeValidated(validated)));
    }
    @Test void actualUniverseAndNativeOwnerAreRecheckedInsideTheWriter(){
        var f=fixturePlan();var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);int count=f.count();
        f.fixture.jdbc.update("UPDATE jobs SET generation=2 WHERE id=?",f.plan.reference().ownerId());
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.freezeValidated(validated)));assertEquals(count,f.count());
        f.fixture.jdbc.update("UPDATE jobs SET generation=1 WHERE id=?",f.plan.reference().ownerId());
        f.fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),f.plan.reference().ownerId(),JdbcFrozenInputs.stableId("source-universe",f.original.runId()));
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.freezeValidated(validated)));assertEquals(count,f.count());assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));
    }
    @Test void existingArchiveRequiresOriginalActorAllBytesAndOriginalValidation(){
        var f=fixturePlan();var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);var reference=f.tx(()->f.service.freezeValidated(validated));int count=f.count();
        var other=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.fixture.source),f.universes,f.fixture.clock,"fictional-other-owner");
        assertThrows(RuntimeException.class,()->f.tx(()->other.freezeValidated(validated)));
        var changed=FrozenSourceStagePlanService.prevalidate(f.plan("Fictional changed evidence".repeat(20)),f.original);
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.freezeValidated(changed)));assertEquals(count,f.count());
        f.fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),reference.ownerId(),JdbcFrozenSourcePlans.stamp(reference.ownerId()));
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.freezeValidated(validated)));assertThrows(RuntimeException.class,()->f.readonly(()->f.service.readImmutable(reference)));assertEquals(count,f.count());
    }
    @Test void rehashedReplacementDuringWriteCannotCommitTheValidatedOriginal(){
        var f=fixturePlan();var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);var store=spy(new JdbcFrozenSourcePlans(f.fixture.source));int count=f.count();
        doAnswer(call->{call.callRealMethod();if((int)call.getArgument(1)==1){String owner=call.getArgument(0);f.fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),owner,JdbcFrozenSourcePlans.stamp(owner));}return null;}).when(store).put(anyString(),anyInt(),anyString(),any(Instant.class));
        var service=new FrozenSourceStagePlanService(store,f.universes,f.fixture.clock,"fictional-owner");assertThrows(RuntimeException.class,()->f.tx(()->service.freezeValidated(validated)));assertEquals(count,f.count());
        assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));assertEquals(validated.reference(),f.tx(()->f.service.freezeValidated(validated)));
    }
}
