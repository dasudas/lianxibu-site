package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

class FrozenSourceStagePlanServiceTest {
    GenerationJdbcStoreTest fixture;FrozenSourceUniverseService universes;FrozenSourceStagePlanService service;
    MemberUniverse.Universe original;FrozenSourceUniverse.Reference universeRef;SourcePartitionPlanner.Plan plan;int actions,sourceReads;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() {
        fixture=fixture();fixture.fixture();original=FrozenSourceUniverseTest.universe(251,false);
        tx(()->{new JdbcJobRecords(fixture.source).ensureIntent(new JdbcJobRecords.Intent(FrozenSourceUniverseTest.OWNER,SourcePartitionPlannerTest.KEY,1,"news_dedup",LocalDate.of(2026,1,1)),fixture.clock.instant());return true;});
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(fixture.source),run->{sourceReads++;assertEquals(original.runId(),run);return original;},fixture.clock);
        universeRef=tx(()->universes.freeze(FrozenSourceUniverseTest.OWNER,original.runId()));
        plan=plan("Fictional original evidence".repeat(20));service=service();
    }
    SourcePartitionPlanner.Plan plan(String body) {
        return new SourcePartitionPlanner().plan(SourcePartitionPlannerTest.STAGE,SourcePartitionPlannerTest.KEY,1,original,universeRef,
                SourcePartitionPlannerTest.decisions(original),FrozenSourceStagePlanTest.scope(),(ids,n,root)->SourcePartitionPlannerTest.snapshot(universeRef,ids,n,root,body));
    }
    FrozenSourceStagePlanService service(){return new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(fixture.source),universes,fixture.clock,"fictional-owner");}
    <T> T tx(java.util.function.Supplier<T> work){return fixture.writes.execute(new SharedWriteExecutor.ActionContext("fictional-plan-action-"+actions++,FrozenSourceUniverseTest.OWNER,fixture.HASH),k->null,a->work.get());}
    FrozenSourceStagePlan.Reference freeze(){return tx(()->service.freeze(plan));}
    int count(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);}
    @Test void completeOriginalPlanSurvivesRestartWithoutRereadingSourcesOrCreatingAiCalls() {
        var reference=freeze();int count=count(),reads=sourceReads;assertEquals(plan,tx(()->service().read(reference)));assertEquals(plan,tx(()->service().read(reference.ownerId())));
        assertEquals(reference,freeze());assertEquals(count,count());assertEquals(reads,sourceReads);
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind IN ('ai_attempt','logical_task')",Integer.class));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void changedSameCountMessagesAndOrphanPartsCannotOverwriteTheOriginal() {
        var reference=freeze();int count=count();var prior=plan;plan=plan("Fictional changed evidence".repeat(20));
        assertThrows(RuntimeException.class,this::freeze);assertEquals(count,count());plan=prior;assertEquals(prior,tx(()->service.read(reference)));
        fixture.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");assertThrows(RuntimeException.class,this::freeze);
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));
    }
    @Test void missingExtraAndRehashedPlanBlocksRefuseTheOriginalReceipt() {
        var reference=freeze();String stamp=JdbcFrozenSourcePlans.stamp(reference.ownerId());
        fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),reference.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));
        clearPlan();var current=freeze();fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",current.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service.read(current)));
        clearPlan();var finalRef=freeze();
        tx(()->{new JdbcFrozenSourceUniverses(fixture.source).put(finalRef.ownerId(),stamp,999,"{}",fixture.clock.instant());return true;});
        assertThrows(RuntimeException.class,()->tx(()->service.read(finalRef)));
    }
    void clearPlan(){fixture.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=?",FrozenSourceUniverseTest.OWNER,JdbcFrozenSourcePlans.stamp(FrozenSourceUniverseTest.OWNER));}
    @Test void exactIntentScopeAndImmutableReceiptIdentityAreRequired() {
        var reference=freeze();int count=count();
        fixture.jdbc.update("UPDATE jobs SET generation=2 WHERE id=?",reference.ownerId());assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));fixture.jdbc.update("UPDATE jobs SET generation=1 WHERE id=?",reference.ownerId());
        fixture.jdbc.update("UPDATE jobs SET business_date='2026-01-02' WHERE id=?",reference.ownerId());assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));fixture.jdbc.update("UPDATE jobs SET business_date='2026-01-01' WHERE id=?",reference.ownerId());
        assertThrows(RuntimeException.class,()->tx(()->service.read("FICTIONAL-universe-owner")));
        var payload=fixture.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='source_stage_plan'",String.class);
        var changed=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(payload);changed.put("logical_key","fictional-replacement-key");
        fixture.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='source_stage_plan'",changed.toString(),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(changed)));
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));assertEquals(count,count());
    }
    @Test void outerFailureRollsBackAllPlanPartsAndReceiptAndEmptyPlanKeepsScope() {
        int before=count();assertThrows(RuntimeException.class,()->tx(()->{service.freeze(plan);throw new IllegalStateException("fictional outer failure");}));assertEquals(before,count());
        var decisions=original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).sorted(Comparator.comparing(SourcePartitionPlanner.Decision::itemId)).toList();
        plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),1,universeRef,decisions,List.of(),FrozenSourceStagePlanTest.scope());
        var reference=freeze();assertEquals(plan,tx(()->service().read(reference)));assertEquals(251,plan.decisions().size());assertTrue(plan.chunks().isEmpty());
    }
    @Test void badRowIdentitySizeUniverseProofAndNonWriterTransactionsRefuseReads() {
        var reference=freeze();assertThrows(RuntimeException.class,()->service.read(reference));assertThrows(RuntimeException.class,()->service.freeze(plan));
        var readOnly=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.source));readOnly.setReadOnly(true);
        assertThrows(RuntimeException.class,()->readOnly.execute(a->service.read(reference)));
        String stamp=JdbcFrozenSourcePlans.stamp(reference.ownerId());fixture.jdbc.update("UPDATE jobs SET id='fictional-replaced-plan-block' WHERE owner_job_id=? AND stamp_id=? AND part_no=1",reference.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));clearPlan();freeze();
        fixture.jdbc.update("UPDATE jobs SET payload_json=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1","{\"oversize\":\""+"x".repeat(262145)+"\"}",reference.ownerId(),stamp);
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));clearPlan();freeze();
        fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",reference.ownerId(),JdbcFrozenInputs.stableId("source-universe",original.runId()));
        assertThrows(RuntimeException.class,()->tx(()->service.read(reference)));
    }
    @Test void firstFreezeRefusesFutureScopeChangedIntentStateAndAlreadyExistingRoots() {
        int before=count();var originalPlan=plan;var originalScope=plan.scope();
        var decisions=original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).sorted(Comparator.comparing(SourcePartitionPlanner.Decision::itemId)).toList();
        var future=new SourcePartitionPlanner.Scope(originalScope.businessDate(),originalScope.cutoff().plusSeconds(1),originalScope.settings());
        plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),1,universeRef,decisions,List.of(),future);assertThrows(RuntimeException.class,this::freeze);assertEquals(before,count());plan=originalPlan;
        fixture.jdbc.update("UPDATE jobs SET state='queued' WHERE id=?",universeRef.ownerId());assertThrows(RuntimeException.class,this::freeze);assertEquals(before,count());fixture.jdbc.update("UPDATE jobs SET state='waiting_input' WHERE id=?",universeRef.ownerId());
        tx(()->{new JdbcJobRecords(fixture.source).createRoot(new JdbcJobRecords.Root(SourcePartitionPlanner.rootId(plan.logicalKey(),1,0),universeRef.ownerId(),plan.logicalKey(),1,0,"news_dedup",1),fixture.clock.instant());return true;});
        int withRoot=count();assertThrows(RuntimeException.class,this::freeze);assertEquals(withRoot,count());
    }
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.source));tx.setReadOnly(true);return tx.execute(status->work.get());}
    @Test void immutableReadWorksInReadOnlyTransactionWithoutSourceReadsOrNewRecords(){
        var reference=freeze();int count=count(),reads=sourceReads;
        assertEquals(plan,readonly(()->service().readImmutable(reference)));assertEquals(plan,readonly(()->service().readImmutable(reference.ownerId())));
        assertEquals(original,readonly(()->universes.readImmutable(universeRef)));assertEquals(count,count());assertEquals(reads,sourceReads);
        assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        assertThrows(RuntimeException.class,()->service.readImmutable(reference));assertThrows(RuntimeException.class,()->universes.readImmutable(universeRef));
        assertThrows(RuntimeException.class,()->readonly(()->service.read(reference)));assertThrows(RuntimeException.class,()->readonly(()->universes.read(universeRef)));
    }
    @Test void immutableReadRequiresExactOriginalReferenceOwnerReceiptAndBothArchives(){
        var reference=freeze();assertThrows(RuntimeException.class,()->readonly(()->service.readImmutable(reference.ownerId().toUpperCase(Locale.ROOT))));
        var other=new FrozenSourceStagePlan.Reference(reference.ownerId(),"0".repeat(64),reference.indexHash());assertThrows(RuntimeException.class,()->readonly(()->service.readImmutable(other)));
        fixture.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);assertThrows(RuntimeException.class,()->readonly(()->service.readImmutable(reference)));
        clearPlan();var valid=freeze();
        fixture.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",valid.ownerId(),JdbcFrozenInputs.stableId("source-universe",original.runId()));
        assertThrows(RuntimeException.class,()->readonly(()->service.readImmutable(valid)));assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void immutableEmptyPlanRetainsActualScopeAndRefusesRehashedPartialBlocks(){
        var decisions=original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).sorted(Comparator.comparing(SourcePartitionPlanner.Decision::itemId)).toList();
        plan=new SourcePartitionPlanner.Plan(plan.stage(),plan.logicalKey(),1,universeRef,decisions,List.of(),FrozenSourceStagePlanTest.scope());var reference=freeze();
        assertEquals(plan,readonly(()->service().readImmutable(reference)));assertTrue(readonly(()->service.readImmutable(reference)).chunks().isEmpty());
        fixture.jdbc.update("UPDATE jobs SET payload_json='{}',payload_hash=? WHERE owner_job_id=? AND stamp_id=? AND part_no=1",HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse("{}"))),reference.ownerId(),JdbcFrozenSourcePlans.stamp(reference.ownerId()));
        assertThrows(RuntimeException.class,()->readonly(()->service.readImmutable(reference)));
    }
    FrozenSourceStagePlan.Reference find(){return readonly(()->service.findImmutableReference(FrozenSourceUniverseTest.OWNER,plan.logicalKey(),1,plan.stage(),plan.scope().businessDate()));}
    @Test void locatingOriginalPlanDistinguishesNotYetPreparedFromOriginalReadyArchive(){
        assertNull(find());var reference=freeze();int rows=count(),reads=sourceReads;
        assertEquals(reference,find());assertEquals(rows,count());assertEquals(reads,sourceReads);
        assertNull(readonly(()->service.findImmutableReference("fictional-missing-owner","fictional-missing-key",1,plan.stage(),plan.scope().businessDate())));
        assertThrows(RuntimeException.class,()->service.findImmutableReference(reference.ownerId(),plan.logicalKey(),1,plan.stage(),plan.scope().businessDate()));
        assertThrows(RuntimeException.class,()->tx(()->service.findImmutableReference(reference.ownerId(),plan.logicalKey(),1,plan.stage(),plan.scope().businessDate())));
    }
    @Test void locatorRejectsWrongGenerationDateStageKeyAndOwnerAliases(){
        freeze();int rows=count();
        assertThrows(RuntimeException.class,()->readonly(()->service.findImmutableReference(FrozenSourceUniverseTest.OWNER,plan.logicalKey(),2,plan.stage(),plan.scope().businessDate())));
        assertThrows(RuntimeException.class,()->readonly(()->service.findImmutableReference(FrozenSourceUniverseTest.OWNER,plan.logicalKey(),1,GenerationFlowService.Stage.NEWS_VALUE,plan.scope().businessDate())));
        assertThrows(RuntimeException.class,()->readonly(()->service.findImmutableReference(FrozenSourceUniverseTest.OWNER,plan.logicalKey(),1,plan.stage(),plan.scope().businessDate().plusDays(1))));
        assertThrows(RuntimeException.class,()->readonly(()->service.findImmutableReference(FrozenSourceUniverseTest.OWNER,"fictional-wrong-key",1,plan.stage(),plan.scope().businessDate())));
        fixture.jdbc.update("UPDATE jobs SET id='fictional-owner-alias' WHERE id=?",FrozenSourceUniverseTest.OWNER);
        assertThrows(RuntimeException.class,this::find);assertEquals(rows,count());
    }
    @Test void missingOwnerReceiptOrBlocksCannotHideOriginalOrphanFacts(){
        var reference=freeze();fixture.jdbc.update("DELETE FROM jobs WHERE id=?",reference.ownerId());assertThrows(RuntimeException.class,this::find);
        fixture.jdbc.update("DELETE FROM jobs WHERE id=?",JdbcFrozenSourcePlans.receiptId(reference.ownerId()));assertThrows(RuntimeException.class,this::find);
        clearPlan();assertNull(find());
        tx(()->{var records=new JdbcJobRecords(fixture.source);records.ensureIntent(new JdbcJobRecords.Intent(reference.ownerId(),plan.logicalKey(),1,"news_dedup",plan.scope().businessDate()),fixture.clock.instant());
            records.createRoot(new JdbcJobRecords.Root(SourcePartitionPlanner.rootId(plan.logicalKey(),1,0),reference.ownerId(),plan.logicalKey(),1,0,"news_dedup",1),fixture.clock.instant());return true;});
        assertThrows(RuntimeException.class,this::find);
    }
    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void immutableDateAndOriginalPlanLocatorAreIndependentOfJvmTimezone(String zone){
        var reference=freeze();var prior=TimeZone.getDefault();try{
            TimeZone.setDefault(TimeZone.getTimeZone(zone));assertEquals(reference,find());
            assertEquals(LocalDate.of(2026,1,1),readonly(()->new JdbcFrozenSourcePlans(fixture.source).immutableOwner(reference.ownerId())).businessDate());
        }finally{TimeZone.setDefault(prior);}
    }
}
