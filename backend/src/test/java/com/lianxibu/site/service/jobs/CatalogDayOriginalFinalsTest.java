package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native original registration, source worker/result/final/first observation and full day closure. */
class CatalogDayOriginalFinalsTest extends CatalogDayFullNativeIntegrationTest {
    CatalogDayOriginalFinalsService service(Fixture f,Clock clock){return new CatalogDayOriginalFinalsService(f.barrier(clock),new JdbcSourceDayDedupClosures(f.f.source),f.graph.plans(),f.graph.finalizer(),f.graph.observer(),f.f.tx,f.f.category);}
    <T>T snapshot(Fixture f,Supplier<T> work){var tx=new TransactionTemplate(f.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    @Test void FourCategoriesCaptureOnlyAllOriginalIncludedFinalsAndVerifyThemInOneNativeSnapshot(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=new Fixture();f.terminal(category);var service=service(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));assertNull(service.captureOriginal(day));assertNull(service.capture(day));f.completeSource();var captured=service.capture(day);assertNotNull(captured);assertEquals(category,captured.category());assertEquals(day,captured.day());assertEquals(completed.plusSeconds(60),captured.cutoff());assertEquals(1,captured.sources().size());assertEquals(f.committed().result().json(),captured.sources().getFirst().result().json());assertEquals(captured.closed().closure().hash(),captured.closureHash());assertSame(captured,snapshot(f,()->service.verifyObserved(captured)));assertThrows(UnsupportedOperationException.class,()->captured.sources().clear());assertFalse(captured.toString().contains("fictional-source"));
            int rows=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.f.jdbc.execute("DROP TABLE sources");f.f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");var replay=service(f,mock(Clock.class)).captureOriginal(day);assertEquals(captured.closureHash(),replay.closureHash());assertEquals(captured.sources().getFirst().result().json(),replay.sources().getFirst().result().json());assertEquals(rows,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
        }
    }
    @Test void NativeMissingSourceRemainsInOriginalClosureButDoesNotGrantAnIncludedFinal(){
        var f=new Fixture();f.secondEnabled=true;f.terminal("agent");f.completeSource();var service=service(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));assertNull(service.capture(day));var captured=service(f,Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).capture(day);assertNotNull(captured);assertEquals(2,captured.closed().closure().entries().size());assertEquals(1,captured.sources().size());assertEquals(SourceDedupBarrier.deadline(day),captured.cutoff());assertEquals("not_completed",captured.closed().closure().entries().stream().filter(e->e.sourceId().equals("fictional-second")).findFirst().orElseThrow().reason());assertEquals(2,f.f.sends);
    }
    @Test void OriginalNativeFinalDamageIssuerAndTransactionRolesRejectWithoutBusinessWrites(){
        var f=new Fixture();f.terminal("skill");f.completeSource();var service=service(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));var captured=service.capture(day);int rows=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertThrows(RuntimeException.class,()->service.verifyObserved(captured));assertThrows(RuntimeException.class,()->f.write(()->service.verifyObserved(captured)));assertThrows(RuntimeException.class,()->snapshot(f,()->service.captureOriginal(day)));assertThrows(RuntimeException.class,()->snapshot(f,()->service(f,mock(Clock.class)).verifyObserved(captured)));
        f.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE id=?",new byte[32],captured.sources().getFirst().receipt().position().receiptId());assertThrows(RuntimeException.class,()->snapshot(f,()->service.verifyObserved(captured)));assertThrows(RuntimeException.class,()->service.captureOriginal(day));assertEquals(rows,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
    }
    @Test void MandatoryClosedCategoryConstructionDoesNotPerformIo(){
        var barriers=mock(CatalogSourceDayDedupBarrierService.class);var closures=mock(JdbcSourceDayDedupClosures.class);var plans=mock(FrozenSourceStagePlanService.class);var finals=mock(CatalogSourceFinalizationService.class);var availability=mock(CatalogSourceFinalAvailabilityService.class);var manager=mock(PlatformTransactionManager.class);
        for(String category:List.of("agent","skill","workflow","model"))assertNotNull(new CatalogDayOriginalFinalsService(barriers,closures,plans,finals,availability,manager,category));verifyNoInteractions(barriers,closures,plans,finals,availability,manager);assertThrows(RuntimeException.class,()->new CatalogDayOriginalFinalsService(barriers,closures,plans,finals,availability,manager,"news_tech"));
    }
}
