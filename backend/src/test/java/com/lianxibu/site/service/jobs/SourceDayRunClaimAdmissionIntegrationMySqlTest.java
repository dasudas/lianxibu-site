package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunClaimAdmissionIntegrationMySqlTest {
    SourceDayRunCoordinatorServiceMySqlTest fixture;SourceDayCohortSealService seals;SourceDayRunCoordinatorService coordinator;SourceDayRunClaimAdmissionService consumer;SourceDayRunRegistration.Api api;TransactionTemplate writer;
    @BeforeEach void setup(){
        fixture=new SourceDayRunCoordinatorServiceMySqlTest();fixture.setup();fixture.sources();fixture.runTables();fixture.twoConfigured();assemble(Clock.fixed(fixture.now,ZoneOffset.UTC));writer=new TransactionTemplate(fixture.manager);
    }
    void assemble(Clock clock){
        var source=fixture.fixture.source;seals=new SourceDayCohortSealConfiguration().create(source,fixture.manager,clock);api=new SourceDayRunRegistrationConfiguration().create(source,fixture.manager,fixture.gate,clock);
        coordinator=new SourceDayRunCoordinatorConfiguration().create(seals,api);var days=new SourceDayCollectionAdmissionConfiguration().create(seals);consumer=new SourceDayRunClaimAdmissionConfiguration().create(source,fixture.manager,days);
    }
    SourceDayRunClaimAdmissionService.Prepared prepare(){return consumer.prepare(Set.of(fixture.day),Set.of("fictional-a","fictional-b"));}
    boolean allowed(SourceDayRunClaimAdmissionService.Prepared prepared,String id){return Boolean.TRUE.equals(writer.execute(s->consumer.allows(prepared,id)));}
    @Test void realFullSealAndIndependentActualReceiptsGrantOnlyRegisteredSourceFacts(){
        var day=coordinator.open(fixture.day);var missing=prepare();assertTrue(missing.runIds().isEmpty());assertEquals(2,missing.unverifiedRunIds().size());for(var request:day.requests())assertFalse(allowed(missing,request.runId()));
        var first=coordinator.reserve(day,"fictional-a");var prepared=prepare();assertTrue(allowed(prepared,first.request().runId()));var second=day.requests().stream().filter(r->r.source().id().equals("fictional-b")).findFirst().orElseThrow();assertFalse(allowed(prepared,second.runId()));assertEquals(2,day.plan().cohort("agent").members().size());assertEquals(1,day.plan().unassignedSourceIds().size());
        assertNotNull(coordinator.reserve(day,"fictional-b"));assertFalse(allowed(prepared,second.runId()));assertTrue(allowed(prepare(),second.runId()));assertEquals(2,fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));assertEquals(2,fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void missingFullSealAndCorruptOriginalRunCannotBecomePermission(){
        var day=coordinator.open(fixture.day);var first=coordinator.reserve(day,"fictional-a");var prepared=prepare();fixture.fixture.jdbc.update("UPDATE source_runs SET config_version=4 WHERE id=?",first.request().runId());assertThrows(RuntimeException.class,()->allowed(prepared,first.request().runId()));
        fixture.fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='disposition'");var missing=prepare();assertTrue(missing.runIds().isEmpty());assertFalse(allowed(missing,first.request().runId()));assertEquals(2,day.requests().size());
    }
    @Test void actualRunReservedBeforeFullSealIsRejectedAfterLaterSeal(){
        var directory=new SourceDayRosterRegistrationConfiguration().create(fixture.fixture.source,fixture.manager,Clock.fixed(fixture.now,ZoneOffset.UTC)).freeze(fixture.day);var request=SourceDayCohortPlan.derive(directory).requests().getFirst();doReturn(true).when(fixture.gate).newRunAllowed(anyString());
        assertNotNull(api.reserve(request));assertEquals(0,fixture.dispositions());assemble(Clock.fixed(fixture.now.plusSeconds(10),ZoneOffset.UTC));var day=coordinator.open(fixture.day);var prepared=prepare();assertFalse(allowed(prepared,request.runId()));assertThrows(RuntimeException.class,()->coordinator.reserve(day,request.source().id()));assertEquals(1,fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));
    }
}
