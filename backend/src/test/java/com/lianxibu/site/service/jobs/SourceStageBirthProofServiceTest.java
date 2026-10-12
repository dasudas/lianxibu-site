package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class SourceStageBirthProofServiceTest {
    GenerationJdbcStoreTest f;FrozenSourceStagePlanService archives;SourceStageBirthProofService service;int actions;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){
        f=fixture();f.fixture();var universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->{throw new AssertionError("birth must not query current source facts");},f.clock);
        archives=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");service=service();
    }
    SourceStageBirthProofService service(){return new SourceStageBirthProofService(new JdbcSourceStageBirth(f.source),new JdbcFrozenSourcePlans(f.source),archives);}
    <T>T tx(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-birth-"+actions++,"fictional-owner",null),k->null,a->work.get());}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(status->work.get());}
    SourceStageBirthProofTest.Archive archive(long generation,int members,boolean eligible){
        var archive=SourceStageBirthProofTest.archive(generation,members,eligible);var original=FrozenSourceUniverseTest.universe(members,false);var p=archive.plan();
        tx(()->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent(p.reference().ownerId(),p.logicalKey(),generation,"news_dedup",p.scope().businessDate()),f.clock.instant());
            var universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->original,f.clock);assertEquals(p.reference(),universes.freeze(p.reference().ownerId(),original.runId()));
            assertEquals(archive.ref(),new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner").freeze(p));return true;});return archive;
    }
    String root(SourceStageBirthProofTest.Archive archive,int part){
        var p=archive.plan();String id=SourcePartitionPlanner.rootId(p.logicalKey(),p.generation(),part);tx(()->new JdbcJobRecords(f.source).createRoot(new JdbcJobRecords.Root(id,p.reference().ownerId(),p.logicalKey(),p.generation(),part,"news_dedup",1),f.clock.instant()));return id;
    }
    @Test void actualZeroAndGrowthArchivesGiveStableReadOnlyProofWithoutNewRecords(){
        var previous=archive(1,0,false);var current=archive(2,5,true);String id=root(current,0);int count=f.count("1=1");var proof=tx(()->service.read(id));
        assertEquals(SourceStageBirthProof.create(id,current.plan(),current.ref(),previous.plan(),previous.ref()),proof);assertEquals(proof,readonly(()->service().read(id)));assertEquals(count,f.count("1=1"));
        var next=archive(3,251,true);String growth=root(next,1);assertEquals(1,readonly(()->service.read(growth)).previousStagePartCount());assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void existingOriginalPartCannotBeCalledNewbornEvenWhenItsPhysicalRootIsMissing(){
        archive(1,5,true);var current=archive(2,5,true);String id=root(current,0);int count=f.count("1=1");assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));assertEquals(count,f.count("1=1"));
    }
    @Test void previousOrphanRootContradictsAbsenceAndCannotBeIgnored(){
        var previous=archive(1,0,false);var current=archive(2,5,true);root(previous,0);String id=root(current,0);assertThrows(RuntimeException.class,()->tx(()->service.read(id)));
    }
    @Test void rawRootOwnerIdentityHashAndTransactionAreRequired(){
        archive(1,0,false);var current=archive(2,5,true);String id=root(current,0);
        assertThrows(RuntimeException.class,()->service.read(id));assertThrows(RuntimeException.class,()->readonly(()->service.read(id.toUpperCase(Locale.ROOT))));
        f.jdbc.update("UPDATE jobs SET intent_id='fictional-wrong-intent' WHERE id=?",id);assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));
        f.jdbc.update("UPDATE jobs SET intent_id=?,logical_key_hash=? WHERE id=?",current.plan().reference().ownerId(),new byte[32],id);assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));
        f.jdbc.update("UPDATE jobs SET logical_key_hash=?,task_type='daily_report' WHERE id=?",JdbcGenerationLineage.hash(current.plan().logicalKey()),id);assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));
    }
    @Test void missingPreviousReceiptCannotBeSubstitutedWithZeroOrCurrentFacts(){
        var previous=archive(1,0,false);var current=archive(2,5,true);String id=root(current,0);f.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenSourcePlans.receiptId(previous.ref().ownerId()));
        assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void rehashedOrMissingPreviousUniverseAndPlanRefuseReadOnlyProof(){
        var previous=archive(1,0,false);var current=archive(2,5,true);String id=root(current,0);
        f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(previous.ref().ownerId()));assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));
        var payload=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,JdbcFrozenSourcePlans.receiptId(previous.ref().ownerId()));f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(com.lianxibu.site.domain.protocol.CanonicalJson.parse(payload))),JdbcFrozenSourcePlans.receiptId(previous.ref().ownerId()));
        f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",previous.ref().ownerId(),JdbcFrozenInputs.stableId("source-universe",previous.plan().reference().runId()));assertThrows(RuntimeException.class,()->readonly(()->service.read(id)));
    }
}
