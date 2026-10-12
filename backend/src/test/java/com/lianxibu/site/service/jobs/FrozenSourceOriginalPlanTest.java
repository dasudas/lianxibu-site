package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Both facts come from the same complete original archive verification. */
class FrozenSourceOriginalPlanTest {
    FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest();}
    FrozenSourceStagePlanServiceTest setup(){var f=fixture();f.setup();f.freeze();f.universes=spy(f.universes);f.service=f.service();return f;}
    @Test void writerReturnsTheSameProvedPlanAndUniverseWithOneNativeRead(){
        var f=setup();int rows=f.count(),reads=f.sourceReads;
        var actual=f.tx(()->f.service.readOriginal(f.universeRef.ownerId()));
        assertEquals(f.plan,actual.plan());assertEquals(f.original,actual.universe());
        verify(f.universes,times(1)).read(f.universeRef);verify(f.universes,never()).readImmutable(f.universeRef);
        assertEquals(rows,f.count());assertEquals(reads,f.sourceReads);assertEquals("OriginalSourcePlan[redacted]",actual.toString());
    }
    @Test void immutableReturnsCompleteOriginalFactsWithoutWriterOrCurrentSourceAccess(){
        var f=setup();int rows=f.count(),reads=f.sourceReads;
        var actual=f.readonly(()->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return f.service.readImmutableOriginal(f.universeRef.ownerId());});
        assertEquals(f.plan,actual.plan());assertEquals(f.original,actual.universe());
        verify(f.universes,times(1)).readImmutable(f.universeRef);verify(f.universes,never()).read(f.universeRef);
        assertEquals(rows,f.count());assertEquals(reads,f.sourceReads);
        assertThrows(RuntimeException.class,()->f.service.readOriginal(f.universeRef.ownerId()));
        assertThrows(RuntimeException.class,()->f.service.readImmutableOriginal(f.universeRef.ownerId()));
        assertThrows(RuntimeException.class,()->f.readonly(()->f.service.readOriginal(f.universeRef.ownerId())));
        assertEquals(f.plan,f.tx(()->f.service.readImmutableOriginal(f.universeRef.ownerId())).plan()); // Receipt lookup also uses immutable archives inside the original writer.
    }
    @Test void hashConsistentCorruptPlanCannotProduceAnOriginalPair(){
        var f=setup();String owner=f.universeRef.ownerId(),stamp=JdbcFrozenSourcePlans.stamp(owner);
        f.fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),owner,stamp);
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.readOriginal(owner)));
        assertThrows(RuntimeException.class,()->f.readonly(()->f.service.readImmutableOriginal(owner)));
    }
    @Test void nextTransactionRereadsBothArchivesAndRejectsChangedUniverseOrReceipt(){
        var f=setup();String owner=f.universeRef.ownerId();
        var prior=f.tx(()->f.service.readOriginal(owner));assertEquals(f.original,prior.universe());
        f.fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",owner,JdbcFrozenInputs.stableId("source-universe",f.original.runId()));
        assertThrows(RuntimeException.class,()->f.tx(()->f.service.readOriginal(owner)));
        assertThrows(RuntimeException.class,()->f.readonly(()->f.service.readImmutableOriginal(owner)));
        verify(f.universes,times(2)).read(f.universeRef);
        var other=setup();other.fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);
        assertThrows(RuntimeException.class,()->other.tx(()->other.service.readOriginal(other.universeRef.ownerId())));
    }
}
