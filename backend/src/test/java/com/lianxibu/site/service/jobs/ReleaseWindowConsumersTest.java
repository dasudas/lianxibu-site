package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseWindowConsumersTest {
    private static final Instant NOW=Instant.parse("2026-01-02T03:00:00Z");
    private static ReleaseAcceptanceWindow policy() {
        return ReleaseAcceptanceWindow.fromEnvironment(Map.of("SITE_AI_ACCEPTANCE_RELEASE","2099010101",
                "SITE_AI_ACCEPTANCE_ISSUED_AT",NOW.toString(),"SITE_AI_ACCEPTANCE_UNTIL",NOW.plusSeconds(120).toString()));
    }
    @Test void inputPreparationHonorsSamePermissionAndReturnsToStandardWindowAtExpiry() {
        assertEquals(NOW,GenerationFlowService.nextEligible(NOW,policy()));
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"),GenerationFlowService.nextEligible(NOW.plusSeconds(120),policy()));
        assertEquals(GenerationFlowService.nextEligible(NOW),GenerationFlowService.nextEligible(NOW,ReleaseAcceptanceWindow.normal()));
    }
    @Test void retryKeepsBackoffAndServerDelayAndCannotCrossExpiredPermission() {
        assertEquals(NOW.plusSeconds(30),AttemptFailureService.nextEligible(NOW,1,0,policy()));
        assertEquals(NOW.plusSeconds(60),AttemptFailureService.nextEligible(NOW,2,0,policy()));
        assertEquals(NOW.plusSeconds(90),AttemptFailureService.nextEligible(NOW,1,90,policy()));
        assertEquals(Instant.parse("2026-01-02T17:00:00Z"),AttemptFailureService.nextEligible(NOW,2,120,policy()));
        assertThrows(IllegalArgumentException.class,()->AttemptFailureService.nextEligible(NOW,3,0,policy()));
        assertThrows(IllegalArgumentException.class,()->AttemptFailureService.nextEligible(NOW,1,3601,policy()));
    }
}
