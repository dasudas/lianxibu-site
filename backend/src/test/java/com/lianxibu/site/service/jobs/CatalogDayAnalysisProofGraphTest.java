package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual registered source and whole day graphs; OPS and model transport remain fictional. */
class CatalogDayAnalysisProofGraphTest {
    boolean mysql(){return false;}
    CatalogDayAnalysisEligibilityTest outer(){return new CatalogDayAnalysisEligibilityTest(){@Override boolean mysql(){return CatalogDayAnalysisProofGraphTest.this.mysql();}};}
    GenerationFlowService.SettingsSnapshotSource settings(){return ()->new FrozenGenerationSettings(7,List.of("Fictional watch"),List.of("Fictional block"));}
    CatalogDayAnalysisProofGraph graph(CatalogDayFullNativeIntegrationTest.Fixture f,Clock clock,GenerationFlowService.SettingsSnapshotSource settings,BooleanSupplier allowed){return new CatalogDayAnalysisProofGraph(f.f.source,f.f.tx,clock,f.cohorts,f.runs,f.graph,settings,allowed,f.f.category);}
    @Test void FourActualGraphsCloseAllocateAndPrepareExactOriginalInputAndDispatchProof(){
        for(String category:List.of("agent","skill","workflow","model")){
            var outer=outer();var f=outer.new NewFixture();f.terminal(category);f.completeSource();
            var g=graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true);
            var eligible=g.eligibility().capture(outer.day);assertEquals(1,eligible.selected().size());var selected=eligible.selected().getFirst();
            assertEquals(2,g.barrier().find(outer.day).closure().entries().size());
            var reservation=g.allocation().reserve(outer.day,selected.entity().entityId());assertEquals(1,reservation.generation());
            var input=g.preparation().prepare(outer.day,reservation.entityId());var snapshot=CanonicalJson.parse(input.input().snapshotJson());
            assertNotNull(CatalogAnalysisInputProfile.complete(snapshot));assertEquals(reservation.json(),input.reservation().json());
            assertEquals(input.input().inputHash(),g.dispatch().capture(input.input().ownerId()).inputHash());
            var permit=g.dispatch().capture(input.input().ownerId());assertTrue(f.write(()->g.dispatch().verify(permit)));
            assertEquals("queued",f.f.jdbc.queryForObject("SELECT analysis_state FROM analyzed_entities",String.class));assertEquals(1,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
            assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE root_job_id=? AND record_kind='ai_attempt'",Integer.class,input.input().ownerId()));assertEquals(2,f.f.sends);
        }
    }
    @Test void ActualPositiveAliasGraphRetainsEveryBodyAndOriginalSuccessfulLineage(){
        var outer=outer();var aliases=new CatalogDayAliasEvidenceTest(){@Override boolean mysql(){return CatalogDayAnalysisProofGraphTest.this.mysql();}};var f=aliases.new AliasFixture();f.terminal("agent");f.completeSource();
        var g=graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true);
        var selected=g.eligibility().capture(outer.day).selected().getFirst();var receipt=g.allocation().reserve(outer.day,selected.entity().entityId());var prepared=g.preparation().prepare(outer.day,receipt.entityId());
        assertEquals(2,receipt.generation());assertEquals(2,receipt.members().size());assertEquals(2,CanonicalJson.parse(prepared.input().snapshotJson()).get("members").size());
        assertEquals("fictional-analysis",CatalogAnalysisInputProfile.complete(CanonicalJson.parse(prepared.input().snapshotJson())).command().applicationContext().contentId());
        var permit=g.dispatch().capture(prepared.input().ownerId());assertTrue(f.write(()->g.dispatch().verify(permit)));
    }
    @Test void ReconstructedGraphReplaysOriginalAllocationAndInputBeforeCurrentTablesSettingsAndClock(){
        var outer=outer();var f=outer.new NewFixture();f.terminal("workflow");f.completeSource();var g=graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true);
        var entity=g.eligibility().capture(outer.day).selected().getFirst().entity().entityId();var receipt=g.allocation().reserve(outer.day,entity);var input=g.preparation().prepare(outer.day,entity);int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        for(String table:List.of("sources","source_run_items","analyzed_entities","contents"))f.f.jdbc.execute("DROP TABLE "+table);
        var clock=mock(Clock.class);var settings=mock(GenerationFlowService.SettingsSnapshotSource.class);var gate=mock(BooleanSupplier.class);var restarted=graph(f,clock,settings,gate);
        assertEquals(receipt.json(),restarted.allocation().reserve(outer.day,entity).json());assertEquals(input.input().inputHash(),restarted.preparation().prepare(outer.day,entity).input().inputHash());assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));verifyNoInteractions(clock,settings,gate);
    }
    @Test void FullOriginalCohortWaitsForMissingEnabledSourceThenKeepsItsDeadlineReason(){
        var outer=outer();var f=outer.new NewFixture();f.secondEnabled=true;f.terminal("model");f.completeSource();var early=graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true);
        assertNull(early.eligibility().capture(outer.day));assertEquals(0,f.closures());
        var deadline=graph(f,Clock.fixed(SourceDedupBarrier.deadline(outer.day),ZoneOffset.UTC),settings(),()->true);var selected=deadline.eligibility().capture(outer.day).selected().getFirst();var receipt=deadline.allocation().reserve(outer.day,selected.entity().entityId());var input=deadline.preparation().prepare(outer.day,receipt.entityId());
        var snapshot=CanonicalJson.parse(input.input().snapshotJson());assertTrue(snapshot.get("missing").toString().contains("fictional-second"));assertEquals(1,f.closures());assertEquals(2,f.f.sends);
    }
    @Test void NativePrepareFailureRollsBackWithoutLosingPermanentAllocationAndGraphResumes(){
        var outer=outer();var f=outer.new NewFixture();f.terminal("skill");f.completeSource();var g=graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true);var entity=g.eligibility().capture(outer.day).selected().getFirst().entity().entityId();var receipt=g.allocation().reserve(outer.day,entity);int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);
        f.f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_analysis_graph_failure CHECK(target_type IS NULL OR target_type<>'generation_prepare' OR operator_id<>'catalog-analysis-coordinator')");assertThrows(RuntimeException.class,()->g.preparation().prepare(outer.day,entity));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(receipt.json(),g.allocation().reserve(outer.day,entity).json());
        f.f.jdbc.execute(mysql()?"ALTER TABLE jobs DROP CHECK fictional_analysis_graph_failure":"ALTER TABLE jobs DROP CONSTRAINT fictional_analysis_graph_failure");assertNotNull(graph(f,Clock.fixed(outer.completed.plusSeconds(120),ZoneOffset.UTC),settings(),()->true).preparation().prepare(outer.day,entity));assertEquals(1,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
    }
    @Test void ClosedCategoryAndAllMandatoryPortsAreColdAndRejectMismatchedSourceGraph(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var clock=mock(Clock.class);var cohorts=mock(SourceDayCohortSealService.class);var runs=mock(SourceDayRunRegistration.Api.class);var settings=mock(GenerationFlowService.SettingsSnapshotSource.class);var gate=mock(BooleanSupplier.class);
        for(String category:List.of("agent","skill","workflow","model")){var sources=new CatalogSourceProofGraph(source,tx,clock,settings,gate,category);var g=new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,runs,sources,settings,gate,category);assertEquals(category,g.category());assertNotNull(g.barrier());assertNotNull(g.evidence());assertNotNull(g.originals());assertNotNull(g.eligibility());assertNotNull(g.reservations());assertNotNull(g.allocation());assertNotNull(g.inputs());assertNotNull(g.prepared());assertNotNull(g.preparation());assertNotNull(g.dispatch());assertNotNull(g.result());assertFalse(g.toString().contains(category));}
        var sources=new CatalogSourceProofGraph(source,tx,clock,settings,gate,"agent");assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,runs,sources,settings,gate,"skill"));assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,runs,sources,settings,gate,"news_tech"));assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,null,sources,settings,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofGraph(source,tx,clock,null,runs,sources,settings,gate,"agent"));assertThrows(RuntimeException.class,()->new CatalogDayAnalysisProofGraph(source,tx,clock,cohorts,runs,null,settings,gate,"agent"));
        verifyNoInteractions(source,tx,clock,cohorts,runs,settings,gate);
    }
}
