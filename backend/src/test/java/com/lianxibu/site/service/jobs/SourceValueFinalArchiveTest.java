package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFinalArchiveTest {
    SourceValueStageProofTest f;SourceValueFinalProofService proofs;JdbcSourceValueFinalBlocks store;SourceValueFinalArchiveService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalArchiveTest.this.fixture();}};f.install(5);install();}
    void install(){proofs=new SourceValueFinalProofService(f.service,f.plans,f.universes);store=new JdbcSourceValueFinalBlocks(f.f.source);service=provider(f.f.writes);}
    SourceValueFinalArchiveService provider(SharedWriteExecutor writes){return new SourceValueFinalArchiveService(proofs,store,writes,new DataSourceTransactionManager(f.f.source),f.clock);}
    int parts(){return f.f.count("record_kind='snapshot_part' AND purpose='result' AND owner_job_id='"+f.ref.ownerId()+"'");}
    void large(){f.install(501);f.pure=new SourceValueStageResultTest(){@Override ObjectNode payload(SourcePartitionPlanner.Chunk c){var n=super.payload(c);n.put("summary","😀".repeat(16370)+" tail-"+c.stagePart());return n;}};install();for(int i=0;i<f.plan.chunks().size();i++)f.complete(i);}
    @Test void pendingAndOuterTransactionCannotWriteOrStageAnUnprovenFinal(){
        assertNull(service.stage(f.ref));assertEquals(0,parts());assertThrows(RuntimeException.class,()->f.tx(()->service.stage(f.ref)));assertEquals(0,parts());f.complete(0);f.f.jdbc.update("UPDATE jobs SET result_hash=? WHERE id=?",new byte[32],f.plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(0,parts());
    }
    @Test void actualCompleteLargeBlocksAndEmptyKeepOriginalBytesWithoutFinalSuccess(){
        large();var staged=service.stage(f.ref);assertTrue(parts()>2);assertEquals(staged.original().result().json(),f.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())).json());int count=f.f.count("1=1");service=provider(f.f.writes);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(count,f.f.count("1=1"));assertEquals(f.plan.chunks().size(),f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,f.f.count("target_type='source_value_final_result'"));
        f.install(0);install();assertNotNull(service.stage(f.ref));assertEquals(2,parts());assertEquals(0,f.f.count("record_kind='logical_task'"));
    }
    @Test void interruptedStagingKeepsCommittedPrefixAndRestartCompletesMissingBlocks(){
        large();f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_final_block_failure CHECK(purpose IS NULL OR purpose<>'result' OR part_no<>2)");assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,parts());assertEquals(0,f.f.count("target_type='source_value_final_result'"));f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_final_block_failure":"ALTER TABLE jobs DROP CHECK fictional_final_block_failure");var staged=service.stage(f.ref);assertEquals(FrozenSourceValueFinal.encode(staged.original().result()).payloads().size(),parts());assertEquals(staged.original().result().hash(),f.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())).hash());
    }
    @Test void actualCommitAckLossRecoversAndChangedStoredBlocksAreRejected(){
        f.complete(0);var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional block ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var staged=provider(writes).stage(f.ref);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(2,parts());f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result' AND part_no=1",new byte[32]);assertThrows(RuntimeException.class,()->service.stage(f.ref));assertThrows(RuntimeException.class,()->f.readonly(()->service.verifyStaged(staged.archiveRef(),staged.original().result())));assertEquals(2,parts());
    }
}
