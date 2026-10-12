package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class SourceValueStageProofTest {
    GenerationJdbcStoreTest f;SourceValueStageResultTest pure;SourcePartitionPlanner.Plan plan;MemberUniverse.Universe original;
    FrozenSourceStagePlanService plans;FrozenSourceUniverseService universes;SourceValueStageProofService service;FrozenSourceStagePlan.Reference ref;Clock clock;int actions;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    SourcePartitionPlanner.Plan originalPlan(NewsValueSourcePlanBuilderTest.Fixture x){return pure.plan(x);}
    @BeforeEach void setup(){install(5);}
    void install(int count){
        f=fixture();f.fixture();boolean h2=f.source instanceof org.h2.jdbcx.JdbcDataSource;if(h2)f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");
        f.jdbc.execute("ALTER TABLE jobs ADD result_hash BINARY(32)");f.jdbc.execute("ALTER TABLE jobs ADD result_json "+(h2?"CLOB":"JSON"));f.jdbc.execute("ALTER TABLE jobs ADD application_state VARCHAR(32)");
        for(String column:List.of("applied_at","acted_at"))f.jdbc.execute(h2?"ALTER TABLE jobs ALTER COLUMN "+column+" TIMESTAMP(6)":"ALTER TABLE jobs MODIFY "+column+" TIMESTAMP(6) NULL");
        pure=new SourceValueStageResultTest();var x=pure.f.fixture(count);original=x.universe();plan=originalPlan(x);clock=Clock.fixed(pure.f.cutoff.plusSeconds(2),ZoneOffset.UTC);
        var writer=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->original,clock);
        var validated=FrozenSourceStagePlanService.prevalidate(plan,original);
        tx(()->{
            var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent(plan.reference().ownerId(),plan.logicalKey(),1,"news_value",plan.scope().businessDate()),clock.instant());assertEquals(plan.reference(),writer.freeze(plan.reference().ownerId(),original.runId()));
            ref=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),writer,clock,"fictional-owner").freezeValidated(validated);new JdbcSourceStageAvailability(f.source).save(x.dependency().observationKeyJson(),x.dependency().observedAt());
            return true;
        });
        for(var c:plan.chunks())tx(()->{
            new JdbcJobRecords(f.source).createRoot(new JdbcJobRecords.Root(c.rootId(),plan.reference().ownerId(),plan.logicalKey(),1,c.stagePart(),"news_value",2),clock.instant());
            new JdbcFrozenInputs(f.source).save(c.rootId(),c.snapshotJson(),c.inputHash(),clock.instant());return true;
        });
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),run->{throw new AssertionError("current source read forbidden");},clock);plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,clock,"fictional-owner");service=provider();
    }
    SourceValueStageProofService provider(){var dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.source));return new SourceValueStageProofService(plans,universes,new SourceValueResultProofService(new JdbcSourceDedupResults(f.source),plans,universes,dependencies),dependencies);}
    <T>T tx(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-value-stage-"+actions++,"fictional-owner",null),k->null,a->work.get());}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    SourceValueStageResult read(){return readonly(()->service.read(ref));}
    void complete(int number){var c=plan.chunks().get(number);var proof=pure.proof(plan,original,c);var input=FrozenApplicationContext.fromJson(CanonicalJson.parse(c.snapshotJson()).get(FrozenApplicationContext.FIELD)).input(c.rootId(),"fictional-value-stage",c.inputHash(),0);String envelope=ResultApplicationService.providerEnvelope(input,proof.result().payloadJson());String attempt=JdbcFrozenInputs.stableId("attempt",c.rootId()+":1");tx(()->{
        new JdbcJobRecords(f.source).prepareAttempt(new JdbcJobRecords.Attempt(attempt,c.rootId(),1,c.requestJson(),c.rootId(),c.inputHash()),clock.instant());f.jdbc.update("UPDATE jobs SET state='running' WHERE id=?",attempt);f.jdbc.update("UPDATE jobs SET state='running',call_count=1 WHERE id=?",c.rootId());var state=new JdbcResultState(f.source);state.retain(state.root(c.rootId()),attempt,proof.result().resultHash(),envelope,clock.instant());
        var handoff=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",c.rootId()).put("input_hash",c.inputHash()).put("result_hash",proof.result().resultHash()).put("stage","news_value").put("generation",plan.generation()).put("stage_part",number);handoff.set("payload",CanonicalJson.parse(proof.result().payloadJson()));handoff.set("members",JsonMapper.shared().valueToTree(proof.members()));new JdbcMemberStageResults(f.source).save(c.rootId(),handoff,clock.instant());f.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),c.rootId());return true;
    });}
    @Test void actualOriginalMultipleFragmentsMustAllBeAppliedBeforeCompleteOutput(){
        install(501);assertEquals(2,plan.chunks().size());assertNull(read());complete(0);assertNull(read());complete(1);int count=f.count("1=1");var result=read();assertEquals(501,result.decisions().size());assertEquals(2,result.fragments().size());assertEquals(plan.decisions().stream().filter(d->d.reason()==SourcePartitionPlanner.Reason.ELIGIBLE).count(),result.members().size());assertEquals(count,f.count("1=1"));assertEquals(2,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void actualEmptyOriginalPlanStillRequiresItsNativeP1Observation(){
        install(0);var result=read();assertTrue(result.members().isEmpty());assertEquals(0,f.count("record_kind='logical_task'"));f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void restartAndLaterIntentKeepExactlyTheOriginalWholeProof(){
        complete(0);var result=read();tx(()->new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",plan.logicalKey()+":2"),plan.logicalKey(),2,"news_value",plan.scope().businessDate()),clock.instant()));int count=f.count("1=1");service=provider();assertEquals(result.json(),read().json());assertEquals(result.hash(),read().hash());assertEquals(count,f.count("1=1"));
    }
    @Test void exactExternalReferenceArchiveAndFragmentIdentityAreRequired(){
        complete(0);assertNotNull(read());assertThrows(RuntimeException.class,()->readonly(()->service.read(new FrozenSourceStagePlan.Reference(ref.ownerId(),"b".repeat(64),ref.indexHash()))));assertThrows(RuntimeException.class,()->readonly(()->service.read(new FrozenSourceStagePlan.Reference(ref.ownerId().toUpperCase(Locale.ROOT),ref.planHash(),ref.indexHash()))));
        f.jdbc.update("DELETE FROM jobs WHERE id=?",plan.chunks().getFirst().rootId());assertThrows(RuntimeException.class,this::read);
    }
    @Test void onlyReadonlyAndCompleteOriginalArchivesCanProduceAResult(){
        assertThrows(RuntimeException.class,()->service.read(ref));assertThrows(RuntimeException.class,()->tx(()->service.read(ref)));complete(0);f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(ref.ownerId()));assertThrows(RuntimeException.class,this::read);
    }
}
