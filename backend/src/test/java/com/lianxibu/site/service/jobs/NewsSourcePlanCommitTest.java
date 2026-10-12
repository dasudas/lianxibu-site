package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NewsSourcePlanCommitTest {
    NewsSourcePlanPreparationTest f;
    NewsSourcePlanPreparationTest fixture(){return new NewsSourcePlanPreparationTest();}
    @BeforeEach void setup() throws Exception {f=fixture();f.setup();}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.source));tx.setReadOnly(true);return tx.execute(status->work.get());}
    SourcePartitionPlanner.Plan committed(){return readonly(()->f.preparation.committed(f.collection.intentId(),"fictional-run"));}
    @Test void emptyReceiptIsNullOnlyInAnActiveTransactionAndOrphanBlocksReject(){
        int count=f.f.count("1=1");assertNull(committed());assertEquals(count,f.f.count("1=1"));assertThrows(RuntimeException.class,()->f.preparation.committed(f.collection.intentId(),"fictional-run"));
        f.tx(()->{new JdbcFrozenSourcePlans(f.f.source).put(f.collection.intentId(),0,"{}",f.f.clock.instant());return true;});assertThrows(RuntimeException.class,this::committed);
    }
    @Test void restartReadsOnlyOriginalArchiveEvenWithCurrentTablesUnavailableAndGateClosed(){
        var original=f.prepare();int count=f.f.count("1=1"),reads=f.settingsReads;f.allowed=false;f.f.jdbc.execute("DROP TABLE analyzed_entities");f.f.jdbc.execute("DROP TABLE source_run_items");f.f.jdbc.execute("DROP TABLE source_runs");
        f.preparation=f.service();assertEquals(original,committed());assertEquals(count,f.f.count("1=1"));assertEquals(reads,f.settingsReads);assertEquals(0,f.f.count("record_kind='ai_attempt'"));
    }
    @Test void commitAckLossRecoversOneOriginalPlanWithoutCapturingAgain(){
        var manager=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional source plan ACK lost");}}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),manager,()->25,n->{});
        var result=writes.execute(new SharedWriteExecutor.ActionContext("source-plan:"+f.collection.intentId(),f.collection.intentId(),null),key->f.preparation.committed(f.collection.intentId(),"fictional-run"),a->f.preparation.prepare(f.collection.intentId(),"fictional-run"));
        assertEquals(result,committed());assertEquals(1,f.settingsReads);assertEquals(1,f.f.count("target_type='source_stage_plan'"));assertEquals(0,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void wrongRunAliasOrDamagedReceiptAndOriginalBlocksNeverReturnMissing(){
        f.prepare();assertThrows(RuntimeException.class,()->readonly(()->f.preparation.committed(f.collection.intentId(),"fictional-other-run")));
        assertThrows(RuntimeException.class,()->readonly(()->f.preparation.committed(f.collection.intentId().toUpperCase(java.util.Locale.ROOT),"fictional-run")));
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);assertThrows(RuntimeException.class,this::committed);
        String payload=f.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='source_stage_plan'",String.class);f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(com.lianxibu.site.domain.protocol.CanonicalJson.parse(payload))));
        f.f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",f.collection.intentId(),JdbcFrozenSourcePlans.stamp(f.collection.intentId()));assertThrows(RuntimeException.class,this::committed);assertEquals(1,f.settingsReads);
    }
}
