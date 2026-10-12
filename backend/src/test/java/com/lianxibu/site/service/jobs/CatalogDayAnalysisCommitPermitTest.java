package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Full original native day permission; no entity reservation or generation input is created. */
class CatalogDayAnalysisCommitPermitTest extends CatalogDayEntityEvidenceTest {
    CatalogDayAnalysisCommitPermitService permits(Fixture f){return new CatalogDayAnalysisCommitPermitService(evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC)),f.graph.permits(),new JdbcSourceDayDedupClosures(f.f.source),f.f.tx,f.f.category);}
    @Test void FourCategoriesActualOriginalWholeDayReverifyInWriterWithoutAllocatingAnything(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=new Fixture();f.terminal(category);var service=permits(f);assertNull(service.capture(day));f.completeSource();var captured=service.capture(day);int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertSame(captured.evidence(),f.write(()->service.verify(captured)));assertEquals(captured.evidence().entities(),service.captureOriginal(day).evidence().entities());assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);assertEquals(2,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertFalse(captured.toString().contains("fictional"));
        }
    }
    @Test void AnyOriginalSourceChangeAfterCaptureRejectsAndCallerRollsBack(){
        var f=new Fixture();f.terminal("agent");f.completeSource();var service=permits(f);var captured=service.capture(day);String root=captured.evidence().entities().getFirst().members().getFirst().member().rootId();f.f.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);long entityRevision=f.f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);assertThrows(RuntimeException.class,()->f.write(()->{f.f.jdbc.update("UPDATE analyzed_entities SET revision=revision+1");return service.verify(captured);}));assertEquals(entityRevision,f.f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class));assertEquals(2,f.f.sends);
    }
    @Test void OriginalClosureDamageAndWrongIssuerOrTransactionCannotGrantWriterAuthority(){
        var f=new Fixture();f.terminal("model");f.completeSource();var service=permits(f);var captured=service.capture(day);assertThrows(RuntimeException.class,()->service.verify(captured));assertThrows(RuntimeException.class,()->snapshot(f,()->service.verify(captured)));assertThrows(RuntimeException.class,()->f.write(()->permits(f).verify(captured)));assertThrows(RuntimeException.class,()->snapshot(f,()->service.capture(day)));int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertTrue(f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='source_day_dedup'",new byte[32])>0);assertThrows(RuntimeException.class,()->f.write(()->service.verify(captured)));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
    }
    @Test void MandatoryCategoryConstructionPerformsNoIo(){
        var evidence=mock(CatalogDayEntityEvidenceService.class);var sources=mock(CatalogSourceFinalCommitPermitService.class);var closures=mock(JdbcSourceDayDedupClosures.class);var manager=mock(PlatformTransactionManager.class);for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogDayAnalysisCommitPermitService(evidence,sources,closures,manager,category));verifyNoInteractions(evidence,sources,closures,manager);assertThrows(RuntimeException.class,()->new CatalogDayAnalysisCommitPermitService(evidence,sources,closures,manager,"news_tech"));
    }
    @Test void ActualMySqlHoldsOriginalClosureCohortAndSourceLocksUntilCallerCommit(){
        var f=new Fixture();f.terminal("workflow");f.completeSource();var service=permits(f);var captured=service.capture(day);String root=captured.evidence().entities().getFirst().members().getFirst().member().rootId();f.write(()->{
            assertSame(captured.evidence(),service.verify(captured));if(mysql())try(var independent=f.f.source.getConnection();var statement=independent.createStatement()){
                statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                for(String predicate:List.of("target_type='source_day_dedup'","target_type='source_day_cohort'","id='"+root+"'")){
                    var rejected=assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("UPDATE jobs SET revision=revision+1 WHERE "+predicate));assertEquals(1205,rejected.getErrorCode());
                }
            }catch(java.sql.SQLException error){throw new IllegalStateException("fictional native independent lock check unavailable",error);}return true;
        });assertEquals(2,f.f.sends);
    }
}
