package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

class SourceStagePreparationTest {
    FrozenSourceStagePlanServiceTest f;SourceStagePreparationService service;
    FrozenSourceStagePlanServiceTest fixture(){return new FrozenSourceStagePlanServiceTest();}
    @BeforeEach void setup(){f=fixture();f.setup();f.fixture.jdbc.update("UPDATE jobs SET task_type='news_value' WHERE id=?",f.universeRef.ownerId());
        rebuild((node,n)->{});f.freeze();service=service(f.fixture.clock);}
    void rebuild(java.util.function.BiConsumer<ObjectNode,Long> change){
        f.plan=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,SourcePartitionPlannerTest.KEY,1,f.original,f.universeRef,
            SourcePartitionPlannerTest.decisions(f.original),FrozenSourceStagePlanTest.scope(),(ids,n,root)->{
                var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(f.universeRef,ids,n,root,"Fictional original material"));
                node.put("stage","news_value");((ObjectNode)node.get(FrozenApplicationContext.FIELD)).put("stage","news_value");change.accept(node,n);return JdbcFrozenInputs.canonical(node);
            });
    }
    SourceStagePreparationService service(Clock clock){return new SourceStagePreparationService(f.fixture.source,f.service,f.universes,run->{throw new AssertionError("value cannot invent a collection");},clock,"fictional-owner");}
    Frozen prepare(long part,String action){return f.tx(()->service.preparePart(f.universeRef.ownerId(),part,action));}
    @Test void bothPartsUseTheExactArchiveAndRestartReplaysWithoutNewAttemptsOrEligibility(){
        int reads=f.sourceReads;var first=prepare(0,"fictional-prepare-0");var second=prepare(1,"fictional-prepare-1");
        for(var result:List.of(first,second)){
            var chunk=f.plan.chunks().stream().filter(c->c.rootId().equals(result.ownerId())).findFirst().orElseThrow();
            assertEquals(chunk.snapshotJson(),result.snapshotJson());assertEquals(chunk.requestJson(),result.requestJson());assertEquals(chunk.inputHash(),result.inputHash());
            assertEquals("queued",result.state());assertEquals(f.universeRef.ownerId(),f.fixture.jdbc.queryForObject("SELECT intent_id FROM jobs WHERE id=?",String.class,result.ownerId()));
        }
        assertEquals(2,f.fixture.count("record_kind='ai_attempt'"));assertEquals(2,f.fixture.count("record_kind='logical_task' AND max_attempts=3 AND call_count=0"));
        int count=f.count();service=service(Clock.fixed(Instant.parse("2026-01-03T04:00:00Z"),ZoneOffset.UTC));
        assertEquals(first,prepare(0,"fictional-prepare-0"));assertEquals(second,prepare(1,"fictional-prepare-1"));
        assertEquals(count,f.count());assertEquals(reads,f.sourceReads);
    }
    @Test void closedWindowUsesTheExistingNextWindowAndKeepsItOnReplay(){
        service=service(Clock.fixed(Instant.parse("2026-01-02T01:00:00Z"),ZoneOffset.UTC));var original=prepare(0,"fictional-window");
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"),original.eligibleAt());
        service=service(Clock.fixed(Instant.parse("2026-01-03T04:00:00Z"),ZoneOffset.UTC));assertEquals(original,prepare(0,"fictional-window"));
    }
    @Test void noWriterMissingPlanBadActionAliasAndOutOfBoundsNeverCreateRoots(){
        int count=f.count();assertThrows(RuntimeException.class,()->service.preparePart(f.universeRef.ownerId(),0,"fictional-no-tx"));
        var readOnly=new TransactionTemplate(new DataSourceTransactionManager(f.fixture.source));readOnly.setReadOnly(true);
        assertThrows(RuntimeException.class,()->readOnly.execute(a->service.preparePart(f.universeRef.ownerId(),0,"fictional-read-only")));
        for(long n:List.of(-1L,2L,10000L))assertThrows(RuntimeException.class,()->prepare(n,"fictional-bounds"));
        for(String key:Arrays.asList(null,"", "invalid action"))assertThrows(RuntimeException.class,()->prepare(0,key));
        assertThrows(RuntimeException.class,()->f.tx(()->service.preparePart("FICTIONAL-universe-owner",0,"fictional-alias")));
        assertEquals(count,f.count());f.fixture.jdbc.update("DELETE FROM jobs WHERE target_type='source_stage_plan'");
        assertThrows(RuntimeException.class,()->prepare(0,"fictional-missing"));assertEquals(0,f.fixture.count("record_kind='logical_task'"));
    }
    @Test void outerFailureRollsBackRootInputAttemptAndReceipts(){
        int count=f.count();assertThrows(RuntimeException.class,()->f.tx(()->{service.preparePart(f.universeRef.ownerId(),0,"fictional-rollback");throw new IllegalStateException("fictional outer failure");}));
        assertEquals(count,f.count());assertEquals(0,f.fixture.count("record_kind IN ('logical_task','ai_attempt')"));assertNotNull(prepare(0,"fictional-rollback"));
    }
    @Test void anActionCannotBeReassignedAndCorruptAttemptCannotBeRegenerated(){
        var original=prepare(0,"fictional-same-action");int count=f.count();assertThrows(RuntimeException.class,()->prepare(1,"fictional-same-action"));assertEquals(count,f.count());
        assertEquals(original,prepare(0,"fictional-same-action"));
        f.fixture.jdbc.update("UPDATE jobs SET request_json='{}' WHERE record_kind='ai_attempt'");
        assertThrows(RuntimeException.class,()->prepare(0,"fictional-same-action"));assertEquals(count,f.count());assertEquals(1,f.fixture.count("record_kind='ai_attempt'"));
    }
    @Test void emptyOrIncompleteMemberMetadataCannotManufacturePreparedTasks(){
        f.clearPlan();var original=f.plan;var decisions=f.original.members().stream().map(m->new SourcePartitionPlanner.Decision(m.itemId(),m.terminalState().equals("excluded")?SourcePartitionPlanner.Reason.COLLECTOR_EXCLUDED:SourcePartitionPlanner.Reason.NOT_AI_ELIGIBLE)).toList();
        f.plan=new SourcePartitionPlanner.Plan(original.stage(),original.logicalKey(),1,f.universeRef,decisions,List.of(),original.scope());f.freeze();
        assertThrows(RuntimeException.class,()->prepare(0,"fictional-empty"));
        f.clearPlan();rebuild((node,n)->((ObjectNode)node.get("members").get(0)).remove("completed_at"));f.freeze();
        assertThrows(RuntimeException.class,()->prepare(0,"fictional-no-metadata"));assertEquals(0,f.fixture.count("record_kind='logical_task'"));
    }
    @Test void originalDependenciesAreIndexedAndUnknownOnesRollback(){
        f.clearPlan();f.tx(()->{var records=new JdbcJobRecords(f.fixture.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-upstream-intent","fictional-upstream-key",1,"news_dedup",f.plan.scope().businessDate()),f.fixture.clock.instant());records.createRoot(new JdbcJobRecords.Root("fictional-upstream","fictional-upstream-intent","fictional-upstream-key",1,0,"news_dedup",1),f.fixture.clock.instant());return true;});
        var dep=new Dependency("fictional-upstream",Stage.NEWS_DEDUP,"succeeded",Instant.parse("2026-01-01T20:00:00Z"));
        rebuild((node,n)->node.set(FrozenDependencyManifest.FIELD,FrozenDependencyManifest.create(List.of(dep),List.of(dep.id()))));f.freeze();var result=prepare(0,"fictional-dependencies");
        assertEquals(1,f.fixture.count("purpose='dependency' AND dependency_id='fictional-upstream'"));
        assertEquals(List.of(dep),FrozenDependencyManifest.read(CanonicalJson.parse(result.snapshotJson()).get(FrozenDependencyManifest.FIELD)).dependencies());
        int count=f.count();f.fixture.jdbc.update("DELETE FROM jobs WHERE id='fictional-upstream'");
        assertThrows(RuntimeException.class,()->prepare(1,"fictional-unknown-dependency"));assertEquals(count-1,f.count());
    }
    @Test void aLaterGenerationCannotInventAnAbsentPredecessor(){
        String owner="fictional-second-intent";f.tx(()->{new JdbcJobRecords(f.fixture.source).ensureIntent(new JdbcJobRecords.Intent(owner,f.plan.logicalKey(),2,"news_value",f.plan.scope().businessDate()),f.fixture.clock.instant());return true;});
        var ref=f.tx(()->f.universes.freeze(owner,f.original.runId()));
        var next=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,f.plan.logicalKey(),2,f.original,ref,SourcePartitionPlannerTest.decisions(f.original),f.plan.scope(),(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional generation two"));node.put("generation",2);node.put("stage","news_value");var context=(ObjectNode)node.get(FrozenApplicationContext.FIELD);context.put("generation",2);context.put("stage","news_value");return JdbcFrozenInputs.canonical(node);
        });f.tx(()->f.service.freeze(next));int count=f.count();
        assertThrows(RuntimeException.class,()->f.tx(()->service.preparePart(owner,1,"fictional-no-predecessor")));assertEquals(count,f.count());assertEquals(0,f.fixture.count("record_kind='ai_attempt'"));
    }
    @Test void aValuePlanCapturedBeforeItsBarrierCannotGainReadinessFromALaterClock(){
        f.clearPlan();var scope=new SourcePartitionPlanner.Scope(f.plan.scope().businessDate(),Instant.parse("2026-01-01T20:00:00Z"),f.plan.scope().settings());
        f.plan=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,f.plan.logicalKey(),1,f.original,f.universeRef,SourcePartitionPlannerTest.decisions(f.original),scope,(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(f.universeRef,ids,n,root,"Fictional early input"));node.put("cutoff",scope.cutoff().toString());node.put("stage","news_value");((ObjectNode)node.get(FrozenApplicationContext.FIELD)).put("stage","news_value");return JdbcFrozenInputs.canonical(node);
        });f.freeze();assertThrows(RuntimeException.class,()->prepare(0,"fictional-early-value"));assertEquals(0,f.fixture.count("record_kind='logical_task'"));
    }
    @Test void dedupConsumesTheRealHandoffAndBindsItToTheExactArchivedUniverse(){
        var collection=new CollectionGenerationInputTest(){@Override CollectionHandoffIntegrationTest fixture(){return new CollectionHandoffIntegrationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceStagePreparationTest.this.f.fixture();}};}};
        collection.setup();var proof=collection.handoff();var base=collection.command(proof,false);var owned=collection.fixture.f;
        var raw=FrozenSourceUniverseTest.universe(2,false);var members=raw.members().stream().map(m->new com.lianxibu.site.domain.sources.MemberUniverse.Member(m.itemId(),m.memberId(),m.sourceItemId(),m.observationId(),m.payloadHash(),m.replacesObservationId(),m.partNo(),"accepted")).toList();
        var original=new com.lianxibu.site.domain.sources.MemberUniverse.Universe(proof.facts().runId(),proof.facts().sourceId(),proof.facts().manifestHash(),raw.parts(),members,2,0);
        var universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(owned.source),run->original,owned.clock);
        var plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(owned.source),universes,owned.clock,"fictional-owner");
        var action=new SharedWriteExecutor.ActionContext("fictional-source-prep",proof.intentId(),null);
        var ref=owned.writes.execute(action,k->null,a->universes.freeze(proof.intentId(),original.runId()));
        var plan=new SourcePartitionPlanner().plan(Stage.NEWS_DEDUP,base.logicalKey(),1,original,ref,SourcePartitionPlannerTest.decisions(original),FrozenSourceStagePlanTest.scope(),(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional real collection"));node.put("logical_key",base.logicalKey());node.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(List.of(proof)));return JdbcFrozenInputs.canonical(node);
        });owned.writes.execute(action,k->null,a->plans.freeze(plan));
        var preparation=new SourceStagePreparationService(owned.source,plans,universes,collection.proofs::read,owned.clock,"fictional-owner");
        var result=owned.writes.execute(action,k->null,a->preparation.preparePart(proof.intentId(),0,"fictional-dedup"));assertEquals(plan.chunks().getFirst().requestJson(),result.requestJson());
        assertEquals(List.of(proof),FrozenCollectionManifest.read(CanonicalJson.parse(result.snapshotJson()).get(FrozenCollectionManifest.FIELD)));assertEquals(1,owned.count("record_kind='ai_attempt' AND call_count=0"));
        owned.jdbc.update("UPDATE source_runs SET config_snapshot_json='{\"changed\":true}'");
        assertEquals(result,owned.writes.execute(action,k->null,a->preparation.preparePart(proof.intentId(),0,"fictional-dedup")));
        assertThrows(RuntimeException.class,()->owned.writes.execute(action,k->null,a->preparation.preparePart(proof.intentId(),0,"fictional-new-dedup")));assertEquals(1,owned.count("record_kind='ai_attempt'"));
    }
}
