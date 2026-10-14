package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.config.SourceAnalysisProofGraph;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual production factory composition on native original model/result/member proofs; the external model is fictional. */
class CatalogVersionResultFactoryTest extends CatalogVersionMemberCompletionTest {
    ResultApplicationFactory factory(){return new ResultApplicationFactory(f.source,f.tx,f.clock);}
    @Test void FourCategoryActualFactoryAppliesNativeVersionsAndPermanentMemberCompletionTogether(){
        for(String category:List.of("agent","skill","workflow","model")){
            completion(category,true);var application=factory().forAttempt(f.identity);assertEquals(original,application.input());f.service=application.service();
            assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(2,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(5,f.jdbc.queryForObject("SELECT revision FROM source_run_items WHERE id='fictional-current-item'",Integer.class));assertEquals(DEDUP_ROOT,f.jdbc.queryForObject("SELECT analysis_job_id FROM source_run_items WHERE id='fictional-current-item'",String.class));assertEquals("free",new JdbcWorkerState(f.source).slot().state());assertEquals(1,new JdbcJobRecords(f.source).byId(DEDUP_ROOT).callCount());assertEquals(2,f.sends);
            f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");f.jdbc.update("UPDATE source_runs SET config_snapshot_json='{}'");application=factory().forAttempt(f.identity);f.service=application.service();assertEquals(Status.APPLIED,apply().status());assertEquals(1,fragments());assertEquals(2,f.sends);
        }
    }
    @Test void MissingNativeVersionFrameRejectsApplicationWhileRetainingOriginalSentResponse(){
        boot("agent",true,false);var application=factory().forAttempt(f.identity);f.service=application.service();assertThrows(RuntimeException.class,this::apply);assertNotNull(new JdbcResultState(f.source).root(DEDUP_ROOT).resultHash());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals("occupied",new JdbcWorkerState(f.source).slot().state());assertEquals(2,f.sends);
    }
    @Test void ConstructingAllNativeCatalogProvidersPerformsZeroDatabaseOrNetworkIo(){
        var source=(javax.sql.DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{javax.sql.DataSource.class},(proxy,method,args)->{throw new AssertionError("factory construction must not access data source");});
        assertDoesNotThrow(()->new ResultApplicationFactory(source,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),f.clock));
    }
    final com.lianxibu.site.service.identity.IdentityService.Actor confirmedOwner=new com.lianxibu.site.service.identity.IdentityService.Actor("fictional-owner","owner");
    void confirmed(String category){
        completion(category,true);f.jdbc.execute("ALTER TABLE jobs ADD COLUMN error_code VARCHAR(64)");var value=ResultApplicationService.validate(original,raw);f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-confirm-original-retain",DEDUP_ROOT,original.inputHash()),k->null,c->{var state=new JdbcResultState(f.source);state.retain(state.root(DEDUP_ROOT),f.identity.attemptId(),value.resultHash(),raw,f.clock.instant());return true;});
        f.clock=java.time.Clock.fixed(f.clock.instant().plusSeconds(91),java.time.ZoneOffset.UTC);var worker=new JdbcWorkerState(f.source);var recovery=new UnknownCallJdbcStore(worker,new JdbcWorkerRecovery(f.source,worker),new JdbcJobRecords(f.source),"fictional-worker",epoch->false);
        new UnknownCallRecoveryService(f.writes,recovery,f.clock).release(f.identity.attemptId(),UnknownCallRecoveryService.Cause.TIMEOUT,new SharedWriteExecutor.ActionContext("fictional-confirm-original-unknown",DEDUP_ROOT,original.inputHash()));var state=new JdbcResultState(f.source);var envelope=com.lianxibu.site.domain.protocol.CanonicalJson.parse(raw);var command=new JobsService.JobAction(state.root(DEDUP_ROOT).revision(),"resolve_external","Fictional same original retained response",f.identity.attemptId(),null,null,"succeeded",List.of("fictional-original-proof"),new JobsService.ValidatedResult("1",original.inputHash(),envelope.get("payload"),value.resultHash()),null);new OwnerJobResolveExternalService(f.source,f.tx,f.writes,f.clock).resolve(confirmedOwner,DEDUP_ROOT,command,"fictional-confirm-original-response");
    }
    ResultApplicationFactory confirmedFactory(){var graph=new SourceAnalysisProofGraph(f.source,f.tx,f.clock,()->false);return new ResultApplicationFactory(f.source,f.tx,f.clock,graph.dependencies(),graph.dedupPermits());}
    Result manual(AttemptOutcomeService.Application application,long revision,String key){return application.service().applyManual(application.input(),ResultApplicationService.validate(original,raw).resultHash(),revision,null,"Fictional separate confirmed application",true,new SharedWriteExecutor.ActionContext(key,DEDUP_ROOT,original.inputHash()));}
    @Test void FourCategoriesConfirmedFactoryAppliesOriginalResponseAndLeavesLaterSlotAlone(){
        for(String category:List.of("agent","skill","workflow","model")){
            confirmed(category);var state=new JdbcResultState(f.source);long revision=state.root(DEDUP_ROOT).revision();var originalResponse=state.retained(state.root(DEDUP_ROOT));f.jdbc.update("UPDATE worker_lock SET slot_state='occupied',job_id='fictional-later-attempt',holder='fictional-later-holder',slot_revision=88,fencing_token=88");var slot=new JdbcWorkerState(f.source).slot();var application=confirmedFactory().forManual(f.identity,confirmedOwner);assertEquals(Status.APPLIED,manual(application,revision,"fictional-confirmed-factory-apply").status());assertEquals("APPLIED",state.root(DEDUP_ROOT).application());assertEquals(originalResponse,state.retained(state.root(DEDUP_ROOT)));assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(1,fragments());assertEquals(1,new JdbcJobRecords(f.source).byId(DEDUP_ROOT).callCount());assertEquals(2,f.sends);
            f.jdbc.update("UPDATE source_run_items SET evidence_json='{}'");application=confirmedFactory().forManual(f.identity,confirmedOwner);assertEquals(Status.APPLIED,manual(application,revision,"fictional-confirmed-factory-apply").status());assertEquals(1,fragments());assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(2,f.sends);
        }
    }
    @Test void ConfirmedFactoryRejectsChangedCurrentProofAndRetainedPartWithoutPartialWrites(){
        for(boolean corruptRetained:List.of(false,true)){
            confirmed("agent");long revision=new JdbcResultState(f.source).root(DEDUP_ROOT).revision();var application=confirmedFactory().forManual(f.identity,confirmedOwner);if(corruptRetained)f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE owner_job_id=? AND purpose='result'",new byte[32],DEDUP_ROOT);else f.jdbc.update("UPDATE source_run_items SET evidence_json='{}'");assertThrows(RuntimeException.class,()->manual(application,revision,"fictional-confirmed-factory-bad-proof"));assertEquals(revision,new JdbcResultState(f.source).root(DEDUP_ROOT).revision());assertEquals(0,fragments());assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(2,f.sends);
        }
    }

}
