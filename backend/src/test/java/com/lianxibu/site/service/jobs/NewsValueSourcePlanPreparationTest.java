package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NewsValueSourcePlanPreparationTest {
    NewsSourcePlanPreparationTest p1;GenerationJdbcStoreTest f;Clock clock;FrozenSourceStagePlanService plans;
    NewsValueSourcePlanPreparationService service;SourceDedupDependencyService dependencies;
    JsonNode profile;String intent;boolean allowed=true;int settingsReads;List<String> keywords=List.of();
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        p1=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return NewsValueSourcePlanPreparationTest.this.fixture();}};
        p1.setup();f=p1.f;var original=p1.prepare();var chunk=original.chunks().getFirst();boolean h2=f.source instanceof org.h2.jdbcx.JdbcDataSource;
        if(h2)f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");
        f.jdbc.execute("ALTER TABLE jobs ADD result_hash BINARY(32)");f.jdbc.execute("ALTER TABLE jobs ADD result_json "+(h2?"CLOB":"JSON"));f.jdbc.execute("ALTER TABLE jobs ADD application_state VARCHAR(32)");
        for(String column:List.of("applied_at","acted_at"))f.jdbc.execute(h2?"ALTER TABLE jobs ALTER COLUMN "+column+" TIMESTAMP(6)":"ALTER TABLE jobs MODIFY "+column+" TIMESTAMP(6) NULL");
        f.jdbc.execute("ALTER TABLE analyzed_entities ADD first_seen_business_date DATE");
        p1.tx(()->new SourceStagePreparationService(f.source,p1.plans,p1.universes,p1.collections::read,f.clock,"fictional-owner").preparePart(p1.collection.intentId(),0,"fictional-p1-permanent"));
        var input=FrozenApplicationContext.fromJson(CanonicalJson.parse(chunk.snapshotJson()).get(FrozenApplicationContext.FIELD)).input(chunk.rootId(),"fictional-proof",chunk.inputHash(),0);
        var binding=FrozenMemberBindings.read(CanonicalJson.parse(chunk.snapshotJson()),input).values().iterator().next();
        var body=JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","Fictional complete P1 summary");
        body.putArray("items").addObject().put("item_id",binding.itemId()).put("category","news_tech").put("decision","new").putNull("candidate_entity_id").put("reason","Fictional complete evidence").put("confidence","1").put("suggested_title","Fictional title").putNull("suggested_summary").put("reanalyze_recommended",false);
        String envelope=ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(body));var result=ResultApplicationService.validate(input,envelope);
        var universe=p1.tx(()->p1.universes.read(original.reference()));var member=universe.members().stream().filter(m->m.itemId().equals(binding.itemId())).findFirst().orElseThrow();
        p1.tx(()->{
            String attempt=JdbcFrozenInputs.stableId("attempt",chunk.rootId()+":1");f.jdbc.update("UPDATE jobs SET state='running' WHERE id=?",attempt);f.jdbc.update("UPDATE jobs SET state='running',call_count=1 WHERE id=?",chunk.rootId());
            var state=new JdbcResultState(f.source);state.retain(state.root(chunk.rootId()),attempt,result.resultHash(),envelope,f.clock.instant());
            var handoff=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",chunk.rootId()).put("input_hash",chunk.inputHash()).put("result_hash",result.resultHash()).put("stage","news_dedup").put("generation",original.generation()).put("stage_part",0);handoff.set("payload",body);
            handoff.putArray("members").add(JsonMapper.shared().valueToTree(new SourceMemberStageService.Fact(binding.itemId(),universe.runId(),universe.sourceId(),member.sourceItemId(),"news_tech",binding.newEntityId(),"ready",binding.expectedRevision()+2,original.scope().businessDate(),null,original.generation(),"production")));
            new JdbcMemberStageResults(f.source).save(chunk.rootId(),handoff,f.clock.instant());
            f.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=? WHERE id=?",Timestamp.from(f.clock.instant().truncatedTo(ChronoUnit.MICROS)),chunk.rootId());
            f.jdbc.update("UPDATE source_run_items SET state='ready',entity_id=?,revision=? WHERE id=?",binding.newEntityId(),binding.expectedRevision()+2,binding.itemId());
            f.jdbc.update("INSERT INTO analyzed_entities(id,category,identity_key,identity_hash,title,summary,revision,first_recorded_at,analysis_state,legacy_flag,first_seen_business_date) VALUES(?,'news_tech',?,?,?,?,0,?,'pending',0,?)",binding.newEntityId(),binding.identityKey(),JdbcEntityDedup.identityHash("news_tech",binding.identityKey()),binding.title(),binding.summary(),Timestamp.from(f.clock.instant().minusSeconds(1).truncatedTo(ChronoUnit.MICROS)),java.sql.Date.valueOf(original.scope().businessDate()));return true;
        });
        var fragments=new SourceDedupResultProofService(new JdbcSourceDedupResults(f.source),p1.plans,p1.universes);var whole=new SourceDedupStageProofService(p1.plans,p1.universes,fragments);
        var ref=readonly(()->FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(new JdbcFrozenSourcePlans(f.source).immutableReceipt(original.reference().ownerId()).payloadJson()).get("plan_ref"))));
        var observer=new SourceDedupStageAvailabilityService(whole,new JdbcSourceStageAvailability(f.source),f.writes,new DataSourceTransactionManager(f.source),f.clock);
        var observed=observer.capture(ref);assertNotNull(observed);clock=Clock.fixed(f.clock.instant().plusSeconds(2),ZoneOffset.UTC);
        profile=FrozenSourceDedupDependency.create(readonly(()->whole.read(ref)),observed,clock.instant().truncatedTo(ChronoUnit.MICROS)).toJson();
        String key=NewsValueSourcePlanBuilder.logicalKey(FrozenSourceDedupDependency.read(profile,clock.instant().truncatedTo(ChronoUnit.MICROS)));intent=JdbcFrozenInputs.stableId("intent",key+":1");
        p1.tx(()->new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent(intent,key,1,"news_value",original.scope().businessDate()),clock.instant()));
        plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),p1.universes,clock,"fictional-owner");dependencies=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.source));service=service();
    }
    NewsValueSourcePlanPreparationService service(){return new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.source),new JdbcFrozenSourceUniverses(f.source),p1.universes,plans,p1.collections,new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.source),new JdbcNewsValueEntities(f.source),p1.members,clock),dependencies,()->{settingsReads++;return new FrozenGenerationSettings(1,List.of(),keywords);},clock,()->allowed);}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    SourcePartitionPlanner.Plan prepare(){return p1.tx(()->service.prepare(intent,"fictional-run",profile));}
    @Test void actualOriginalProofAndCurrentQualificationFreezeP2WithoutCreatingCalls(){
        int count=f.count("record_kind IN ('logical_task','ai_attempt')");var plan=prepare();assertEquals(GenerationFlowService.Stage.NEWS_VALUE,plan.stage());assertEquals(2,plan.decisions().size());assertEquals(1,plan.chunks().size());assertEquals(1,settingsReads);
        var snapshot=CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());assertTrue(snapshot.get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0).get("eligible_high_value").asBoolean());
        assertEquals("partial",snapshot.get("coverage_state").asText());NewsValueInputProfile.complete(snapshot);assertEquals(profile,snapshot.get(FrozenSourceDedupDependency.FIELD));
        assertEquals(count,f.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(1,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(plan,readonly(()->service.committed(intent,"fictional-run")));
    }
    @Test void zeroCurrentQualificationStillPermanentlyStoresTheCompleteOriginalDependency(){
        f.jdbc.update("UPDATE source_run_items SET effective_valid=0");var plan=prepare();assertTrue(plan.chunks().isEmpty());assertEquals(2,plan.decisions().size());assertEquals(profile,CanonicalJson.parse(plan.scope().stageContextJson()).get(FrozenSourceDedupDependency.FIELD));
        assertEquals(plan,readonly(()->service.committed(intent,"fictional-run")));assertEquals(1,settingsReads);
    }
    @Test void preferencesAndOldEntityQualificationAreFrozenFromActualMetadata(){
        f.jdbc.update("UPDATE analyzed_entities SET first_seen_business_date=?",java.sql.Date.valueOf(LocalDate.of(2025,12,31)));var plan=prepare();assertFalse(CanonicalJson.parse(plan.chunks().getFirst().snapshotJson()).get(NewsValueInputProfile.ELIGIBILITY_FIELD).get("members").get(0).get("eligible_high_value").asBoolean());
    }
    @Test void blockedInputCreatesACompleteZeroPlanWithOriginalSettings(){
        keywords=List.of(CanonicalJson.parse(f.jdbc.queryForObject("SELECT evidence_json FROM source_run_items WHERE terminal_state='accepted'",String.class)).get("title").asText());var plan=prepare();assertTrue(plan.chunks().isEmpty());assertTrue(plan.decisions().stream().anyMatch(d->d.reason()==SourcePartitionPlanner.Reason.PREFERENCE_BLOCKED));assertEquals(keywords,plan.scope().settings().blockedKeywords());
    }
    @Test void restartAndCapacityClosureUseOnlyOriginalArchiveAndCommittedObservation(){
        var plan=prepare();int count=f.count("1=1");allowed=false;keywords=List.of("changed");f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");f.jdbc.execute("DROP TABLE source_run_items");f.jdbc.execute("DROP TABLE analyzed_entities");service=service();
        assertEquals(plan,p1.tx(()->service.prepare(intent,"fictional-run",null)));assertEquals(plan,readonly(()->service.committed(intent,"fictional-run")));assertEquals(1,settingsReads);assertEquals(count,f.count("1=1"));
    }
    @Test void missingObservationBadOriginalProfileAndWrongNativeIntentScopeNeverPrepare(){
        int count=f.count("1=1");assertThrows(RuntimeException.class,()->service.prepare(intent,"fictional-run",profile));assertThrows(RuntimeException.class,()->p1.tx(()->service.prepare(intent.toUpperCase(Locale.ROOT),"fictional-run",profile)));assertThrows(RuntimeException.class,()->p1.tx(()->service.prepare(intent,"fictional-other-run",profile)));
        var bad=profile.deepCopy();((tools.jackson.databind.node.ObjectNode)bad.get("stage_result")).put("logical_key","fictional-different");assertThrows(RuntimeException.class,()->p1.tx(()->service.prepare(intent,"fictional-run",bad)));
        allowed=false;assertThrows(GenerationFlowService.CapacityGuarded.class,this::prepare);allowed=true;f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.count("1=1"));assertEquals(0,settingsReads);
    }
    @Test void outerRollbackAndDamagedCommittedArchiveDoNotRebuildFromCurrentFacts(){
        int count=f.count("1=1");assertNull(readonly(()->service.committed(intent,"fictional-run")));assertThrows(RuntimeException.class,()->p1.tx(()->{service.prepare(intent,"fictional-run",profile);throw new IllegalStateException("fictional outer failure");}));assertEquals(count,f.count("1=1"));
        prepare();int read=settingsReads;f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(intent));assertThrows(RuntimeException.class,this::prepare);assertThrows(RuntimeException.class,()->readonly(()->service.committed(intent,"fictional-run")));assertEquals(read,settingsReads);
    }
    @Test void successfulCommitWithLostAckRecoversExactlyOnePlan(){
        var fault=new DataSourceTransactionManager(f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){super.doCommit(status);if(!lost&&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional ACK lost");}}};
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.source),fault,()->25,n->{});var plan=writes.execute(new SharedWriteExecutor.ActionContext("fictional-p2-ack",intent,null),key->service.committed(intent,"fictional-run"),a->service.prepare(intent,"fictional-run",profile));
        assertEquals(plan,prepare());assertEquals(1,settingsReads);assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id=?",Integer.class,JdbcFrozenSourcePlans.receiptId(intent)));
    }
}
