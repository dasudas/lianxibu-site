package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.sources.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class NewsSourcePlanPreparationTest {
    GenerationJdbcStoreTest f;CollectionHandoffProofService collections;FrozenSourceUniverseService universes;FrozenSourceStagePlanService plans;
    NewsSourcePlanPreparationService preparation;NewsDedupEligibilityService eligibility;NewsCandidateHistoryService history;SourceMemberEvidenceService members;
    CollectionHandoffProofService.Proof collection;int actions,settingsReads;boolean allowed=true;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=fixture();f.fixture();var sourceFixture=new SourceRunUniverseServiceTest(){@Override protected javax.sql.DataSource database(){return f.source;}void initialize(){setup();}};sourceFixture.initialize();
        String json=f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.jdbc.execute("ALTER TABLE source_runs ADD finished_at TIMESTAMP(6)");
        for(String column:List.of("category VARCHAR(16)","entity_id VARCHAR(64)","state VARCHAR(32)","projection_decision VARCHAR(16)","effective_valid INT","revision BIGINT","target_business_date DATE","created_at TIMESTAMP(6)","evidence_json "+json,"evidence_hash BINARY(32)"))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        var payload=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);payload.put("published_at","2026-01-01T08:00:00.000000Z");payload.put("time_basis","published");String body=JdbcFrozenInputs.canonical(payload);
        f.jdbc.update("UPDATE source_runs SET finished_at=?,config_snapshot_json='{\"parser_version\":\"fictional-parser-v1\"}'",java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)));
        f.jdbc.update("UPDATE source_run_items SET category='news_tech',state=CASE WHEN terminal_state='accepted' THEN 'pending' ELSE 'excluded' END,projection_decision='initialized',effective_valid=1,revision=2,created_at=?,evidence_json=?,evidence_hash=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(2)),body,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));
        f.jdbc.execute("DROP TABLE IF EXISTS analyzed_entities");f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),title VARCHAR(512),summary TEXT,revision BIGINT,first_recorded_at TIMESTAMP(6),analysis_state VARCHAR(32),legacy_flag INT)");
        var sourceHandoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);
        tx(()->{sourceHandoff.terminal("fictional-run");return true;});collections=new CollectionHandoffProofService(f.source,sourceHandoff);collection=tx(()->collections.read("fictional-run"));
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),new SourceRunUniverseService(new JdbcSourceRunUniverse(f.source),sourceHandoff),f.clock);
        plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source));
        eligibility=new NewsDedupEligibilityService(new JdbcNewsDedupEligibility(f.source),members);history=new NewsCandidateHistoryService(new JdbcNewsCandidateHistory(f.source),f.clock);preparation=service();
    }
    NewsSourcePlanPreparationService service(){return new NewsSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.source),universes,plans,collections,eligibility,history,()->{settingsReads++;return new FrozenGenerationSettings(1,List.of(),List.of());},()->allowed);}
    <T>T tx(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-source-flow-"+actions++,"fictional-run",null),k->null,a->work.get());}
    SourcePartitionPlanner.Plan prepare(){return tx(()->preparation.prepare(collection.intentId(),"fictional-run"));}
    @Test void realOriginalProvidersFreezeAPlanThenPrepareAndStrictlyDispatchWithoutACall(){
        var plan=prepare();assertEquals(1,plan.chunks().size());assertEquals(2,plan.decisions().size());assertEquals(0,f.count("record_kind IN ('logical_task','ai_attempt')"));
        var chunk=plan.chunks().getFirst();var part=new SourceStagePreparationService(f.source,plans,universes,collections::read,f.clock,"fictional-owner");var result=tx(()->part.preparePart(collection.intentId(),0,"fictional-permanent-part"));
        assertEquals(chunk.requestJson(),result.requestJson());assertEquals(chunk.snapshotJson(),result.snapshotJson());assertEquals(1,f.count("record_kind='ai_attempt' AND state='prepared'"));
        var inputs=new JdbcFrozenInputs(f.source);var dispatch=new DispatchReadinessService(new JdbcDispatchInputs(f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"fictional-worker"),mock(ContentReadService.class),members,target->{throw new AssertionError("inline source facts have no synthetic content references");},collections::read,new SourcePartitionProofService(plans),true);
        assertTrue(tx(()->dispatch.ready(chunk.rootId())));assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void restartReplaysTheArchiveWithoutReadingNewQualificationHistoryOrSettings(){
        var original=prepare();int count=f.count("1=1"),read=settingsReads;
        f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");f.jdbc.update("UPDATE source_run_items SET effective_valid=0,revision=3");f.jdbc.execute("DROP TABLE analyzed_entities");
        preparation=service();assertEquals(original,prepare());assertEquals(read,settingsReads);assertEquals(count,f.count("1=1"));
    }
    @Test void missingEvidenceAndOuterFailureRollbackAllNewUniversePlanPartsAndReceipts() throws Exception {
        int count=f.count("1=1");f.jdbc.update("UPDATE source_run_items SET evidence_hash=? WHERE terminal_state='accepted'",new byte[32]);assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.count("1=1"));
        var body=f.jdbc.queryForObject("SELECT evidence_json FROM source_run_items WHERE terminal_state='accepted'",String.class);f.jdbc.update("UPDATE source_run_items SET evidence_hash=? WHERE terminal_state='accepted'",java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(CanonicalJson.parse(body))));
        assertThrows(RuntimeException.class,()->tx(()->{preparation.prepare(collection.intentId(),"fictional-run");throw new IllegalStateException("fictional outer failure");}));assertEquals(count,f.count("1=1"));assertNotNull(prepare());
    }
    @Test void aliasWrongRunNonWriterAndCapacityGateRefuseBeforeNewPreparation(){
        int count=f.count("1=1");assertThrows(RuntimeException.class,()->preparation.prepare(collection.intentId(),"fictional-run"));
        assertThrows(RuntimeException.class,()->tx(()->preparation.prepare(collection.intentId().toUpperCase(Locale.ROOT),"fictional-run")));
        assertThrows(RuntimeException.class,()->tx(()->preparation.prepare(collection.intentId(),"fictional-wrong-run")));allowed=false;
        assertThrows(GenerationFlowService.CapacityGuarded.class,this::prepare);assertEquals(count,f.count("1=1"));assertEquals(0,settingsReads);
    }
    @Test void aDamagedPermanentReceiptNeverFallsBackToNewFacts(){
        prepare();int count=f.count("1=1"),read=settingsReads;f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.count("1=1"));assertEquals(read,settingsReads);
    }
}
