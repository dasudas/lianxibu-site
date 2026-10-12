package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
class AdHocPreparationCycleTest {
    OwnerAdHocCreationTest c;
    OwnerAdHocCreationTest fixture(){return new OwnerAdHocCreationTest();}
    @BeforeEach void setup(){c=fixture();c.setup();}
    AdHocPreparationCycle cycle(){var tx=new DataSourceTransactionManager(c.f.source);return new AdHocPreparationCycle(new JdbcAdHocIntentScan(c.f.source),c.accepted,new OwnerAdHocPreparationService(c.f.source,c.accepted,c.f.writes,tx,c.f.clock,()->c.allowed),tx);}
    @Test void RestartFindsTheOriginalQuestionAndPreparesExactlyOneDurableRootWithoutCallingAi(){
        var original=c.first();var cycle=cycle();assertEquals(AdHocPreparationCycle.Outcome.PREPARED,cycle.run(()->true));assertEquals(AdHocPreparationCycle.Outcome.IDLE,cycle().run(()->true));assertEquals(1,c.f.count("record_kind='logical_task' AND call_count=0 AND priority_no=14"));assertEquals(1,c.f.count("record_kind='ai_attempt' AND state='prepared'"));assertEquals(1,c.freezes);assertEquals(original.json(),c.first().json());
    }
    @Test void AFailedOriginalCannotStrandTheNextDurableQuestion(){
        var one=c.create.create(c.owner,"fictional-one","Fictional question one","Fictional reason","fictional-one-key");var two=c.create.create(c.owner,"fictional-two","Fictional question two","Fictional reason","fictional-two-key");var ordered=new ArrayList<>(List.of(one,two));ordered.sort(Comparator.comparing(x->x.command().intentId()));var failed=ordered.getFirst();var good=ordered.getLast();assertEquals(1,c.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE action_key_hash=?",new byte[32],JdbcGenerationLineage.hash(failed.actionKey())));
        var cycle=cycle();assertEquals(AdHocPreparationCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));assertEquals(AdHocPreparationCycle.Outcome.PREPARED,cycle.run(()->true));assertNotNull(new JdbcJobRecords(c.f.source).byId(good.command().rootId()));assertNull(new JdbcJobRecords(c.f.source).byId(failed.command().rootId()));assertEquals(1,c.f.count("record_kind='logical_task'"));
    }
    @Test void AClosedCurrentGateDefersWorkAndLaterRestartUsesOriginalSettings(){
        c.first();c.allowed=false;assertEquals(AdHocPreparationCycle.Outcome.GATED,cycle().run(()->true));assertEquals(2,c.f.count("1=1"));c.allowed=true;assertEquals(AdHocPreparationCycle.Outcome.PREPARED,cycle().run(()->true));assertEquals(1,c.freezes);
    }
    @Test void StopAndOuterTransactionCannotCreateARequestOrConsumeBudget(){
        c.first();var cycle=cycle();assertEquals(AdHocPreparationCycle.Outcome.IDLE,cycle.run(()->false));var outer=new TransactionTemplate(new DataSourceTransactionManager(c.f.source));assertEquals(AdHocPreparationCycle.Outcome.RECOVERY_REQUIRED,outer.execute(s->cycle.run(()->true)));assertEquals(2,c.f.count("1=1"));assertEquals(AdHocPreparationCycle.Outcome.PREPARED,cycle.run(()->true));
    }
    @Test void ConcurrentPassesCannotOverlapTheSameOriginalPreparation()throws Exception{
        c.first();var tx=new DataSourceTransactionManager(c.f.source);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var prepare=new OwnerAdHocPreparationService(c.f.source,c.accepted,c.f.writes,tx,c.f.clock,()->{entered.countDown();try{if(!release.await(500,TimeUnit.MILLISECONDS))throw new AssertionError("fictional release missing");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("fictional interrupted");}return true;});var cycle=new AdHocPreparationCycle(new JdbcAdHocIntentScan(c.f.source),c.accepted,prepare,tx);var pool=Executors.newSingleThreadExecutor();
        try{var pending=pool.submit(()->cycle.run(()->true));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(AdHocPreparationCycle.Outcome.BUSY,cycle.run(()->true));release.countDown();assertEquals(AdHocPreparationCycle.Outcome.PREPARED,pending.get(5,TimeUnit.SECONDS));assertEquals(1,c.f.count("record_kind='logical_task'"));assertEquals(0,c.f.count("record_kind='logical_task' AND call_count<>0"));}finally{release.countDown();pool.shutdownNow();}
    }
}
