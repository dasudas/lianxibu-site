package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

class SourceValueFinalizationTest {
    SourceValueStageProofTest f;SourceValueFinalProofService proofs;SourceValueFinalArchiveService archives;SourceValueFinalReceiptService receipts;SourceRunAnalysisService summaries;SourceValueFinalizationService service;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){f=new SourceValueStageProofTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueFinalizationTest.this.fixture();}};f.install(5);install();}
    void install(){
        boolean h2=f.f.source instanceof org.h2.jdbcx.JdbcDataSource;for(String t:List.of("source_runs","sources"))f.f.jdbc.execute("DROP TABLE IF EXISTS "+t);String json=h2?"CLOB":"JSON",text=h2?"CLOB":"MEDIUMTEXT";
        f.f.jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(16))");f.f.jdbc.update("INSERT INTO sources VALUES(?,'news_tech')",f.original.sourceId());
        f.f.jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,manifest_hash BINARY(32),expected_count BIGINT,accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,activation_stage VARCHAR(16),delivery_state VARCHAR(32),analysis_state VARCHAR(32),revision BIGINT,job_refs_json "+json+",dedup_summary "+text+",analysis_summary "+text+",result_json "+json+",coverage_json "+json+")");var refs=tools.jackson.databind.json.JsonMapper.shared().createArrayNode().add(f.ref.ownerId());f.plan.chunks().forEach(c->refs.add(c.rootId()));f.f.jdbc.update("INSERT INTO source_runs VALUES(?,?,'daily',?,1,?,?,?,?,0,'production',?,'waiting_input',2,?,NULL,NULL,?,?)",f.original.runId(),f.original.sourceId(),java.sql.Date.valueOf(f.plan.scope().businessDate()),HexFormat.of().parseHex(f.original.manifestHash()),f.original.members().size(),f.original.accepted(),f.original.excluded(),f.original.members().isEmpty()?"empty_success":"complete",JdbcFrozenInputs.canonical(refs),"{\"fictional_collector\":true}","{}");
        proofs=new SourceValueFinalProofService(f.service,f.plans,f.universes);archives=new SourceValueFinalArchiveService(proofs,new JdbcSourceValueFinalBlocks(f.f.source),f.f.writes,new DataSourceTransactionManager(f.f.source),f.clock);receipts=new SourceValueFinalReceiptService(new JdbcSourceValueFinalReceipts(f.f.source));summaries=new SourceRunAnalysisService(new JdbcSourceRunAnalysis(f.f.source),receipts::prior);service=provider(f.f.writes,f.clock);
    }
    SourceValueFinalizationService provider(SharedWriteExecutor writes,Clock clock){return new SourceValueFinalizationService(proofs,archives,f.plans,f.universes,receipts,summaries,writes,new DataSourceTransactionManager(f.f.source),clock);}
    SourceValueFinalizationService.Committed read(){return f.readonly(()->service.readCommitted(f.ref));}
    String sourceText(String column){return f.f.jdbc.queryForObject("SELECT "+column+" FROM source_runs",String.class);}
    String intentState(){return f.f.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,f.ref.ownerId());}
    int seals(){return f.f.count("target_type='source_value_final_result'");}
    @Test void pendingCannotSealAndCompleteOriginalAtomicallyProjectsPartialSummaryAndIntent(){
        assertNull(service.finish(f.ref));assertNull(read());assertNull(sourceText("analysis_summary"));assertEquals(0,seals());f.complete(0);var sealed=service.finish(f.ref);var committed=read();assertEquals(sealed.json(),committed.receipt().json());assertEquals(committed.result().summary(),sourceText("analysis_summary"));assertTrue(sourceText("dedup_summary").contains("Fictional fragment summary"));assertEquals("partial",sourceText("analysis_state"));assertEquals("succeeded",intentState());assertEquals(3,f.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertEquals("{\"fictional_collector\":true}",JdbcFrozenInputs.canonical(CanonicalJson.parse(sourceText("result_json"))));assertEquals("{}",JdbcFrozenInputs.canonical(CanonicalJson.parse(sourceText("coverage_json"))));assertEquals(1,seals());int count=f.f.count("1=1");service=provider(f.f.writes,Clock.fixed(f.clock.instant().plusSeconds(100),ZoneOffset.UTC));assertEquals(sealed.json(),service.finish(f.ref).json());assertEquals(count,f.f.count("1=1"));assertEquals(f.clock.instant(),read().minimumObservationTime());assertEquals(1,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void zeroOriginalPlanCommitsProgramSummaryWithoutAnyAiRoot(){
        f.install(0);install();assertNotNull(service.finish(f.ref));assertEquals("no_input",intentState());assertEquals("succeeded",sourceText("analysis_state"));assertEquals("本来源没有可分析输入。",sourceText("analysis_summary"));assertEquals(0,f.f.count("record_kind='logical_task'"));assertNotNull(read());
    }
    @Test void finalReceiptFailureRollsBackBothProjectionsAndIntentButKeepsStagedBytes(){
        f.complete(0);f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_finalization_failure CHECK(target_type IS NULL OR target_type<>'source_value_final_result')");assertThrows(RuntimeException.class,()->service.finish(f.ref));assertEquals(0,seals());assertNull(sourceText("analysis_summary"));assertEquals("waiting_input",intentState());assertEquals(2,f.f.count("purpose='result' AND owner_job_id='"+f.ref.ownerId()+"'"));assertNull(read());f.f.jdbc.execute(f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_finalization_failure":"ALTER TABLE jobs DROP CHECK fictional_finalization_failure");assertNotNull(service.finish(f.ref));assertEquals(1,seals());
    }
    @Test void actualFinalCommitAckLossRecoversOneSourceUpdateAndOneOriginalSeal(){
        f.complete(0);var fault=new DataSourceTransactionManager(f.f.source){boolean lost;@Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s){super.doCommit(s);if(!lost&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()){lost=true;throw new org.springframework.transaction.TransactionSystemException("fictional finalization ACK lost");}}};var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.f.source),fault,()->25,n->{});var sealed=provider(writes,f.clock).finish(f.ref);assertEquals(sealed.json(),service.finish(f.ref).json());assertEquals(3,f.f.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertEquals(1,seals());assertEquals("succeeded",intentState());
    }
    @Test void badOriginalCurrentRunOrTerminalIntentCannotCreateASuccessfulSeal(){
        assertThrows(RuntimeException.class,()->f.tx(()->service.finish(f.ref)));f.complete(0);f.f.jdbc.update("UPDATE source_runs SET generation=2");assertThrows(RuntimeException.class,()->service.finish(f.ref));assertNull(sourceText("analysis_summary"));assertEquals(0,seals());f.f.jdbc.update("UPDATE source_runs SET generation=1");f.f.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",f.ref.ownerId());assertThrows(RuntimeException.class,()->service.finish(f.ref));assertEquals(0,seals());assertNull(sourceText("analysis_summary"));
    }
    @Test void rehashedSealMetadataAndChangedArchiveFailTheFullCommittedProof(){
        f.complete(0);var sealed=service.finish(f.ref);var altered=(ObjectNode)CanonicalJson.parse(sealed.json());altered.put("analysis_summary_hash","c".repeat(64));String raw=JdbcFrozenInputs.canonical(altered);f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),sealed.position().receiptId());assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->service.finish(f.ref));f.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",sealed.json(),JdbcGenerationLineage.hash(sealed.json()),sealed.position().receiptId());f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result' AND owner_job_id=? AND part_no=1",new byte[32],f.ref.ownerId());assertThrows(RuntimeException.class,this::read);assertEquals(1,seals());assertNotNull(sourceText("analysis_summary"));
    }
}
