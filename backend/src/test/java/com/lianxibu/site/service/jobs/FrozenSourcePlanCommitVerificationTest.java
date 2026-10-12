package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourcePlanCommitVerificationTest {
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    FrozenSourceStagePlanServiceTest small(){var f=new FrozenSourceStagePlanServiceTest(){@Override GenerationJdbcStoreTest fixture(){return FrozenSourcePlanCommitVerificationTest.this.fixture();}};f.setup();return f;}
    @Test void thousandOriginalMembersCommitAndReadTheExactOriginalPlanUnderTheUnchangedWriter(){
        var committedRoots=new ArrayList<Integer>();
        var f=new SourceValueStageProofTest(){
            @Override GenerationJdbcStoreTest fixture(){return FrozenSourcePlanCommitVerificationTest.this.fixture();}
            @Override <T>T tx(java.util.function.Supplier<T> work){
                T result=super.tx(work);committedRoots.add(this.f.count("record_kind='logical_task'"));return result;
            }
        };
        f.install(1001);assertEquals(List.of(0,1,2,3,4),committedRoots);assertEquals(1001,f.plan.decisions().size());assertEquals(4,f.plan.chunks().size());
        assertEquals(f.plan,f.readonly(()->f.plans.readImmutable(f.ref)));assertNull(f.read());
        assertEquals(4,f.f.count("record_kind='logical_task'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void persistedBlockReplacedAndRehashedDuringTheWriteCannotCommitAnOriginalPlan(){
        var f=small();var store=spy(new JdbcFrozenSourcePlans(f.fixture.source));int count=f.count();
        doAnswer(call->{call.callRealMethod();if((int)call.getArgument(1)==1){String owner=call.getArgument(0);f.fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),owner,JdbcFrozenSourcePlans.stamp(owner));}return null;}).when(store).put(anyString(),anyInt(),anyString(),any(Instant.class));
        f.service=new FrozenSourceStagePlanService(store,f.universes,f.fixture.clock,"fictional-owner");assertThrows(RuntimeException.class,f::freeze);assertEquals(count,f.count());
        assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));assertEquals(FrozenSourceStagePlan.encode(f.plan,f.original).reference(),f.tx(()->f.service().freeze(f.plan)));
    }
    @Test void persistedReceiptReplacedAndRehashedDuringTheWriteCannotCommitAnOriginalPlan(){
        var f=small();var store=spy(new JdbcFrozenSourcePlans(f.fixture.source));int count=f.count();
        doAnswer(call->{call.callRealMethod();String owner=call.getArgument(0),raw=call.getArgument(2);var body=(ObjectNode)CanonicalJson.parse(raw);body.put("logical_key","fictional-forged-logical-key");String changed=JdbcFrozenInputs.canonical(body);f.fixture.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",changed,JdbcGenerationLineage.hash(changed),JdbcFrozenSourcePlans.receiptId(owner));return null;}).when(store).append(anyString(),anyString(),anyString(),any(Instant.class));
        f.service=new FrozenSourceStagePlanService(store,f.universes,f.fixture.clock,"fictional-owner");assertThrows(RuntimeException.class,f::freeze);assertEquals(count,f.count());assertEquals(0,f.fixture.count("target_type='source_stage_plan'"));
    }
}
