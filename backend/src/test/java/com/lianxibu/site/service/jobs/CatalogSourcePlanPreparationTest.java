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

class CatalogSourcePlanPreparationTest {
    GenerationJdbcStoreTest f;CollectionHandoffProofService collections;FrozenSourceUniverseService universes;FrozenSourceStagePlanService plans;
    CatalogSourcePlanPreparationService preparation;CatalogDedupEligibilityService eligibility;CatalogCandidateHistoryService history;SourceMemberEvidenceService members;
    CollectionHandoffProofService.Proof collection;int actions,settingsReads;boolean allowed=true;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {
        f=fixture();f.fixture();var sourceFixture=new SourceRunUniverseServiceTest(){@Override protected javax.sql.DataSource database(){return f.source;}void initialize(){setup();}};sourceFixture.initialize();
        String json=f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.jdbc.execute("ALTER TABLE source_runs ADD finished_at TIMESTAMP(6)");
        for(String column:List.of("category VARCHAR(16)","entity_id VARCHAR(64)","state VARCHAR(32)","projection_decision VARCHAR(16)","effective_valid INT","revision BIGINT","target_business_date DATE","created_at TIMESTAMP(6)","evidence_json "+json,"evidence_hash BINARY(32)"))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        f.jdbc.update("UPDATE sources SET category='agent'");
        var payload=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","1").put("category","agent").put("title","Fictional agent release").put("source_url","https://example.invalid/fictional-agent");
        payload.putObject("identity").put("namespace","github_repository").put("key","fictional-agent");payload.putNull("version").putNull("published_at").putNull("updated_at");payload.put("summary","Fictional source summary").put("document_text","Fictional source document").put("truncated",false).put("parser_version","fictional-parser-v1").put("config_version",1);
        payload.putArray("evidence").addObject().put("url","https://example.invalid/fictional-agent").putNull("revision").putNull("path").putNull("file_hash").put("evidence_type","metadata");String body=JdbcFrozenInputs.canonical(payload);
        f.jdbc.update("UPDATE source_runs SET finished_at=?,config_snapshot_json='{\"parser_version\":\"fictional-parser-v1\"}'",java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)));
        f.jdbc.update("UPDATE source_run_items SET category='agent',state=CASE WHEN terminal_state='accepted' THEN 'pending' ELSE 'excluded' END,projection_decision='initialized',effective_valid=1,revision=2,created_at=?,evidence_json=?,evidence_hash=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(2)),body,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));
        f.jdbc.execute("DROP TABLE IF EXISTS analyzed_entities");f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,category VARCHAR(16),identity_key TEXT,identity_hash BINARY(32),title VARCHAR(512),summary TEXT,revision BIGINT,first_recorded_at TIMESTAMP(6),analysis_state VARCHAR(32),legacy_flag INT)");
        var sourceHandoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);
        tx(()->{sourceHandoff.terminal("fictional-run");return true;});collections=new CollectionHandoffProofService(f.source,sourceHandoff);collection=tx(()->collections.read("fictional-run"));
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),new SourceRunUniverseService(new JdbcSourceRunUniverse(f.source),sourceHandoff),f.clock);
        plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");members=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source));
        eligibility=new CatalogDedupEligibilityService(new JdbcNewsDedupEligibility(f.source),members);history=new CatalogCandidateHistoryService(f.source,"agent",f.clock);preparation=service();
    }
    CatalogSourcePlanPreparationService service(){return new CatalogSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.source),universes,plans,collections,eligibility,history::capture,"agent",()->{settingsReads++;return new FrozenGenerationSettings(1,List.of(),List.of());},()->allowed);}
    <T>T tx(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-source-flow-"+actions++,"fictional-run",null),k->null,a->work.get());}
    SourcePartitionPlanner.Plan prepare(){var captured=tx(()->preparation.capture(collection.intentId(),"fictional-run"));var built=preparation.build(captured);return tx(()->preparation.commit(built));}
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
        var captured=tx(()->preparation.capture(collection.intentId(),"fictional-run"));var built=preparation.build(captured);int afterCapture=f.count("1=1");
        assertThrows(RuntimeException.class,()->tx(()->{preparation.commit(built);throw new IllegalStateException("fictional outer failure");}));assertEquals(afterCapture,f.count("1=1"));assertNotNull(prepare());
    }
    @Test void aliasWrongRunNonWriterAndCapacityGateRefuseBeforeNewPreparation(){
        int count=f.count("1=1");assertThrows(RuntimeException.class,()->preparation.capture(collection.intentId(),"fictional-run"));
        assertThrows(RuntimeException.class,()->tx(()->preparation.capture(collection.intentId().toUpperCase(Locale.ROOT),"fictional-run")));
        assertThrows(RuntimeException.class,()->tx(()->preparation.capture(collection.intentId(),"fictional-wrong-run")));allowed=false;
        assertThrows(GenerationFlowService.CapacityGuarded.class,this::prepare);assertEquals(count,f.count("1=1"));assertEquals(0,settingsReads);
    }
    @Test void changedFactsAfterOutsideBuildCannotCommitOriginalPlanAndTokensAreInstanceBound(){
        var captured=tx(()->preparation.capture(collection.intentId(),"fictional-run"));var built=preparation.build(captured);
        var foreign=service();assertThrows(RuntimeException.class,()->foreign.build(captured));assertThrows(RuntimeException.class,()->tx(()->foreign.commit(built)));
        f.jdbc.update("UPDATE source_run_items SET revision=3 WHERE terminal_state='accepted'");int count=f.count("1=1");assertThrows(RuntimeException.class,()->tx(()->preparation.commit(built)));assertEquals(count,f.count("1=1"));assertEquals(0,f.count("target_type='source_stage_plan'"));
        f.jdbc.update("UPDATE source_run_items SET revision=2 WHERE terminal_state='accepted'");assertNotNull(tx(()->preparation.commit(built)));
    }
    @Test void pureBuildRejectsOuterTransactionsAndRestartRecoversTheActualOriginalReceipt(){
        var captured=tx(()->preparation.capture(collection.intentId(),"fictional-run"));assertThrows(RuntimeException.class,()->tx(()->preparation.build(captured)));var built=preparation.build(captured);
        var original=tx(()->preparation.commit(built));int count=f.count("1=1"),read=settingsReads;preparation=service();
        var ro=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source));ro.setReadOnly(true);
        assertEquals(original,ro.execute(a->preparation.committed(collection.intentId(),"fictional-run")));assertEquals(count,f.count("1=1"));assertEquals(read,settingsReads);assertEquals(0,f.count("record_kind='ai_attempt'"));
    }
    @Test void aDamagedPermanentReceiptNeverFallsBackToNewFacts(){
        prepare();int count=f.count("1=1"),read=settingsReads;f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_plan'",new byte[32]);assertThrows(RuntimeException.class,this::prepare);assertEquals(count,f.count("1=1"));assertEquals(read,settingsReads);
    }
}
