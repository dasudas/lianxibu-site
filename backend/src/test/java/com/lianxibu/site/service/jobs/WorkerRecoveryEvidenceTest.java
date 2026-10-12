package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.WorkerRecoveryTelemetry.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class WorkerRecoveryEvidenceTest {
 final Instant now=Instant.parse("2026-01-01T00:00:00.123456Z");final WorkerRecoveryEvidence validator=new WorkerRecoveryEvidence();
 Evidence fact(Instant at,long failures){return new Evidence(at,new Snapshot(100,1,false),failures,failures==0);}
 @Test void originalFullSuccessPreservesActualOverdueAndConfiguredRecovery(){assertEquals(new OperationalRuleEngine.SlotRecovery(100,0,true),validator.validate(fact(now,0),now,3));assertEquals(new OperationalRuleEngine.SlotRecovery(0,0,true),validator.validate(new Evidence(now,new Snapshot(0,0,true),0,true),now,3));}
 @Test void failedScanNeverBecomesHealthyBeforeItsConfiguredCriticalThreshold(){assertNull(validator.validate(fact(now,1),now,3));assertNull(validator.validate(fact(now,2),now,3));assertEquals(new OperationalRuleEngine.SlotRecovery(100,3,false),validator.validate(fact(now,3),now,3));assertEquals(new OperationalRuleEngine.SlotRecovery(100,1,false),validator.validate(fact(now,1),now,1));}
 @Test void exactFreshBoundaryPassesButFutureAndOlderFactsStayUnknown(){assertNotNull(validator.validate(fact(now.minusSeconds(120),0),now,3));assertNull(validator.validate(fact(now.minusSeconds(120).minusNanos(1000),0),now,3));assertNull(validator.validate(fact(now.plusNanos(1000),0),now,3));assertNull(validator.validate(null,now,3));}
 @Test void missingOrMalformedClockAndConfigurationCannotQualifySnapshot(){assertNull(validator.validate(fact(now,0),null,3));assertNull(validator.validate(fact(now,0),now.plusNanos(1),3));assertNull(validator.validate(fact(now,0),now,0));assertNull(validator.validate(fact(now,0),now,101));}
}
