package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Original registered native source chain; the model transport is an explicit fictional sender. */
class CatalogDayEntityEvidenceTest extends CatalogDayEntityGroupingTest {
    CatalogDayEntityEvidenceService evidence(Fixture f,Clock clock){return new CatalogDayEntityEvidenceService(service(f,clock),f.graph.plans(),f.f.tx,f.f.category);}
    @Test void FourCategoriesReadFullOriginalBodiesFromNativePlansAndReplayAfterCurrentSourceRemoval(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=new Fixture();f.terminal(category);var provider=evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));assertNull(provider.capture(day));f.completeSource();var captured=provider.capture(day);assertEquals(1,captured.entities().size());var entity=captured.entities().getFirst();assertEquals("fictional-entity",entity.entityId());assertEquals(category,entity.category());var member=entity.members().getFirst();assertEquals("fictional-current-item",member.member().itemId());assertEquals(member.bodyHash(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(member.bodyJson())));assertEquals("[]",member.referencesJson());assertEquals(member.originalRevision()+2,member.member().revision());assertTrue(member.bodyJson().contains("fictional"));assertSame(captured,snapshot(f,()->provider.verifyObserved(captured)));assertThrows(UnsupportedOperationException.class,()->entity.members().clear());assertFalse(captured.toString().contains("fictional"));assertFalse(member.toString().contains("fictional"));
            int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.f.jdbc.execute("DROP TABLE sources");f.f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");var replay=evidence(f,mock(Clock.class)).captureOriginal(day);assertEquals(captured.entities(),replay.entities());assertEquals(captured.grouping().json(),replay.grouping().json());assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
        }
    }
    @Test void MissingOriginalSourceRemainsExplicitAndCannotSupplyEvidence(){
        var f=new Fixture();f.secondEnabled=true;f.terminal("workflow");f.completeSource();assertNull(evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)).capture(day));var captured=evidence(f,Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).capture(day);assertEquals("partial",captured.grouping().coverageState());assertEquals(1,captured.entities().getFirst().members().size());assertEquals(2,CanonicalJson.parse(captured.grouping().json()).get("source_decisions").size());assertEquals(2,f.f.sends);
    }
    @Test void OriginalPlanDamageWrongIssuerAndTransactionRolesRejectWithoutWrites(){
        var f=new Fixture();f.terminal("skill");f.completeSource();var provider=evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));var captured=provider.capture(day);int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertThrows(RuntimeException.class,()->provider.verifyObserved(captured));assertThrows(RuntimeException.class,()->f.write(()->provider.verifyObserved(captured)));assertThrows(RuntimeException.class,()->snapshot(f,()->provider.captureOriginal(day)));assertThrows(RuntimeException.class,()->snapshot(f,()->evidence(f,mock(Clock.class)).verifyObserved(captured)));
        assertTrue(f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id=?",new byte[32],f.reference.ownerId(),com.lianxibu.site.repository.jobs.JdbcFrozenSourcePlans.stamp(f.reference.ownerId()))>0);assertThrows(RuntimeException.class,()->snapshot(f,()->provider.verifyObserved(captured)));assertThrows(RuntimeException.class,()->provider.captureOriginal(day));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
    }
    @Test void ClosedCategoryConstructionPerformsNoIo(){
        var finals=mock(CatalogDayOriginalFinalsService.class);var plans=mock(FrozenSourceStagePlanService.class);var manager=mock(PlatformTransactionManager.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogDayEntityEvidenceService(finals,plans,manager,category));verifyNoInteractions(finals,plans,manager);assertThrows(RuntimeException.class,()->new CatalogDayEntityEvidenceService(finals,plans,manager,"news_tech"));
    }
}
