package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceStageBirthPreparationTest {
    SourceStageBirthProofServiceTest f;SourceStagePreparationService preparation;FrozenSourceUniverseService universes;
    SourceStageBirthProofServiceTest fixture(){return new SourceStageBirthProofServiceTest();}
    @BeforeEach void setup(){f=fixture();f.setup();universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.f.source),run->{throw new AssertionError("original archive only");},f.f.clock);preparation=preparation(true);}
    SourceStagePreparationService preparation(boolean births){return new SourceStagePreparationService(f.f.source,f.archives,universes,run->{throw new AssertionError("value has no fabricated collector proof");},f.f.clock,"fictional-owner",births?f.service::read:null);}
    SourceStageBirthProofTest.Archive archive(long generation,int members,boolean eligible){
        var raw=SourceStageBirthProofTest.archive(generation,members,eligible);var original=FrozenSourceUniverseTest.universe(members,false);var ref=raw.plan().reference();
        var plan=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,raw.plan().logicalKey(),generation,original,ref,raw.plan().decisions(),raw.plan().scope(),(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional original value evidence"));node.put("generation",generation);node.put("stage","news_value");var context=(ObjectNode)node.get(FrozenApplicationContext.FIELD);context.put("generation",generation);context.put("stage","news_value");return JdbcFrozenInputs.canonical(node);
        });
        var frozen=f.tx(()->{new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(ref.ownerId(),plan.logicalKey(),generation,"news_value",plan.scope().businessDate()),f.f.clock.instant());
            var source=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.f.source),run->original,f.f.clock);assertEquals(ref,source.freeze(ref.ownerId(),original.runId()));return new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.f.source),source,f.f.clock,"fictional-owner").freeze(plan);});
        return new SourceStageBirthProofTest.Archive(plan,frozen);
    }
    Frozen prepare(SourceStageBirthProofTest.Archive archive,int part,String action){return f.tx(()->preparation.preparePart(archive.ref().ownerId(),part,action));}
    @Test void zeroPreviousPlanPreparesExactV2RequestAndReadonlyReplayHasNoNewAttempt(){
        archive(1,0,false);var current=archive(2,5,true);var result=prepare(current,0,"fictional-born-prepare");int count=f.f.count("1=1");
        assertEquals(current.plan().chunks().getFirst().requestJson(),result.requestJson());assertEquals(current.plan().chunks().getFirst().snapshotJson(),result.snapshotJson());
        assertEquals(result,f.readonly(()->preparation.committedPart(current.ref().ownerId(),0,"fictional-born-prepare")));assertEquals(result,prepare(current,0,"fictional-born-prepare"));assertEquals(count,f.f.count("1=1"));
        assertEquals(1,f.f.count("record_kind='ai_attempt' AND state='prepared'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void growthUsesOriginalExistingPredecessorAndOnlyNewPartGetsV2(){
        var previous=archive(1,11,true);var old=prepare(previous,0,"fictional-old-part");var current=archive(2,251,true);
        var continued=prepare(current,0,"fictional-next-existing");
        var born=prepare(current,1,"fictional-next-born");
        assertEquals(old.ownerId(),f.f.jdbc.queryForObject("SELECT supersedes_job_id FROM jobs WHERE id=?",String.class,continued.ownerId()));assertNull(f.f.jdbc.queryForObject("SELECT supersedes_job_id FROM jobs WHERE id=?",String.class,born.ownerId()));
        assertEquals(current.plan().chunks().get(1).requestJson(),born.requestJson());assertEquals(3,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void actualCommitAckLossRecoversOnePreparedAttemptThroughReadonlyLookup(){
        archive(1,0,false);var current=archive(2,5,true);String action="fictional-born-ack-loss";
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional commit ACK lost");}}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),manager,()->25,n->{});
        var result=writes.execute(new SharedWriteExecutor.ActionContext(action,current.ref().ownerId(),null),key->preparation.committedPart(current.ref().ownerId(),0,key),a->preparation.preparePart(current.ref().ownerId(),0,a.actionKey()));
        assertEquals(current.plan().chunks().getFirst().requestJson(),result.requestJson());assertEquals(1,f.f.count("record_kind='logical_task'"));assertEquals(1,f.f.count("record_kind='ai_attempt'"));assertEquals(1,f.f.count("target_type='generation_prepare'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void oldConstructorWithoutBirthAndMissingPreviousArchiveCannotPrepareANewborn(){
        var current=archive(2,5,true);int count=f.f.count("1=1");assertThrows(RuntimeException.class,()->prepare(current,0,"fictional-missing-archive"));assertEquals(count,f.f.count("1=1"));
        archive(1,0,false);count=f.f.count("1=1");preparation=preparation(false);assertThrows(RuntimeException.class,()->prepare(current,0,"fictional-no-birth"));assertEquals(count,f.f.count("1=1"));
    }
    @Test void readonlyWrongActionPartAndDamagedPreviousProofNeverRebuild(){
        var previous=archive(1,0,false);var current=archive(2,251,true);var result=prepare(current,0,"fictional-original-key");
        assertNull(f.readonly(()->preparation.committedPart(current.ref().ownerId(),0,"fictional-not-committed")));assertThrows(RuntimeException.class,()->f.readonly(()->preparation.committedPart(current.ref().ownerId(),1,"fictional-original-key")));
        assertThrows(RuntimeException.class,()->preparation.committedPart(current.ref().ownerId(),0,"fictional-original-key"));
        f.f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",previous.ref().ownerId(),JdbcFrozenSourcePlans.stamp(previous.ref().ownerId()));assertThrows(RuntimeException.class,()->f.readonly(()->preparation.committedPart(current.ref().ownerId(),0,"fictional-original-key")));
        assertEquals(1,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(current.plan().chunks().getFirst().requestJson(),result.requestJson());
    }
    @Test void outerRollbackRemovesAllNewRootInputAttemptAndLineage(){
        archive(1,0,false);var current=archive(2,5,true);int count=f.f.count("1=1");assertThrows(RuntimeException.class,()->f.tx(()->{preparation.preparePart(current.ref().ownerId(),0,"fictional-born-rollback");throw new IllegalStateException("fictional outer rollback");}));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.count("record_kind='logical_task' OR record_kind='ai_attempt'"));assertNotNull(prepare(current,0,"fictional-born-retry"));
    }
}
