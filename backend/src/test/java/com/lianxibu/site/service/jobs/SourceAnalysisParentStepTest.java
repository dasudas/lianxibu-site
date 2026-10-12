package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import com.lianxibu.site.service.articles.NewsValueEligibilityService;
import com.lianxibu.site.service.sources.SourceRunAnalysisService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real original providers with retained fictional results, never an external model call. */
class SourceAnalysisParentStepTest {
    NewsSourcePlanPreparationTest p1;SourceValueIntentHandoffTest handoffs;GenerationJdbcStoreTest db;
    FrozenSourceStagePlanService plans;SourceStagePreparationService parts;SourceValueFinalizationService finals;
    SourceValueFinalAvailabilityService observer;SourceAnalysisParentStepService step;Clock clock;boolean allowed=true,projectionInstalled;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void newSource() throws Exception {
        p1=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceAnalysisParentStepTest.this.database();}};p1.setup();db=p1.f;clock=Clock.fixed(db.clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
        boolean h2=db.source instanceof org.h2.jdbcx.JdbcDataSource;if(h2)db.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");
        db.jdbc.execute("ALTER TABLE jobs ADD result_hash BINARY(32)");db.jdbc.execute("ALTER TABLE jobs ADD result_json "+(h2?"CLOB":"JSON"));db.jdbc.execute("ALTER TABLE jobs ADD application_state VARCHAR(32)");
        for(String c:List.of("applied_at","acted_at"))db.jdbc.execute(h2?"ALTER TABLE jobs ALTER COLUMN "+c+" TIMESTAMP(6)":"ALTER TABLE jobs MODIFY "+c+" TIMESTAMP(6) NULL");
        db.jdbc.execute("ALTER TABLE analyzed_entities ADD first_seen_business_date DATE");
        handoffs=new SourceValueIntentHandoffTest();handoffs.f=new NewsValueSourcePlanPreparationTest();handoffs.f.p1=p1;handoffs.f.f=db;handoffs.f.clock=clock;handoffs.install();wire();
    }
    void completedP1() throws Exception {
        handoffs=new SourceValueIntentHandoffTest(){@Override GenerationJdbcStoreTest database(){return SourceAnalysisParentStepTest.this.database();}};handoffs.setup();p1=handoffs.f.p1;db=p1.f;clock=Clock.fixed(handoffs.f.clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);wire();
    }
    void wire(){
        // Fixed fixture clocks represent one persisted microsecond; real clocks advance after observation.
        handoffs.f.clock=clock;handoffs.install();
        boolean h2=db.source instanceof org.h2.jdbcx.JdbcDataSource;String text=h2?"CLOB":"MEDIUMTEXT",json=h2?"CLOB":"JSON";
        if(!projectionInstalled){
            for(String c:List.of("analysis_state VARCHAR(32)","dedup_summary "+text,"analysis_summary "+text,"coverage_json "+json))db.jdbc.execute("ALTER TABLE source_runs ADD "+c);
            db.jdbc.update("UPDATE source_runs SET analysis_state='waiting_input',coverage_json='{}'");
            projectionInstalled=true;
        }
        var tx=new DataSourceTransactionManager(db.source);plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(db.source),p1.universes,clock,"fictional-owner");
        parts=new SourceStagePreparationService(db.source,plans,p1.universes,p1.collections::read,clock,"fictional-owner");
        var dependency=new SourceDedupDependencyService(new JdbcSourceStageAvailability(db.source));
        var value=new NewsValueSourcePlanPreparationService(new JdbcFrozenSourcePlans(db.source),new JdbcFrozenSourceUniverses(db.source),p1.universes,plans,p1.collections,
            new NewsValueEligibilityService(new JdbcNewsDedupEligibility(db.source),new JdbcNewsValueEntities(db.source),p1.members,clock),dependency,()->new FrozenGenerationSettings(1,List.of(),List.of()),clock,()->allowed,"2");
        var coordinator=new SourcePlanPreparationCoordinator(p1.preparation,value,parts,handoffs.service,handoffs.proofs,new JdbcFrozenSourcePlans(db.source),db.writes,tx,clock,()->allowed);
        var proof=new SourceValueStageProofService(plans,p1.universes,new SourceValueResultProofService(new JdbcSourceDedupResults(db.source),plans,p1.universes,dependency),dependency);
        var finalProof=new SourceValueFinalProofService(proof,plans,p1.universes);
        var archives=new SourceValueFinalArchiveService(finalProof,new JdbcSourceValueFinalBlocks(db.source),db.writes,tx,clock);
        var receipts=new SourceValueFinalReceiptService(new JdbcSourceValueFinalReceipts(db.source));
        finals=new SourceValueFinalizationService(finalProof,archives,plans,p1.universes,receipts,new SourceRunAnalysisService(new JdbcSourceRunAnalysis(db.source),receipts::prior),db.writes,tx,clock);
        observer=new SourceValueFinalAvailabilityService(finals,new JdbcSourceValueFinalAvailability(db.source),db.writes,tx,clock);
        var nativeResults=new JdbcSourceDedupResults(db.source);
        var resolutions=new SourceStageResolutionService(new JdbcSourceStageRoots(db.source),plans,p1.universes,parts,new JdbcFrozenInputs(db.source),nativeResults,
            new SourceDedupResultProofService(nativeResults,plans,p1.universes),new SourceValueResultProofService(nativeResults,plans,p1.universes,dependency),dependency);
        step=new SourceAnalysisParentStepService(new SourceIntentOriginService(new JdbcSourceIntentScan(db.source),handoffs.metadata),new JdbcFrozenSourcePlans(db.source),plans,parts,coordinator,resolutions,handoffs.service,finals,observer,tx,clock);
    }
    <T>T read(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(db.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    JdbcSourceIntentScan.Candidate candidate(String id){return read(()->new JdbcSourceIntentScan(db.source).page(null,50).candidates().stream().filter(c->id.equals(c.id())).findFirst().orElseThrow());}
    FrozenSourceStagePlan.Reference reference(String owner){return read(()->FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(CanonicalJson.parse(new JdbcFrozenSourcePlans(db.source).immutableReceipt(owner).payloadJson()).get("plan_ref"))));}
    void completeP2(FrozenSourceStagePlan.Reference reference){
        var f=new SourceValueStageProofTest();f.f=db;f.plan=read(()->plans.readImmutable(reference));f.original=read(()->p1.universes.readImmutable(f.plan.reference()));f.clock=clock;f.pure=new SourceValueStageResultTest();
        for(int n=0;n<f.plan.chunks().size();n++)f.complete(n);
    }
    void closeGate(){allowed=false;p1.allowed=false;handoffs.allowed=false;}
    void status(SourceAnalysisParentStepService.Status expected,JdbcSourceIntentScan.Candidate candidate){assertEquals(expected,step.step(candidate).status());}
    @Test void actualNewP1PreparesOneOriginalPartAndRestartPendingAddsNothing() throws Exception {
        newSource();var candidate=candidate(p1.collection.intentId());status(SourceAnalysisParentStepService.Status.PREPARED,candidate);assertEquals(1,db.count("record_kind='logical_task'"));assertEquals(1,db.count("record_kind='ai_attempt'"));int count=db.count("1=1"),settings=p1.settingsReads;
        wire();status(SourceAnalysisParentStepService.Status.PENDING,candidate(p1.collection.intentId()));assertEquals(count,db.count("1=1"));assertEquals(settings,p1.settingsReads);assertEquals(0,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void originalAppliedP1CreatesOneChildAndActualV2P2ReachesARealFinalObservation() throws Exception {
        completedP1();String collector=JdbcFrozenInputs.canonical(CanonicalJson.parse(db.jdbc.queryForObject("SELECT result_json FROM source_runs",String.class)));var original=candidate(handoffs.ref.ownerId());
        status(SourceAnalysisParentStepService.Status.HANDED_OFF,original);var relation=handoffs.read();assertNotNull(relation);assertEquals(1,handoffs.children());int count=db.count("1=1");status(SourceAnalysisParentStepService.Status.HANDED_OFF,original);assertEquals(count,db.count("1=1"));
        var child=candidate(relation.p2Owner());status(SourceAnalysisParentStepService.Status.PREPARED,child);var ref=reference(child.id());var plan=read(()->plans.readImmutable(ref));assertEquals("2",CanonicalJson.parse(plan.chunks().getFirst().snapshotJson()).get(FrozenApplicationContext.FIELD).get("value_result_version").asText());
        status(SourceAnalysisParentStepService.Status.PENDING,candidate(child.id()));completeP2(ref);status(SourceAnalysisParentStepService.Status.FINALIZED,candidate(child.id()));
        assertEquals("succeeded",db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,child.id()));assertNotNull(read(()->finals.readCommitted(ref)));assertEquals(1,db.count("target_type='source_value_final_availability'"));assertEquals(2,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(collector,JdbcFrozenInputs.canonical(CanonicalJson.parse(db.jdbc.queryForObject("SELECT result_json FROM source_runs",String.class))));
    }
    @Test void sealedTerminalCandidateRecoversOnlyItsMissingObservationWithCapacityClosed() throws Exception {
        completedP1();status(SourceAnalysisParentStepService.Status.HANDED_OFF,candidate(handoffs.ref.ownerId()));var child=candidate(handoffs.read().p2Owner());status(SourceAnalysisParentStepService.Status.PREPARED,child);var ref=reference(child.id());completeP2(ref);assertNotNull(finals.finish(ref));assertEquals(0,db.count("target_type='source_value_final_availability'"));
        var terminal=candidate(child.id());assertEquals("succeeded",terminal.state());clock=Clock.fixed(clock.instant().plusSeconds(100),ZoneOffset.UTC);closeGate();wire();status(SourceAnalysisParentStepService.Status.FINALIZED,terminal);
        var proof=read(()->finals.readCommitted(ref));assertEquals(clock.instant(),read(()->observer.availableAt(proof,clock.instant())));int count=db.count("1=1");long revision=db.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);status(SourceAnalysisParentStepService.Status.FINALIZED,candidate(child.id()));assertEquals(count,db.count("1=1"));assertEquals(revision,db.jdbc.queryForObject("SELECT revision FROM source_runs",Long.class));assertEquals(2,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void originalMetadataPrefixIsRepairedBeforeTheChildCanPrepareInput() throws Exception {
        completedP1();var proof=read(()->handoffs.proofs.read(handoffs.ref));var plan=read(()->plans.readImmutable(handoffs.ref));var universe=read(()->p1.universes.readImmutable(plan.reference()));
        Instant observed=FrozenSourceDedupDependency.read(handoffs.f.profile,clock.instant().truncatedTo(ChronoUnit.MICROS)).observedAt();var relation=SourceValueIntentReceiptService.Handoff.create(proof,universe,observed,1,clock.instant().truncatedTo(ChronoUnit.MICROS));p1.tx(()->handoffs.metadata.reserve(relation));assertNull(handoffs.read());
        status(SourceAnalysisParentStepService.Status.PREPARED,candidate(relation.p2Owner()));assertNotNull(handoffs.read());assertEquals("succeeded",handoffs.state());assertEquals(1,handoffs.children());assertEquals(1,handoffs.relations());
    }
    @Test void emptyActualQualificationCompletesBothParentsWithoutSyntheticRoots() throws Exception {
        newSource();db.jdbc.update("UPDATE source_run_items SET effective_valid=0");var parent=candidate(p1.collection.intentId());status(SourceAnalysisParentStepService.Status.PREPARED,parent);status(SourceAnalysisParentStepService.Status.HANDED_OFF,candidate(parent.id()));
        var relation=read(()->handoffs.metadata.read(parent.id()));var child=candidate(relation.p2Owner());status(SourceAnalysisParentStepService.Status.PREPARED,child);status(SourceAnalysisParentStepService.Status.FINALIZED,candidate(child.id()));assertEquals("no_input",db.jdbc.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,child.id()));assertEquals(0,db.count("record_kind IN ('logical_task','ai_attempt')"));assertEquals(0,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertEquals(1,db.count("target_type='source_value_final_availability'"));
    }
    @Test void gatesOuterTransactionsAndChangedNativeParentRefuseNewInput() throws Exception {
        newSource();var parent=candidate(p1.collection.intentId());int count=db.count("1=1");closeGate();status(SourceAnalysisParentStepService.Status.GATED,parent);assertEquals(count,db.count("1=1"));assertThrows(RuntimeException.class,()->p1.tx(()->step.step(parent)));allowed=true;p1.allowed=true;handoffs.allowed=true;
        db.jdbc.update("UPDATE jobs SET state='cancelled' WHERE id=?",parent.id());assertThrows(RuntimeException.class,()->step.step(parent));assertEquals(count,db.count("1=1"));
    }
    @Test void brokenOriginalRouteAndPlanCannotFallBackToNewFacts() throws Exception {
        newSource();var parent=candidate(p1.collection.intentId());status(SourceAnalysisParentStepService.Status.PREPARED,parent);int count=db.count("1=1");
        db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],JdbcFrozenSourcePlans.receiptId(parent.id()));assertThrows(RuntimeException.class,()->step.step(parent));assertEquals(count,db.count("1=1"));
        db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],p1.collection.dispositionId());assertThrows(RuntimeException.class,()->step.step(parent));assertEquals(count,db.count("1=1"));
    }
    @Test void terminalP2WithoutACompleteFinalProofCannotRecreateInputOrClaimSuccess() throws Exception {
        completedP1();status(SourceAnalysisParentStepService.Status.HANDED_OFF,candidate(handoffs.ref.ownerId()));var child=candidate(handoffs.read().p2Owner());status(SourceAnalysisParentStepService.Status.PREPARED,child);db.jdbc.update("UPDATE jobs SET state='succeeded' WHERE id=?",child.id());int count=db.count("1=1");
        assertThrows(RuntimeException.class,()->step.step(candidate(child.id())));assertEquals(count,db.count("1=1"));assertEquals(0,db.count("target_type='source_value_final_result'"));assertEquals(0,db.count("target_type='source_value_final_availability'"));
    }
    @Test void committedP1PlanSurvivesAnActualFirstPartRollbackAndRestart() throws Exception {
        newSource();String owner=p1.collection.intentId();db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_first_p1_part CHECK(record_kind<>'logical_task' OR task_type<>'news_dedup')");
        assertThrows(RuntimeException.class,()->step.step(candidate(owner)));var original=reference(owner);var plan=read(()->plans.readImmutable(original));assertEquals(0,db.count("record_kind IN ('logical_task','ai_attempt')"));int settings=p1.settingsReads;
        dropPartConstraint("fictional_first_p1_part");wire();status(SourceAnalysisParentStepService.Status.PREPARED,candidate(owner));assertEquals(original,reference(owner));assertEquals(plan,read(()->plans.readImmutable(original)));assertEquals(settings,p1.settingsReads);assertEquals(1,db.count("record_kind='logical_task'"));assertEquals(1,db.count("record_kind='ai_attempt'"));int count=db.count("1=1");status(SourceAnalysisParentStepService.Status.PENDING,candidate(owner));assertEquals(count,db.count("1=1"));assertEquals(0,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void committedP2PlanSurvivesAnActualFirstPartRollbackAndRestart() throws Exception {
        completedP1();status(SourceAnalysisParentStepService.Status.HANDED_OFF,candidate(handoffs.ref.ownerId()));String owner=handoffs.read().p2Owner();db.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_first_p2_part CHECK(record_kind<>'logical_task' OR task_type<>'news_value')");
        assertThrows(RuntimeException.class,()->step.step(candidate(owner)));var original=reference(owner);var plan=read(()->plans.readImmutable(original));assertEquals(0,db.count("record_kind='logical_task' AND task_type='news_value'"));
        dropPartConstraint("fictional_first_p2_part");wire();status(SourceAnalysisParentStepService.Status.PREPARED,candidate(owner));assertEquals(original,reference(owner));assertEquals(plan,read(()->plans.readImmutable(original)));assertEquals(1,db.count("record_kind='logical_task' AND task_type='news_value'"));assertEquals(1,db.count("record_kind='ai_attempt' AND root_job_id='"+plan.chunks().getFirst().rootId()+"'"));int count=db.count("1=1");status(SourceAnalysisParentStepService.Status.PENDING,candidate(owner));assertEquals(count,db.count("1=1"));assertEquals(1,db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    void dropPartConstraint(String name){db.jdbc.execute("ALTER TABLE jobs"+(db.source instanceof org.h2.jdbcx.JdbcDataSource?" DROP CONSTRAINT ":" DROP CHECK ")+name);}
}
