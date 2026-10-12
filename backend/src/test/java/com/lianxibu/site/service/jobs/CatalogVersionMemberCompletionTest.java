package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.sources.JdbcSourceMemberStages;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.SourceMemberStageService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real v2 native result/member stage completion; external model response remains fictional. */
class CatalogVersionMemberCompletionTest extends CatalogVersionDedupHandlerTest {
    MemberResultDownstream downstream;
    void completion(String category,boolean recommend){
        boot(category,recommend,true);String text=mysql()?"JSON":"CLOB",time=mysql()?"DATETIME(6)":"TIMESTAMP";
        for(String column:List.of("run_kind VARCHAR(32) DEFAULT 'daily'","generation BIGINT DEFAULT 1","job_refs_json "+text,"revision BIGINT DEFAULT 0"))f.jdbc.execute("ALTER TABLE source_runs ADD "+column);
        f.jdbc.update("UPDATE source_runs SET job_refs_json='[]'");
        for(String column:List.of("target_business_date DATE","analysis_job_id VARCHAR(64)","applied_at "+time))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        downstream=new MemberResultDownstream(new JdbcFrozenInputs(f.source),new JdbcMemberStageResults(f.source),new SourceMemberStageService(new JdbcSourceMemberStages(f.source),f.clock),f.clock,Map.of(category,new CatalogVersionJudgmentLedger(new JdbcCatalogVersionJudgments(f.source),category)));
        installCompletion(downstream);
    }
    void installCompletion(DedupResultHandler.Downstream completion){
        var batches=new CatalogVersionBatchPreflightService(f.source,f.tx,f.category);var versions=new CatalogVersionBatchJudgmentWriter(f.source,batches.permits(),new EntityAnalysisStateService(new JdbcEntityAnalysisState(f.source)),f.category,f.clock);
        var entities=new EntityDedupService(new JdbcEntityDedup(f.source),f.writes,new EntityDecisionJdbcLog(new JdbcEntityDecisions(f.source),"fictional-owner",f.clock),f.clock);
        install(new DedupResultHandler(entities,new JdbcFrozenInputs(f.source)::read,completion,batches,versions));
    }
    int fragments(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_member_stage'",Integer.class);}
    void unwritten(){
        assertEquals(0,fragments());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type IN ('catalog_version_judgment','entity_decision')",Integer.class));
        assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertNull(f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(0,f.jdbc.queryForObject("SELECT revision FROM source_runs",Integer.class));assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertEquals(2,f.sends);
    }
    @Test void FourCategoryFullNativeChainCompletesPermanentFragmentPointersAndReplaysWithoutCurrentProof(){
        for(String category:List.of("agent","skill","workflow","model")){
            completion(category,true);assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(5,f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id='fictional-current-item'",Integer.class));assertEquals(DEDUP_ROOT,f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(1,f.jdbc.queryForObject("SELECT revision FROM source_runs",Integer.class));
            var fragment=new JdbcMemberStageResults(f.source).read(DEDUP_ROOT);assertEquals("source-member-stage-v1",fragment.get("schema_version").asText());assertEquals("2",fragment.get("payload").get("dedup_result_version").asText());assertEquals(original.inputHash(),fragment.get("input_hash").asText());assertEquals(5,fragment.get("members").get(0).get("revision").asInt());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
            f.jdbc.update("UPDATE source_run_items SET revision=20,analysis_job_id='fictional-later-pointer' WHERE id='fictional-current-item'");assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(20,f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id='fictional-current-item'",Integer.class));assertEquals("fictional-later-pointer",f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(2,f.sends);
        }
    }
    @Test void LastFragmentReceiptFailureRetainsOriginalResponseAndRollsBackVersionsMembersAndRunLinks(){
        completion("agent",true);f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_catalog_fragment_failure CHECK(target_type IS NULL OR target_type<>'source_member_stage')");assertThrows(RuntimeException.class,this::apply);unwritten();
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_catalog_fragment_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_catalog_fragment_failure");assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(2,f.sends);
    }
    @Test void MissingLedgerProviderCannotSubstituteRawRecommendationsForFirstJudgmentProof(){
        completion("agent",true);var legacy=new MemberResultDownstream(new JdbcFrozenInputs(f.source),new JdbcMemberStageResults(f.source),new SourceMemberStageService(new JdbcSourceMemberStages(f.source),f.clock),f.clock);installCompletion(legacy);assertThrows(RuntimeException.class,this::apply);unwritten();installCompletion(downstream);assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());
    }
    @Test void CorruptFirstLedgerOrForgedOutcomeMemberSetRejectsBeforePointersAndRollsBack(){
        for(boolean corruption:List.of(false,true)){
            completion("agent",true);installCompletion(new DedupResultHandler.Downstream(){
                public void afterApplied(Input i,Validated r,List<EntityDedupService.Decision> d,Set<String> ids){throw new AssertionError("v2 port required");}
                public void afterCatalogApplied(Input i,Validated r,List<EntityDedupService.Decision> d,CatalogVersionBatchJudgmentWriter.Outcome outcome){
                    if(corruption){f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='catalog_version_judgment'",new byte[32]);downstream.afterCatalogApplied(i,r,d,outcome);}
                    else downstream.afterCatalogApplied(i,r,d,new CatalogVersionBatchJudgmentWriter.Outcome(Map.of()));
                }
            });assertThrows(RuntimeException.class,this::apply);unwritten();
        }
    }
    @Test void NativeNegativeJudgmentAndCommitAckUnknownCompleteOnceWithoutAllocation(){
        completion("agent",false);f.tx.ackLost=true;assertEquals(Status.APPLIED,apply().status());assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT revision FROM source_runs",Integer.class));assertEquals(2,f.sends);
    }
}
