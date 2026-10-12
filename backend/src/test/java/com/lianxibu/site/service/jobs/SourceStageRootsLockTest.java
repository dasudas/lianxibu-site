package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.JdbcSourceStageRoots;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class SourceStageRootsLockTest {
    SourceDedupResolvedStageResultTest f;JdbcSourceStageRoots store;List<JdbcSourceStageRoots.Native> roots;
    SourceDedupResolvedStageResultTest fixture(){return new SourceDedupResolvedStageResultTest();}
    @BeforeEach void setup()throws Exception {f=fixture();f.setup(201);store=new JdbcSourceStageRoots(f.f.db.source);roots=f.f.read(()->f.plan.chunks().stream().map(c->store.find(c.rootId())).toList());}
    @Test void allThreeOriginalHeadersLockInStableOrderWithoutChangingStateBudgetOrRevision(){
        var reverse=new ArrayList<>(roots);Collections.reverse(reverse);int count=f.f.db.count("1=1");f.f.p1.tx(()->{store.lockOriginal(reverse);return true;});assertEquals(roots,f.f.read(()->f.plan.chunks().stream().map(c->store.find(c.rootId())).toList()));assertEquals(count,f.f.db.count("1=1"));assertEquals(0,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void noTransactionReadOnlyAndMalformedListsRefuseBeforeDatabaseAccess(){
        assertThrows(RuntimeException.class,()->store.lockOriginal(roots));assertThrows(RuntimeException.class,()->f.f.read(()->{store.lockOriginal(roots);return true;}));
        var data=mock(javax.sql.DataSource.class);var empty=new JdbcSourceStageRoots(data);var invalid=new ArrayList<List<JdbcSourceStageRoots.Native>>();invalid.add(List.of());invalid.add(Arrays.asList((JdbcSourceStageRoots.Native)null));invalid.add(List.of(roots.getFirst(),roots.getFirst()));invalid.add(Collections.nCopies(101,roots.getFirst()));
        var original=roots.getFirst();invalid.add(List.of(new JdbcSourceStageRoots.Native("UPPER",original.intent(),original.key(),original.generation(),original.part(),original.type(),original.day(),original.state(),original.revision(),original.calls(),original.maximum(),original.inputState(),original.inputHash(),original.cutoff(),original.application(),original.resultHash(),original.appliedAt(),original.updatedAt())));
        for(var rows:invalid)assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{empty.lockOriginal(rows);return true;}));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{empty.lockOriginal(null);return true;}));verifyNoInteractions(data);
    }
    @Test void everyChangedHeaderFieldIsRejectedAgainstTheCompleteOriginalCapture(){
        String root=roots.getFirst().id();Object[][] mutations={{"revision",roots.getFirst().revision()+1},{"state","cancelled"},{"call_count",1},{"input_state","waiting_input"},{"input_hash",new byte[32]},{"result_hash",new byte[32]},{"application_state","DISCARDED"},{"applied_at",java.sql.Timestamp.from(f.f.clock.instant())},{"updated_at",java.sql.Timestamp.from(roots.getFirst().updatedAt().plusNanos(1000))},{"business_date",java.sql.Date.valueOf("2026-01-02")},{"record_identity_hash",new byte[32]},{"logical_key_hash",new byte[32]},{"intent_id","a".repeat(64)}};
        for(var mutation:mutations){Object before=f.f.db.jdbc.queryForObject("SELECT "+mutation[0]+" FROM jobs WHERE id=?",Object.class,root);f.f.db.jdbc.update("UPDATE jobs SET "+mutation[0]+"=? WHERE id=?",mutation[1],root);assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{store.lockOriginal(roots);return true;}),mutation[0].toString());f.f.db.jdbc.update("UPDATE jobs SET "+mutation[0]+"=? WHERE id=?",before,root);f.f.p1.tx(()->{store.lockOriginal(roots);return true;});}
    }
    @Test void missingAndWrongRoleRootsCannotMatchTheOriginalHeaders(){
        String root=roots.getFirst().id();var original=roots.getFirst();var wrongRole=new JdbcSourceStageRoots.Native(f.ref.ownerId(),original.intent(),original.key(),original.generation(),original.part(),original.type(),original.day(),original.state(),original.revision(),original.calls(),original.maximum(),original.inputState(),original.inputHash(),original.cutoff(),original.application(),original.resultHash(),original.appliedAt(),original.updatedAt());assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{store.lockOriginal(List.of(wrongRole));return true;}));f.f.db.jdbc.update("DELETE FROM jobs WHERE id=?",root);assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{store.lockOriginal(roots);return true;}));
    }
    @Test void actualSecondConnectionUpdateWaitsForTheShortWriterCommit()throws Exception {
        String root=roots.getFirst().id();var started=new CountDownLatch(1);var completed=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();var future=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        try {f.f.p1.tx(()->{store.lockOriginal(roots);future.set(pool.submit(()->{started.countDown();try{return f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);}finally{completed.countDown();}}));assertTrue(assertDoesNotThrow(()->started.await(1,TimeUnit.SECONDS)));assertFalse(assertDoesNotThrow(()->completed.await(150,TimeUnit.MILLISECONDS)));return true;});assertEquals(1,future.get().get(5,TimeUnit.SECONDS));assertEquals(roots.getFirst().revision()+1,f.f.read(()->store.find(root)).revision());assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{store.lockOriginal(roots);return true;}));}finally{pool.shutdownNow();}
    }

    @Test void explicitStorageCapacityForUniqueHeadersRefusesBeforeAnySql(){
        var data=mock(javax.sql.DataSource.class);var empty=new JdbcSourceStageRoots(data);var original=roots.getFirst();var many=new ArrayList<JdbcSourceStageRoots.Native>();for(int n=0;n<101;n++)many.add(new JdbcSourceStageRoots.Native(SourcePartitionPlanner.rootId(original.key(),original.generation(),n),original.intent(),original.key(),original.generation(),n,original.type(),original.day(),original.state(),original.revision(),original.calls(),original.maximum(),original.inputState(),original.inputHash(),original.cutoff(),original.application(),original.resultHash(),original.appliedAt(),original.updatedAt()));
        assertThrows(JdbcSourceStageRoots.CapacityRejected.class,()->f.f.p1.tx(()->{empty.lockOriginal(many);return true;}));verifyNoInteractions(data);
    }
}
