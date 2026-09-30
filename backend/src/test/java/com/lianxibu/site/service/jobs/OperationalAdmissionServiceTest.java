package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.OperationalAdmissionService.*;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.settings.OperationalRulePolicy;
import com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalConfiguration;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class OperationalAdmissionServiceTest {
    final Instant sampled=Instant.parse("2026-10-01T00:00:00Z");
    final Key capacity=new Key("OPS-04","database","fictional-rds"),telemetry=new Key("OPS-10","monitor","fictional-metrics"),slot=new Key("OPS-03","worker","fictional-slot"),memory=new Key("OPS-06","java","fictional-node"),provider=new Key("OPS-08","provider","fictional-provider"),contract=new Key("OPS-07","source","fictional-source");
    final Scopes scopes=new Scopes(List.of(capacity,telemetry),List.of(slot,memory,provider),Map.of("fictional-source",List.of(contract)));
    OperationalConfiguration config=new OperationalConfiguration(1,MonitoringFixtures.complete(),true);
    List<State> states=new ArrayList<>(List.of(sample(capacity,new Capacity(new BigDecimal("50"))),sample(telemetry,new Telemetry(0,0)),sample(slot,new SlotRecovery(0,0,true)),sample(memory,new Memory(true,new BigDecimal("500"),false)),sample(provider,new Provider(false,0,0,false)),sample(contract,new ContractFailures(0,0,true))));
    State sample(Key key,Reading reading){var rule=MonitoringFixtures.complete().stream().filter(r->r.ruleId().equals(key.ruleId())).findFirst().orElseThrow();return new OperationalRuleEngine().sample(State.empty(key),OperationalRulePolicy.compile(rule),1,sampled,true,reading).state();}
    OperationalAdmissionService service(Instant now){return new OperationalAdmissionService(()->new Snapshot(config,states),scopes,Clock.fixed(now,ZoneOffset.UTC));}
    @Test void eachOperationRequiresFreshConfiguredResourcesButProviderUnknownUsageIsOnlyWarning(){
        for(Operation operation:Operation.values())assertTrue(service(sampled).check(operation,"fictional-source").allowed());
        states.set(4,sample(provider,new Provider(false,3,86401,false)));
        assertTrue(service(sampled).check(Operation.DISPATCH_AI,null).allowed());
        states.set(4,sample(provider,new Provider(true,0,0,false)));
        assertFalse(service(sampled).check(Operation.DISPATCH_AI,null).allowed());assertTrue(service(sampled).check(Operation.PREPARE_ANALYSIS,null).allowed());
        assertFalse(service(sampled).check(Operation.NEW_RUN,"unregistered-fictional-source").allowed());
    }
    @Test void unknownConfigurationMissingSamplesWrongVersionAndSamplerOutageFailClosed(){
        config=new OperationalConfiguration(1,MonitoringFixtures.complete(),false);assertEquals(Reason.CONFIGURATION_UNKNOWN,service(sampled).check(Operation.PREPARE_ANALYSIS,null).reason());
        config=new OperationalConfiguration(2,MonitoringFixtures.complete(),true);assertFalse(service(sampled).check(Operation.PREPARE_ANALYSIS,null).allowed());
        config=new OperationalConfiguration(1,MonitoringFixtures.complete(),true);assertTrue(service(sampled.plusSeconds(600)).check(Operation.UPLOAD_OBSERVATION,null).allowed());assertFalse(service(sampled.plusSeconds(601)).check(Operation.UPLOAD_OBSERVATION,null).allowed());
        assertFalse(service(sampled.minusSeconds(1)).check(Operation.UPLOAD_OBSERVATION,null).allowed());states.removeFirst();assertFalse(service(sampled).check(Operation.UPLOAD_OBSERVATION,null).allowed());
        config=new OperationalConfiguration(1,MonitoringFixtures.complete().subList(0,9),true);assertEquals(Reason.CONFIGURATION_UNKNOWN,service(sampled).check(Operation.UPLOAD_OBSERVATION,null).reason());
    }
    @Test void latchedCapacityAndContractGuardsSurviveUnknownAndAreScoped(){
        var protectedState=sample(capacity,new Capacity(new BigDecimal("4")));states.set(0,protectedState);
        for(Operation operation:Operation.values())assertEquals(Reason.CAPACITY_GUARD,service(sampled).check(operation,"fictional-source").reason());
        var rule=OperationalRulePolicy.compile(MonitoringFixtures.complete().get(3));states.set(0,new OperationalRuleEngine().sample(protectedState,rule,1,sampled.plusSeconds(60),false,null).state());
        assertEquals(Reason.CAPACITY_GUARD,service(sampled.plusSeconds(60)).check(Operation.UPLOAD_OBSERVATION,null).reason());
        states.set(0,sample(capacity,new Capacity(new BigDecimal("50"))));states.set(5,sample(contract,new ContractFailures(3,3,false)));
        assertFalse(service(sampled).check(Operation.NEW_RUN,"fictional-source").allowed());assertTrue(service(sampled).check(Operation.UPLOAD_OBSERVATION,null).allowed());
    }
    @Test void incompleteServerScopeCannotBeConstructed(){
        assertThrows(IllegalArgumentException.class,()->new Scopes(List.of(capacity),List.of(slot,memory,provider),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new Scopes(List.of(capacity,telemetry),List.of(slot,memory),Map.of()));
    }
}
