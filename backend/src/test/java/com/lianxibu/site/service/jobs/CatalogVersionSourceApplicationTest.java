package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Actual original collector handoff, source plan, readiness, worker, production result factory and permanent member completion. Model transport is fictional. */
class CatalogVersionSourceApplicationTest extends CatalogVersionSourceGraphTest {
    AttemptOutcomeService.Application application;String raw;String action;
    void modelItem(tools.jackson.databind.node.ObjectNode item){}
    void functional(String category,boolean unchanged,boolean missing,boolean recommend){
        source(category,unchanged);String json=mysql()?"JSON":"CLOB",time=mysql()?"DATETIME(6)":"TIMESTAMP(6)";
        for(String column:List.of("repeat_count BIGINT DEFAULT 0","last_seen_at "+time,"last_occurrence_at "+time))f.jdbc.execute("ALTER TABLE analyzed_entities ADD "+column);
        f.jdbc.update("UPDATE analyzed_entities SET first_seen_business_date='2026-01-01',occurrence_count=1,last_seen_at=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(2)));
        for(String column:List.of("counted_occurrence_key BINARY(32) UNIQUE","is_new INT","dedup_json "+json,"analysis_job_id VARCHAR(64)","applied_at "+time))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        if(missing)f.jdbc.update("UPDATE contents SET input_job_id=NULL");graph();var prepared=graph.coordinator().prepareDedup(collection.intentId(),sourceRunId(),0);var root=prepared.root();var inputs=new JdbcFrozenInputs(f.source);
        var dispatch=new DispatchReadinessService(new JdbcDispatchInputs(f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"analysis-worker"),new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences()),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source)),target->{throw new AssertionError("original source dedup contains no synthetic reference targets");},collections::read,new SourcePartitionProofService(plans),true);
        assertEquals(root.cutoff(),new JdbcDispatchInputs(f.source).read(root.ownerId()).cutoff());
        assertTrue(write(()->dispatch.ready(root.ownerId())));
        var worker=new WorkerAuthorizationService(f.writes,new WorkerJdbcStore(new JdbcWorkerState(f.source),new JdbcJobRecords(f.source),"fictional-complete-epoch",()->false,dispatch::ready,f.clock),f.clock,()->true);
        var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-complete-claim",root.ownerId(),root.inputHash()));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());f.permit=claim.permit();f.identity=f.permit.identity();assertEquals(root.ownerId(),f.identity.rootId());assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(f.permit,request->{f.sends++;assertEquals(CanonicalJson.parse(root.requestJson()),CanonicalJson.parse(request));return "fictional complete model response";}).state());
        application=new ResultApplicationFactory(f.source,f.tx,f.clock).forAttempt(f.identity);assertEquals(root.inputHash(),application.input().inputHash());
        var payload=JsonMapper.shared().createObjectNode().put("kind","dedup").put("dedup_result_version","2").put("summary","Fictional complete source result");var items=payload.putArray("items");
        for(String id:new TreeSet<>(application.input().itemIds())){
            var item=items.addObject().put("item_id",id).put("category",category).put("decision","existing").put("candidate_entity_id","fictional-entity").put("reason","Fictional verified original input comparison").put("confidence","0.9").put("suggested_title","Fictional current catalog").putNull("suggested_summary").put("reanalyze_recommended",recommend);
            var q=application.input().catalogVersionQualifications().get(id);if(q==null)item.putNull("version_changed").putNull("previous_evidence_ref").putNull("new_evidence_ref");else{item.put("version_changed",q.versionChanged());item.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.previousProofHash());item.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.newProofHash());}
            modelItem(item);
        }
        raw=ResultApplicationService.providerEnvelope(application.input(),JdbcFrozenInputs.canonical(payload));action="fictional-complete-source-apply";
    }
    Result apply(){return application.service().applyAutomatic(application.input(),raw,new SharedWriteExecutor.ActionContext(action,application.input().rootId(),application.input().inputHash()));}
    @Test void FourCategoryCompleteOriginalSourcePipelineAppliesFirstGenerationAndPermanentMemberTogether(){
        for(String category:List.of("agent","skill","workflow","model")){
            functional(category,false,false,true);assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals("fictional-entity",f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals(application.input().rootId(),f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals("APPLIED",new JdbcResultState(f.source).root(application.input().rootId()).application());assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(1,new JdbcJobRecords(f.source).byId(application.input().rootId()).callCount());assertEquals(2,f.sends);
        }
    }
    @Test void KnownFalseAndUnknownApplyWithoutInventingPositiveGeneration(){
        functional("agent",true,false,false);assertEquals(Status.APPLIED,apply().status());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));
        functional("skill",false,true,false);assertEquals(Status.APPLIED,apply().status());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.sends);
    }
    @Test void DownstreamFailureRetainsOriginalResponseAndRollsBackMemberJudgmentAndGeneration(){
        functional("workflow",false,false,true);f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_full_completion_failure CHECK(target_type IS NULL OR target_type<>'source_member_stage')");assertThrows(RuntimeException.class,this::apply);assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertNull(f.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertNotNull(new JdbcResultState(f.source).root(application.input().rootId()).resultHash());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));
        f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_full_completion_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_full_completion_failure");assertEquals(Status.APPLIED,apply().status());assertEquals(2,f.sends);
    }
    @Test void RebuiltFactoryReplaysActualOriginalReceiptAfterNativeCurrentProofDisappears(){
        functional("model",false,false,true);assertEquals(Status.APPLIED,apply().status());int before=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}' WHERE id=?",RUN);application=new ResultApplicationFactory(f.source,f.tx,f.clock).forAttempt(f.identity);assertEquals(Status.APPLIED,apply().status());assertEquals(before,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.sends);
    }
}
