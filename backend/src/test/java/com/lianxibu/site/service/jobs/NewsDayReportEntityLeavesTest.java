package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

/** Original empty and explicit missing cohorts use actual native archives; enabled-source proof is tested on MySQL. */
class NewsDayReportEntityLeavesTest extends NewsDayReportComponentTest {
    @Test void EmptyOrUnqualifiedOriginalCohortHasNoIncludedDedupPlanAuthority(){cohorts.seal(h.day);barrier.close(h.day);var empty=service.capture(h.day,h.now);assertTrue(empty.includedDedupPlans().isEmpty());assertThrows(UnsupportedOperationException.class,()->empty.includedDedupPlans().clear());setup();OriginalUnconfiguredAndUnassignedSourcesAreExplicitMissingAndDisabledIsExcluded();assertTrue(service.capture(h.day,h.now).includedDedupPlans().isEmpty());}
    @Test void OriginalEmptyCohortHasNoEntityLeavesWithoutInventingUnknownSources(){cohorts.seal(h.day);barrier.close(h.day);var value=service.capture(h.day,h.now);assertTrue(value.completeSuccessfulEmpty());assertTrue(value.entityLeaves().isEmpty());assertTrue(value.originalByteSize()>0);assertThrows(UnsupportedOperationException.class,()->value.entityLeaves().clear());assertSame(value,rr(()->service.verifyObserved(value)));}
    @Test void UnconfiguredOrUnassignedSourcesKeepGapsAndCannotSupplyEntityBodies(){OriginalUnconfiguredAndUnassignedSourcesAreExplicitMissingAndDisabledIsExcluded();var value=service.capture(h.day,h.now);assertTrue(value.entityLeaves().isEmpty());assertFalse(value.missing().isEmpty());assertFalse(value.completeSuccessfulEmpty());assertSame(value,rr(()->service.verifyObserved(value)));}
}
