package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry;import com.lianxibu.site.domain.jobs.ProviderCallTelemetry;
import java.time.Instant;import org.junit.jupiter.api.Test;
class ProviderEvidenceTest {
 final Instant now=Instant.parse("2026-01-01T00:00:00.123456Z");final ProviderEvidence validator=new ProviderEvidence();
 ProviderReadinessTelemetry.Evidence account(Instant at,ProviderReadinessTelemetry.Status s){return new ProviderReadinessTelemetry.Evidence(at,s);}
 ProviderCallTelemetry.Evidence calls(Instant at,long n,long age,long blocked){return new ProviderCallTelemetry.Evidence(at,n,age,blocked);}
 @Test void completeActualZeroAndReadyAreNormalWithoutClaimingRepair(){assertEquals(new OperationalRuleEngine.Provider(false,0,0,false),validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),calls(now,0,0,0),now));}
 @Test void eachDefiniteAccountFailureAndNativeConfigurationFailurePreservesCriticalSignal(){for(var status:ProviderReadinessTelemetry.Status.values()){var result=validator.validate(account(now,status),calls(now,2,999,0),now);assertEquals(status!=ProviderReadinessTelemetry.Status.READY,result.explicitFailure());assertEquals(2,result.unknownCount());assertEquals(999,result.oldestUnknownSeconds());assertFalse(result.repairedAndValidated());}assertTrue(validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),calls(now,0,0,1),now).explicitFailure());}
 @Test void NeitherMissingFactNorFutureStaleOrInvalidClockCanClaimHealth(){assertNull(validator.validate(null,calls(now,0,0,0),now));assertNull(validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),null,now));for(var at:new Instant[]{now.minusSeconds(120).minusNanos(1000),now.plusNanos(1000)}){assertNull(validator.validate(account(at,ProviderReadinessTelemetry.Status.READY),calls(now,0,0,0),now));assertNull(validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),calls(at,0,0,0),now));}assertNull(validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),calls(now,0,0,0),now.plusNanos(1)));assertNull(validator.validate(account(now,ProviderReadinessTelemetry.Status.READY),calls(now,0,0,0),null));}
 @Test void exactFreshBoundaryAndOriginalUnknownAgeArePreserved(){var at=now.minusSeconds(120);assertEquals(new OperationalRuleEngine.Provider(false,3,86400,false),validator.validate(account(at,ProviderReadinessTelemetry.Status.READY),calls(at,3,86400,0),now));}
}
