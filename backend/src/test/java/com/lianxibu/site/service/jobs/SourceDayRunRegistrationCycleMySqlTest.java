package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.SourceDayRunRegistrationRuntimeConfiguration;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** All day and run proofs are actual providers. The OPS capacity gate is an explicit double. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunRegistrationCycleMySqlTest {
    private SourceDayRunClaimAdmissionIntegrationMySqlTest graph;
    private SourceDayRunCoordinatorServiceMySqlTest fixture;
    @BeforeEach void setup(){graph=new SourceDayRunClaimAdmissionIntegrationMySqlTest();graph.setup();fixture=graph.fixture;}
    private SourceDayRunRegistrationCycle cycle(int batch){return new SourceDayRunRegistrationRuntimeConfiguration().createCycle(graph.coordinator,Clock.fixed(fixture.now,ZoneOffset.UTC),batch);}
    private int runs(){return fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class);}
    @Test void boundedRotationPreservesUnavailableMemberAndRestartsThroughPermanentOriginalReceipts() {
        doReturn(false).when(fixture.gate).newRunAllowed("fictional-a");var cycle=cycle(1);
        var first=cycle.tick();assertEquals(2,first.planned());assertEquals(1,first.unverified());assertEquals(0,runs());
        var second=cycle.tick();assertEquals(1,second.verified());assertEquals(1,runs());assertEquals(2,graph.coordinator.open(fixture.day).plan().cohort("agent").members().size());assertEquals(1,graph.coordinator.open(fixture.day).plan().unassignedSourceIds().size());
        assertEquals(1,cycle.tick().unverified());doReturn(true).when(fixture.gate).newRunAllowed("fictional-a");assertEquals(1,cycle.tick().verified());assertEquals(1,runs());assertEquals(1,cycle.tick().verified());assertEquals(2,runs());
        fixture.fixture.jdbc.execute("DROP TABLE sources");reset(fixture.gate);var restarted=cycle(2);assertEquals(2,restarted.tick().verified());assertEquals(2,runs());assertEquals(2,fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));verifyNoInteractions(fixture.gate);
    }
    @Test void permanentBadOriginalFailsIndependentlyAndLeavesFullMembership() {
        var day=graph.coordinator.open(fixture.day);var a=graph.coordinator.reserve(day,"fictional-a");fixture.fixture.jdbc.update("UPDATE source_runs SET base_run_id='fictional-corrupt-base' WHERE id=?",a.request().runId());
        var outcome=cycle(2).tick();assertEquals(2,outcome.planned());assertEquals(2,outcome.attempted());assertEquals(1,outcome.failed());assertEquals(1,outcome.verified());assertEquals(2,runs());
        assertEquals(2,graph.coordinator.open(fixture.day).plan().cohort("agent").members().size());assertEquals(1,graph.prepare().runIds().size());assertEquals(1,graph.prepare().unverifiedRunIds().size());
    }
    @Test void missingSourceSqlCannotBecomeEmptyDayButActualZeroCanBeCompletelySealed() {
        var cycle=cycle(2);fixture.fixture.jdbc.execute("DROP TABLE sources");assertThrows(RuntimeException.class,cycle::tick);assertEquals(0,runs());assertEquals(0,fixture.dispositions());
        fixture.sources();var empty=cycle.tick();assertEquals(0,empty.planned());assertEquals(0,empty.attempted());assertEquals(0,runs());assertEquals(1,fixture.dispositions());
        var original=graph.coordinator.open(fixture.day);assertEquals(5,original.plan().cohorts().size());assertTrue(original.plan().cohorts().stream().allMatch(c->c.members().isEmpty()));assertEquals(0,original.plan().directory().evidence().sourceCount());
    }
}
