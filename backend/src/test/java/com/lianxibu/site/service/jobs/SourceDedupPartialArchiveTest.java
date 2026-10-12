package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.repository.jobs.*;
import javax.sql.DataSource;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SourceDedupPartialArchiveTest {
    SourceDedupResolvedStageResultTest f;SourceStageResolutionService resolutions;JdbcSourceDedupPartialBlocks store;SourceDedupPartialArchiveService service;
    SourceDedupResolvedStageResultTest fixture(){return new SourceDedupResolvedStageResultTest();}
    void setup(int count,int padding)throws Exception {
        f=fixture();f.setup(count,padding);var results=new JdbcSourceDedupResults(f.f.db.source);var dependency=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.f.db.source));
        resolutions=new SourceStageResolutionService(new JdbcSourceStageRoots(f.f.db.source),f.f.plans,f.f.p1.universes,f.f.parts,new JdbcFrozenInputs(f.f.db.source),results,new SourceDedupResultProofService(results,f.f.plans,f.f.p1.universes),new SourceValueResultProofService(results,f.f.plans,f.f.p1.universes,dependency),dependency);store=new JdbcSourceDedupPartialBlocks(f.f.db.source);service=provider(f.f.db.writes);
    }
    SourceDedupPartialArchiveService provider(SharedWriteExecutor writes){return new SourceDedupPartialArchiveService(resolutions,store,writes,new DataSourceTransactionManager(f.f.db.source),f.f.clock);}
    void large()throws Exception {setup(201,900);f.applied(0);f.terminal(1,"failed");f.applied(2);}
    int blocks(){return f.f.db.count("record_kind='snapshot_part' AND purpose='result' AND owner_job_id='"+f.ref.ownerId()+"'");}
    @Test void constructorDoesNotContactDatabaseAndPendingCompleteZeroOrWriterCannotStage()throws Exception {
        var data=mock(DataSource.class);new JdbcSourceDedupPartialBlocks(data);verifyNoInteractions(data);
        var resolution=mock(SourceStageResolutionService.class);var blocks=mock(JdbcSourceDedupPartialBlocks.class);var writes=mock(SharedWriteExecutor.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);new SourceDedupPartialArchiveService(resolution,blocks,writes,tx,Clock.systemUTC());verifyNoInteractions(resolution,blocks,writes,tx);
        setup(1,0);assertNull(service.stage(f.ref));assertEquals(0,blocks());assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.stage(f.ref)));f.f.db.jdbc.update("UPDATE jobs SET state='unknown_external' WHERE id=?",f.plan.chunks().getFirst().rootId());assertNull(service.stage(f.ref));assertEquals(0,blocks());f.applied(0);assertNull(service.stage(f.ref));assertEquals(0,blocks());setup(0,0);assertNull(service.stage(f.ref));assertEquals(0,blocks());
    }
    @Test void actualMultipleBlocksReplayExactlyWithoutAnyParentSealOrNewCall()throws Exception {
        large();String parentState=f.f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId());var staged=service.stage(f.ref);var original=SourceDedupStageResult.createResolved(staged.original());assertTrue(blocks()>2);assertEquals(original.json(),f.f.read(()->service.verifyStaged(staged.archiveRef(),original)).json());int count=f.f.db.count("1=1");service=provider(f.f.db.writes);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(count,f.f.db.count("1=1"));assertEquals(0,f.f.db.count("target_type='source_dedup_partial_result'"));assertEquals(parentState,f.f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId()));assertEquals(5,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void interruptedPrefixRestartsOriginalBytesAndActualLostCommitAckRecovers()throws Exception {
        large();f.f.db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_partial_block_failure CHECK(purpose IS NULL OR purpose<>'result' OR part_no<>2)");assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,blocks());f.f.db.jdbc.execute(f.f.db.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_partial_block_failure":"ALTER TABLE jobs DROP CHECK fictional_partial_block_failure");service=provider(f.f.db.writes);var staged=service.stage(f.ref);assertEquals(FrozenSourceDedupPartial.encode(SourceDedupStageResult.createResolved(staged.original())).payloads().size(),blocks());
        setup(1,0);f.terminal(0,"failed");var fault=new DataSourceTransactionManager(f.f.db.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional partial block ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.db.source),fault,()->25,n->{});staged=provider(writes).stage(f.ref);assertEquals(staged.archiveRef(),service.stage(f.ref).archiveRef());assertEquals(2,blocks());
    }
    @Test void nativeChangeDuringStagingRejectsOldProofAndPreservesOldBlocks()throws Exception {
        setup(1,0);f.terminal(0,"failed");store=spy(store);doAnswer(call->{var value=call.callRealMethod();if((int)call.getArgument(2)==0)f.terminal(0,"cancelled");return value;}).when(store).put(anyString(),anyString(),anyInt(),anyString(),any());service=provider(f.f.db.writes);assertThrows(RuntimeException.class,()->service.stage(f.ref));assertEquals(2,blocks());assertEquals(0,f.f.db.count("target_type='source_dedup_partial_result'"));store=new JdbcSourceDedupPartialBlocks(f.f.db.source);service=provider(f.f.db.writes);assertNotNull(service.stage(f.ref));assertEquals(4,blocks());
    }
    @Test void damagedBlocksOwnerAndWritableReadersFailClosed()throws Exception {
        setup(1,0);f.terminal(0,"failed");var staged=service.stage(f.ref);var original=SourceDedupStageResult.createResolved(staged.original());assertThrows(RuntimeException.class,()->service.verifyStaged(staged.archiveRef(),original));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->service.verifyStaged(staged.archiveRef(),original)));
        f.f.db.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result' AND owner_job_id=? AND part_no=1",new byte[32],f.ref.ownerId());assertThrows(RuntimeException.class,()->service.stage(f.ref));assertThrows(RuntimeException.class,()->f.f.read(()->service.verifyStaged(staged.archiveRef(),original)));assertEquals(2,blocks());
        f.f.db.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",f.ref.ownerId());assertThrows(RuntimeException.class,()->f.f.read(()->{store.immutableOwner(f.ref.ownerId());return true;}));assertThrows(RuntimeException.class,()->f.f.p1.tx(()->{store.lockOwner(f.ref.ownerId());return true;}));
    }
}
