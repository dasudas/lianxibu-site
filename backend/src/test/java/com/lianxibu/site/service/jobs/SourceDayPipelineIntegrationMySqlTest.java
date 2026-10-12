package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.ContentReadService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual original day and P2 SQL consumers. Model terminals and settings are fictional fixtures. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayPipelineIntegrationMySqlTest {
    SourceDayPartialPermitIntegrationMySqlTest day;SourceAnalysisParentStepTest f;
    SourcePlanPreparationCoordinator preparation;SourceAnalysisParentStepService parent;int settingsReads;
    void setup(boolean partial)throws Exception{
        day=new SourceDayPartialPermitIntegrationMySqlTest(){@Override boolean newPreparationAllowed(){return true;}};day.setup(partial);f=day.original.f;
        var configuration=new SourceDayNewsDedupConfiguration();day.barriers=configuration.createBarrier(f.db.source,day.manager,day.cohorts,configuration.createProgress(day.manager,day.runs,day.graph),f.clock);
        var admission=new SourceDayRunClaimAdmissionConfiguration().create(f.db.source,day.manager,new SourceDayCollectionAdmissionService(day.cohorts));
        day.permits=new SourceDayNewsValuePermitService(day.barriers,admission,new JdbcSourceDayDedupClosures(f.db.source),day.graph.dedupProofs(),day.graph.dedupObserver(),day.graph.dependencies(),day.graph.dedupPermits(),day.manager);
        var value=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.db.source),new JdbcFrozenSourceUniverses(f.db.source),day.graph.universes(),day.graph.plans(),day.graph.collections(),
            new NewsValueEligibilityService(new JdbcNewsDedupEligibility(f.db.source),new JdbcNewsValueEntities(f.db.source),f.p1.members,f.clock),day.graph.dependencies(),()->{settingsReads++;return new FrozenGenerationSettings(1,List.of(),List.of());},f.clock,()->true,"2",day.permits);
        preparation=new SourcePlanPreparationCoordinator(f.p1.preparation,value,day.graph.parts(),day.graph.handoffs(),day.graph.dedupProofs(),new JdbcFrozenSourcePlans(f.db.source),f.db.writes,day.manager,f.clock,()->true,day.graph.dedupPermits(),day.permits);
        parent=NewsSourcePreparationConfiguration.parentStep(f.db.source,day.manager,f.clock,preparation,day.graph);
    }
    String child(){var result=day.graph.handoffs().handoff(day.original.ref);assertNotNull(result);return result.p2Owner();}
    void projectFictionalAppliedMember(){
        var chunk=day.original.plan.chunks().getFirst();var snapshot=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));var bindings=FrozenMemberBindings.read(snapshot,context.input(chunk.rootId(),"fictional-projection",chunk.inputHash(),0));var member=bindings.values().iterator().next();String entity="fictional-entity-"+member.itemId();
        f.db.jdbc.update("UPDATE source_run_items SET state='ready',entity_id=?,revision=? WHERE id=?",entity,member.expectedRevision()+2,member.itemId());
        f.db.jdbc.update("INSERT INTO analyzed_entities(id,category,identity_key,identity_hash,title,summary,revision,first_recorded_at,analysis_state,legacy_flag,first_seen_business_date) VALUES(?,'news_tech',?,?,?,?,0,?,'pending',0,?)",entity,member.identityKey(),JdbcEntityDedup.identityHash("news_tech",member.identityKey()),member.title(),member.summary(),java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),java.sql.Date.valueOf(day.day));
    }
    DispatchReadinessService dispatch(){var input=new JdbcFrozenInputs(f.db.source);return new DispatchReadinessService(new JdbcDispatchInputs(f.db.source),input,new FrozenDependencyIndexService(input,new JdbcFrozenDependencies(f.db.source)),new GenerationLineageService(new JdbcGenerationLineage(f.db.source),f.clock,"fictional-owner"),mock(ContentReadService.class),f.p1.members,target->{throw new AssertionError("source-only fixture has no content references");},day.graph.collections()::read,new SourcePartitionProofService(day.graph.plans()),true,day.graph.dependencies(),day.graph.dedupPermits(),day.manager,day.permits);}
    boolean ready(DispatchReadinessService service,String root,DispatchReadinessService.Captured captured){return f.db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-original-day-qualification",root,null),key->null,c->service.readyCaptured(root,captured));}
    @Test void actualFullDayPreparesNonzeroP2AndGrantsOnlyFreshWriterQualification()throws Exception{
        setup(false);projectFictionalAppliedMember();String owner=child();var prepared=preparation.prepareValue(owner,day.run(),day.original.ref,0);assertNotNull(prepared);assertNotNull(prepared.root());String root=prepared.root().ownerId();var snapshot=CanonicalJson.parse(prepared.root().snapshotJson());assertTrue(snapshot.get(FrozenSourceDayDedupDependency.FIELD).get("original_batch_eligible").asBoolean());assertEquals(1,prepared.plan().chunks().size());
        var dispatch=dispatch();int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var captured=dispatch.captureSource(root);assertNotNull(captured);assertTrue(ready(dispatch,root,captured));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(0,f.db.count("target_type='worker_send'"));
        f.db.jdbc.update("DELETE FROM request_receipts WHERE actor_id='source-day-register'");assertThrows(RuntimeException.class,()->ready(dispatch,root,captured));assertThrows(RuntimeException.class,()->dispatch.captureSource(root));
    }
    @Test void actualPartialAllFailedP1ProducesZeroP2PartialFinalAndOriginalObservation()throws Exception{
        setup(true);String owner=child();assertEquals(SourceAnalysisParentStepService.Status.PREPARED,parent.step(f.candidate(owner)).status());var ref=f.reference(owner);var plan=f.read(()->day.graph.plans().readImmutable(ref));assertTrue(plan.chunks().isEmpty());assertTrue(CanonicalJson.parse(plan.scope().stageContextJson()).has(FrozenSourceDayDedupDependency.FIELD));
        assertEquals(SourceAnalysisParentStepService.Status.FINALIZED,parent.step(f.candidate(owner)).status());var committed=f.read(()->day.graph.finalization().readCommitted(ref));assertNotNull(committed);assertEquals("partial",CanonicalJson.parse(committed.result().json()).get("coverage_state").asText());assertEquals("succeeded",f.db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,owner));assertEquals(0,f.db.count("record_kind='logical_task' AND task_type='news_value'"));assertNotNull(f.read(()->day.graph.finalObserver().availableAt(committed,f.clock.instant())));
        int rows=f.db.count("1=1"),reads=settingsReads;changeCurrentConfiguration();assertEquals(SourceAnalysisParentStepService.Status.FINALIZED,parent.step(f.candidate(owner)).status());assertEquals(rows,f.db.count("1=1"));assertEquals(reads,settingsReads);
    }
    @Test void brokenActualRunReceiptCannotPrepareP2OrRebuildItsOriginalDay()throws Exception{
        setup(false);projectFictionalAppliedMember();String owner=child();var captured=day.permits.capture(day.own.toJson(),day.cutoff());assertNotNull(captured);String closure=day.barriers.find(day.day).closure().json();int rows=f.db.count("1=1");f.db.jdbc.update("DELETE FROM request_receipts WHERE actor_id='source-day-register'");assertThrows(RuntimeException.class,()->preparation.prepareValue(owner,day.run(),day.original.ref,0));assertEquals(rows,f.db.count("1=1"));assertEquals(0,f.db.count("record_kind='logical_task' AND task_type='news_value'"));assertEquals(closure,f.read(()->new JdbcSourceDayDedupClosures(f.db.source).read(day.day,"news_tech")).closureJson());
    }
    void changeCurrentConfiguration(){f.db.jdbc.update("UPDATE sources SET state='disabled',activation_stage='disabled',config_revision=config_revision+1,effective_config_json='{}',revision=revision+1");}
    @Test void preparedFullDayReplaysExactPlanAndPartWithoutFreshSettingsOrDirectoryRecalculation()throws Exception{
        setup(false);projectFictionalAppliedMember();String owner=child();var original=preparation.prepareValue(owner,day.run(),day.original.ref,0);int rows=f.db.count("1=1"),reads=settingsReads;changeCurrentConfiguration();var replay=preparation.prepareValue(owner,day.run(),day.original.ref,0);assertEquals(original, replay);assertEquals(rows,f.db.count("1=1"));assertEquals(reads,settingsReads);
    }
}
