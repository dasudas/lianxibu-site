package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original full native proof; snapshot reuse never crosses transaction or issuer boundaries. */
class FrozenSourceStagePlanSnapshotTest {
    FrozenSourceStagePlanServiceTest f;JdbcFrozenSourcePlans storage;FrozenSourceStagePlanService service;FrozenSourceStagePlan.Reference ref;
    boolean mysql(){return false;}
    @BeforeEach void setup(){
        f=mysql()?new FrozenSourceStagePlanMySqlTest():new FrozenSourceStagePlanServiceTest();
        f.fixture=f.fixture();f.fixture.fixture();f.original=com.lianxibu.site.service.jobs.FrozenSourceUniverseTest.universe(3,false);
        f.tx(()->{new JdbcJobRecords(f.fixture.source).ensureIntent(new JdbcJobRecords.Intent(FrozenSourceUniverseTest.OWNER,SourcePartitionPlannerTest.KEY,1,"news_dedup",java.time.LocalDate.of(2026,1,1)),f.fixture.clock.instant());return true;});
        f.universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.fixture.source),run->{f.sourceReads++;return f.original;},f.fixture.clock);
        f.universeRef=f.tx(()->f.universes.freeze(FrozenSourceUniverseTest.OWNER,f.original.runId()));f.plan=f.plan("Fictional bounded snapshot evidence");f.service=f.service();ref=f.freeze();
        storage=spy(new JdbcFrozenSourcePlans(f.fixture.source));service=new FrozenSourceStagePlanService(storage,f.universes,f.fixture.clock,"fictional-snapshot-owner");
    }
    <T>T read(int isolation,int propagation,Supplier<T> work){var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.fixture.source));tx.setReadOnly(true);tx.setIsolationLevel(isolation);tx.setPropagationBehavior(propagation);return tx.execute(s->work.get());}
    <T>T rr(Supplier<T> work){return read(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRED,work);}
    @Test void RepeatedSameSnapshotLoadsWholeOriginalOnlyOnceAndKeepsExactRefRejection(){
        int rows=f.count(),reads=f.sourceReads;
        rr(()->{for(int n=0;n<12;n++){assertEquals(f.plan,service.readImmutable(ref));assertEquals(f.plan,service.readImmutable(ref.ownerId()));}
            assertThrows(RuntimeException.class,()->service.readImmutable(new FrozenSourceStagePlan.Reference(ref.ownerId(),"0".repeat(64),ref.indexHash())));return true;});
        verify(storage,times(1)).immutableOwner(ref.ownerId());verify(storage,times(1)).immutableParts(ref.ownerId());verify(storage,times(1)).immutableReceipt(ref.ownerId());
        assertEquals(rows,f.count());assertEquals(reads,f.sourceReads);assertEquals(0,f.fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void NewAndRequiresNewSnapshotsReloadWhileSuspendedOuterSnapshotKeepsItsOwnFrame(){
        rr(()->{assertEquals(f.plan,service.readImmutable(ref));read(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.PROPAGATION_REQUIRES_NEW,()->{assertEquals(f.plan,service.readImmutable(ref));assertEquals(f.plan,service.readImmutable(ref));return true;});assertEquals(f.plan,service.readImmutable(ref));return true;});
        verify(storage,times(2)).immutableOwner(ref.ownerId());rr(()->service.readImmutable(ref));verify(storage,times(3)).immutableOwner(ref.ownerId());
    }
    @Test void RollbackAndNewSnapshotCannotRetainPriorValidProofAfterNativeReceiptDamage(){
        assertThrows(IllegalStateException.class,()->rr(()->{service.readImmutable(ref);throw new IllegalStateException("fictional rollback");}));
        f.fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);
        assertThrows(RuntimeException.class,()->rr(()->service.readImmutable(ref)));verify(storage,times(2)).immutableOwner(ref.ownerId());
    }
    @Test void LargeCompleteOriginalPlanBypassesFrameBudgetWithoutTruncatingOrRejectingProof(){
        f=mysql()?new FrozenSourceStagePlanMySqlTest():new FrozenSourceStagePlanServiceTest();f.setup();
        f.plan=f.plan("Fictional large original evidence".repeat(65));
        var validated=FrozenSourceStagePlanService.prevalidate(f.plan,f.original);ref=f.tx(()->f.service.freezeValidated(validated));
        storage=spy(new JdbcFrozenSourcePlans(f.fixture.source));service=new FrozenSourceStagePlanService(storage,f.universes,f.fixture.clock,"fictional-large-snapshot-owner");
        long bodyBytes=f.plan.chunks().stream().mapToLong(c->2L*(c.snapshotJson().length()+c.requestJson().length())).sum();assertTrue(bodyBytes>1024*1024);
        int rows=f.count();rr(()->{assertEquals(f.plan,service.readImmutable(ref));assertEquals(f.plan,service.readImmutable(ref));return true;});
        verify(storage,times(2)).immutableOwner(ref.ownerId());assertEquals(rows,f.count());assertEquals(0,f.fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void ReadCommittedWriterAndAnotherIssuerDoNotBorrowSnapshotReuse(){
        read(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.PROPAGATION_REQUIRED,()->{service.readImmutable(ref);service.readImmutable(ref);return true;});
        verify(storage,times(2)).immutableOwner(ref.ownerId());clearInvocations(storage);
        rr(()->{service.readImmutable(ref);var other=new FrozenSourceStagePlanService(storage,f.universes,f.fixture.clock,"fictional-other-snapshot-owner");other.readImmutable(ref);return true;});
        verify(storage,times(2)).immutableOwner(ref.ownerId());clearInvocations(storage);
        f.tx(()->{service.read(ref);service.read(ref);return true;});verify(storage,times(2)).lockOwner(ref.ownerId());assertThrows(RuntimeException.class,()->service.readImmutable(ref));
    }
}
