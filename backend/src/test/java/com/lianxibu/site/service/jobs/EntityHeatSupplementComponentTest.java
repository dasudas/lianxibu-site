package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import org.junit.jupiter.api.*;
/** Actual native count/event/first/header composition; unavailable original source-day ports are explicit doubles. */
class EntityHeatSupplementComponentTest {
 boolean mysql(){return false;}
 EntityHeatReportComponentTest h;Instant cutoff;
 @BeforeEach void setup(){h=new EntityHeatReportComponentTest(){@Override GenerationJdbcStoreTest fixture(){return EntityHeatSupplementComponentTest.this.mysql()?new GenerationJdbcStoreMySqlTest():new GenerationJdbcStoreTest();}};h.setup();cutoff=h.cutoff.plusSeconds(17).plusNanos(1000);}
 @Test void RealNewCutoffAndObservedNativeCountStillRequireFullOriginalSource(){h.first.capture(h.h.id);var value=h.service.captureSupplement(cutoff);assertEquals(cutoff,value.cutoff());assertTrue(value.candidates().isEmpty());assertTrue(value.missing().stream().anyMatch(m->"count_source_origin_unproven".equals(m.reason())));assertTrue(value.dependencies().isEmpty());assertTrue(value.referenceTargetsByEvidence().isEmpty());assertSame(value,h.rr(()->h.service.verifyObserved(value)));assertThrows(RuntimeException.class,()->h.service.capture(cutoff));}
 @Test void ActualEmptyWholeNativeUniverseIsKnownEmptyWithoutFakeCountsOrBodies(){h.h.h.f.jdbc.update("DELETE FROM jobs");h.h.h.f.jdbc.update("DELETE FROM analyzed_entities");var value=h.service.captureSupplement(cutoff);assertTrue(value.candidates().isEmpty());assertTrue(value.missing().isEmpty());assertTrue(value.dependencies().isEmpty());assertSame(value,h.rr(()->h.service.verifyObserved(value)));assertThrows(UnsupportedOperationException.class,()->value.missing().clear());}
 @Test void UnobservedOriginalAndChangedNativeHeaderFailOrRemainExplicitMissing(){var unobserved=h.service.captureSupplement(cutoff);assertTrue(unobserved.missing().stream().anyMatch(m->"count_unobserved_at_cutoff".equals(m.reason())));h.first.capture(h.h.id);var value=h.service.captureSupplement(cutoff);h.h.h.f.jdbc.update("UPDATE source_run_items SET revision=revision+1,effective_valid=0 WHERE id='fictional-one'");assertThrows(RuntimeException.class,()->h.rr(()->h.service.verifyObserved(value)));var fresh=h.service.captureSupplement(cutoff);assertTrue(fresh.candidates().isEmpty());assertFalse(fresh.missing().isEmpty());}
 @Test void FullFactBudgetIssuerMicrosecondAndOwnedRrRemainMandatory(){h.first.capture(h.h.id);var value=h.service.captureSupplement(cutoff);assertThrows(RuntimeException.class,()->h.create(1024).captureSupplement(cutoff));assertThrows(RuntimeException.class,()->h.service.captureSupplement(cutoff.plusNanos(1)));assertThrows(RuntimeException.class,()->h.rr(()->h.service.captureSupplement(cutoff)));assertThrows(RuntimeException.class,()->h.service.verifyObserved(value));assertThrows(RuntimeException.class,()->h.rr(()->h.create(EntityHeatCutoffProofService.MAX_ORIGINAL_BYTES).verifyObserved(value)));}
}
