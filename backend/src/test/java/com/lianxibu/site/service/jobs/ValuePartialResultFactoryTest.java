package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/** Real partial input and result SQL; the sole external sender is fictional. */
class ValuePartialResultFactoryTest {
    SourcePartialDispatchReadinessTest f;
    ResultJdbcStoreTest.FaultManager tx;
    WorkerAuthorizationService.Identity identity;
    SourcePartitionPlanner.Chunk part;
    ResultApplicationFactory factory;
    int sends;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup() throws Exception {
        f=new SourcePartialDispatchReadinessTest(){@Override GenerationJdbcStoreTest database(){return ValuePartialResultFactoryTest.this.database();}};
        f.setup();WorkerCandidateSelectionScopeTest.installWorker(f.db);
        for(String column:List.of("high_value INT","value_json "+(f.db.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON"),"analysis_job_id VARCHAR(64)","applied_at TIMESTAMP(6)"))f.db.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        tx=new ResultJdbcStoreTest.FaultManager(f.db.source);
        var store=new WorkerJdbcStore(new JdbcWorkerState(f.db.source),new JdbcJobRecords(f.db.source),"fictional-partial-result-worker",()->false,f.service,f.q.clock,tx);
        var worker=new WorkerAuthorizationService(f.db.writes,store,f.q.clock,n->0,()->true);
        var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-partial-result-claim","fictional-partial-result-receipt",null));
        assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());identity=claim.permit().identity();
        part=f.plan.chunks().stream().filter(c->c.rootId().equals(identity.rootId())).findFirst().orElseThrow();
        assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{assertEquals(identity.frozenRequestJson(),request);sends++;return "fictional partial value response";}).state());
        factory=provider(f.q.q.a.service);
    }
    ResultApplicationFactory provider(SourceDedupPartialDependencyPermitService permits){return new ResultApplicationFactory(f.db.source,tx,f.q.clock,new SourceDedupDependencyService(f.q.q.a.observations,f.q.q.a.proven,permits),permits);}
    SharedWriteExecutor.ActionContext action(){return new SharedWriteExecutor.ActionContext("fictional-partial-result-apply",identity.rootId(),identity.inputHash());}
    ResultApplicationService.Result apply(){var app=factory.forAttempt(identity);return apply(app);}
    ResultApplicationService.Result apply(AttemptOutcomeService.Application app){return app.service().applyAutomatic(app.input(),ResultApplicationService.providerEnvelope(app.input(),JdbcFrozenInputs.canonical(new SourceValueStageResultTest().payload(part))),action());}
    void empty(){
        assertNull(new JdbcResultState(f.db.source).root(identity.rootId()).resultHash());
        assertEquals("occupied",new JdbcWorkerState(f.db.source).slot().state());
        assertEquals(0,f.db.count("target_type='source_member_stage' AND id='"+memberReceipt()+"'"));
        assertEquals(0,f.db.count("target_type='result_application' AND target_key_json LIKE '%"+identity.rootId()+"%'"));
        assertEquals(0,f.db.count("purpose='result' AND owner_job_id='"+identity.rootId()+"'"));
        assertEquals(0,f.db.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE value_json IS NOT NULL OR high_value IS NOT NULL OR analysis_job_id IS NOT NULL OR applied_at IS NOT NULL",Integer.class));
        for(String id:part.itemIds())assertEquals(4,f.db.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Integer.class,id));
        assertEquals(1,new JdbcJobRecords(f.db.source).byId(identity.rootId()).callCount());assertEquals(1,sends);
    }
    void applied(){
        assertEquals("free",new JdbcWorkerState(f.db.source).slot().state());
        var nativeRoot=new JdbcResultState(f.db.source).root(identity.rootId());assertEquals("succeeded",nativeRoot.state());assertEquals("APPLIED",nativeRoot.application());assertNotNull(nativeRoot.appliedAt());
        assertEquals(1,f.db.count("target_type='source_member_stage' AND id='"+memberReceipt()+"'"));assertEquals(1,f.db.count("target_type='result_application' AND target_key_json LIKE '%"+identity.rootId()+"%'"));
        assertEquals(part.itemIds().size(),f.db.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE value_json IS NOT NULL",Integer.class));
        for(String id:part.itemIds()){
            var value=CanonicalJson.parse(f.db.jdbc.queryForObject("SELECT value_json FROM source_run_items WHERE id=?",String.class,id));
            assertEquals("member-value-v3",value.get("schema_version").asText());assertTrue(value.get("valuable").booleanValue());assertFalse(value.get("high_value").booleanValue());assertEquals("model",value.get("category").asText());assertEquals("news_tech",value.get("member_category").asText());
            assertEquals(f.db.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,id),value.get("entity_id").asText());assertEquals(6,f.db.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id=?",Integer.class,id));assertEquals(identity.rootId(),f.db.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id=?",String.class,id));
        }
        assertEquals(1,sends);
    }
    String memberReceipt(){return JdbcFrozenInputs.stableId("member-stage","member-stage:"+identity.rootId());}
    @Test void Actual201PartialResultIsAtomicAndOriginalAppliedReplayNeedsNoCurrentMemberOrNewP1Proof() throws Exception {
        setup();var result=apply();assertEquals(ResultApplicationService.Status.APPLIED,result.status());applied();
        f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_stage_availability'",new byte[32]);
        f.db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-later-generation",identity.rootId(),null),key->null,c->{new JdbcJobRecords(f.db.source).ensureIntent(new JdbcJobRecords.Intent("fictional-later-value-intent",f.plan.logicalKey(),2,"news_value",f.plan.scope().businessDate()),f.q.clock.instant());return true;});
        f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");
        factory=provider(f.q.q.a.service);int rows=f.db.count("1=1");assertEquals(result,apply());assertEquals(rows,f.db.count("1=1"));assertEquals(1,sends);
        f.db.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",new byte[32],identity.rootId());assertThrows(RuntimeException.class,this::apply);assertEquals(1,sends);
    }
    @Test void ActualGraphAndEnabledConfigurationUseTheSamePartialProofPorts() throws Exception {
        setup();f.db.jdbc.update("UPDATE jobs SET operator_id='analysis-worker' WHERE target_type='generation_prepare'");
        var graph=new SourceAnalysisProofGraph(f.db.source,tx,f.q.clock,()->false);
        new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class).withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true").withBean(DataSource.class,()->f.db.source).withBean(PlatformTransactionManager.class,()->tx).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(ResultApplicationFactory.class).forAttempt(identity));assertNotNull(c.getBean(SourceAnalysisProofGraph.class));});
        factory=new ResultApplicationFactory(f.db.source,tx,f.q.clock,graph.dependencies(),graph.dedupPermits());assertEquals(ResultApplicationService.Status.APPLIED,apply().status());applied();
    }
    @Test void CurrentEntityAndP1ChangeInsideWriterRefuseAllWritesAndDefaultFactoryCannotGrantPermission() throws Exception {
        setup();var legacy=new ResultApplicationFactory(f.db.source,tx,f.q.clock);assertThrows(RuntimeException.class,()->apply(legacy.forAttempt(identity)));empty();
        var app=factory.forAttempt(identity);String item=part.itemIds().getFirst(),entity=f.db.jdbc.queryForObject("SELECT entity_id FROM source_run_items WHERE id=?",String.class,item);f.db.jdbc.update("UPDATE source_run_items SET entity_id='fictional-changed' WHERE id=?",item);assertThrows(RuntimeException.class,()->apply(app));empty();f.db.jdbc.update("UPDATE source_run_items SET entity_id=? WHERE id=?",entity,item);
        String p1=f.q.q.a.f.plan.chunks().getLast().rootId();long revision=f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,p1);var changed=spy(f.q.q.a.service);doAnswer(call->{f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);return call.callRealMethod();}).when(changed).verify(any(),any());factory=provider(changed);assertThrows(RuntimeException.class,this::apply);empty();assertEquals(revision,f.db.jdbc.queryForObject("SELECT revision FROM jobs WHERE id=?",Long.class,p1));
        factory=provider(f.q.q.a.service);assertThrows(RuntimeException.class,()->f.q.q.h.f.readonly(()->factory.forAttempt(identity)));assertEquals(ResultApplicationService.Status.APPLIED,apply().status());applied();
    }
    @Test void FinalReceiptFailureRollsBackRetentionMemberLocatorsAndSlotAndRetriesTheKnownResult() throws Exception {
        setup();f.db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_partial_result_failure CHECK(target_type IS NULL OR target_type<>'result_application')");assertThrows(RuntimeException.class,this::apply);empty();
        f.db.jdbc.execute(f.db.source instanceof org.h2.jdbcx.JdbcDataSource?"ALTER TABLE jobs DROP CONSTRAINT fictional_partial_result_failure":"ALTER TABLE jobs DROP CHECK fictional_partial_result_failure");assertEquals(ResultApplicationService.Status.APPLIED,apply().status());applied();
    }
    @Test void LostCommitAckRecoversOneApplicationAndOriginalClaimRetainedAndAppliedMarkersStayRequired() throws Exception {
        setup();tx.ackLost=true;var result=apply();assertEquals(ResultApplicationService.Status.APPLIED,result.status());applied();factory=provider(f.q.q.a.service);assertEquals(result,apply());
        String original=f.db.jdbc.queryForObject("SELECT payload_json FROM jobs WHERE purpose='result' AND owner_job_id=?",String.class,identity.rootId());f.db.jdbc.update("UPDATE jobs SET payload_json=? WHERE purpose='result' AND owner_job_id=?",original.replace("Fictional","Altered"),identity.rootId());assertThrows(RuntimeException.class,this::apply);f.db.jdbc.update("UPDATE jobs SET payload_json=? WHERE purpose='result' AND owner_job_id=?",original,identity.rootId());
        f.db.jdbc.update("UPDATE jobs SET request_json='{}' WHERE id=?",identity.attemptId());assertThrows(RuntimeException.class,this::apply);f.db.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?",identity.frozenRequestJson(),identity.attemptId());
        f.db.jdbc.update("UPDATE jobs SET application_state='INVALID_EVIDENCE' WHERE id=?",identity.rootId());assertThrows(RuntimeException.class,this::apply);f.db.jdbc.update("UPDATE jobs SET application_state='APPLIED' WHERE id=?",identity.rootId());assertEquals(result,apply());assertEquals(1,sends);
    }
    @Test void ExplicitConstructorIsLazyAndCoreFlagsKeepAssemblyOff() {
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var clock=mock(Clock.class);var deps=mock(SourceDedupDependencyService.class);var permits=mock(SourceDedupPartialDependencyPermitService.class);
        assertNotNull(new ResultApplicationFactory(source,tx,clock,deps,permits));assertThrows(RuntimeException.class,()->new ResultApplicationFactory(source,tx,clock,deps,null));verifyNoInteractions(source,tx,clock,deps,permits);
        new ApplicationContextRunner().withUserConfiguration(ResultApplicationConfiguration.class).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(AttemptOutcomeService.Applications.class).isEmpty());assertTrue(c.getBeansOfType(SourceAnalysisProofGraph.class).isEmpty());});
    }
}
