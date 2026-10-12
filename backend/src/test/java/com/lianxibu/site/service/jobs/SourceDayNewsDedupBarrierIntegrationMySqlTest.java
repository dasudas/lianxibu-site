package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual SQL provider composition. OPS gate and the already-applied P1 genesis remain fictional fixtures. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayNewsDedupBarrierIntegrationMySqlTest {
    final LocalDate day=LocalDate.of(2026,1,1);final Instant registered=Instant.parse("2026-01-01T16:10:00Z"),p1At=registered.plusSeconds(600);
    SourceDayNewsDedupProgressServiceTest p1;GenerationJdbcStoreTest f;DataSourceTransactionManager manager;OperationalInputGate gate;
    SourceDayCohortSealService cohorts;SourceDayRunRegistration.Api runs;SourceDayRunCoordinatorService coordinator;SourceAnalysisProofGraph graph;
    @BeforeEach void setup(){install(5);}
    void install(int count){
        String runId=JdbcFrozenInputs.stableId("source-day-run",day+"\nfictional-source");
        p1=new SourceDayNewsDedupProgressServiceTest(){
            @Override GenerationJdbcStoreTest fixture(){var fixture=new GenerationJdbcStoreMySqlTest();fixture.clock=Clock.fixed(p1At,ZoneOffset.UTC);return fixture;}
            @Override String runId(){return runId;}
            @Override SourcePartitionPlanner.Scope scope(){return new SourcePartitionPlanner.Scope(day,p1At.minusSeconds(60),FrozenSourceStagePlanTest.scope().settings());}
        };p1.install(count,runId);f=p1.f.f;manager=new DataSourceTransactionManager(f.source);
        var schema=new SourceDayRunCoordinatorServiceMySqlTest();schema.fixture=f;schema.runTables();
        f.jdbc.execute("DROP TABLE IF EXISTS sources");f.jdbc.execute("CREATE TABLE sources(id VARCHAR(64) PRIMARY KEY,category VARCHAR(32),revision BIGINT,config_revision BIGINT,activation_stage VARCHAR(32),state VARCHAR(32),config_valid_until DATETIME(6),draft_config_json JSON,effective_config_json JSON)");
        for(String source:List.of("fictional-source","fictional-second"))f.jdbc.update(c->{var ps=c.prepareStatement("INSERT INTO sources VALUES(?,'news_tech',2,3,'production','enabled',?,NULL,?)");ps.setString(1,source);ps.setTimestamp(2,java.sql.Timestamp.from(registered.plusSeconds(86400)),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(3,"{\"valid_until\":\"2026-01-02T16:10:00.000000Z\",\"fixture\":\"fictional\"}");return ps;});
        f.jdbc.update("INSERT INTO sources VALUES('fictional-legacy',NULL,0,0,'draft','disabled',NULL,NULL,NULL)");
        gate=mock(OperationalInputGate.class);when(gate.newRunAllowed(anyString())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return true;});
        cohorts=new SourceDayCohortSealConfiguration().create(f.source,manager,Clock.fixed(registered,ZoneOffset.UTC));
        runs=new SourceDayRunRegistrationConfiguration().create(f.source,manager,gate,Clock.fixed(registered.plusSeconds(1),ZoneOffset.UTC));coordinator=new SourceDayRunCoordinatorConfiguration().create(cohorts,runs);
        graph=new SourceAnalysisProofGraph(f.source,manager,Clock.fixed(p1At,ZoneOffset.UTC),()->false);
    }
    SourceDayNewsDedupBarrierService barrier(Clock clock){var config=new SourceDayNewsDedupConfiguration();return config.createBarrier(f.source,manager,cohorts,config.createProgress(manager,runs,graph),clock);}
    int closures(){return f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_day_dedup'",Integer.class);}
    void completed(){p1.complete();assertEquals(p1At,graph.dedupObserver().capture(p1.ref()));}
    @Test void actualOriginalRegistrationRequiredEvenWhenOriginalP1AlreadyExistsAndWholeDayCannotShrink(){
        var original=coordinator.open(day);completed();assertNull(barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(0,closures());
        assertNotNull(coordinator.reserve(original,"fictional-source"));assertNull(barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(2,original.plan().cohort("news_tech").members().size());
        var closed=barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day);assertEquals(1,closures());assertEquals(1,closed.closure().entries().stream().filter(e->e.decision()==SourceDedupBarrier.Decision.INCLUDED).count());
        assertEquals("not_completed",closed.closure().entries().stream().filter(e->e.sourceId().equals("fictional-second")).findFirst().orElseThrow().reason());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void fullActualDayWithOneDisabledSourceClosesEarlyAndReplaysWithoutCurrentSourceOrClock(){
        f.jdbc.update("UPDATE sources SET activation_stage='disabled' WHERE id='fictional-second'");var original=coordinator.open(day);assertNotNull(coordinator.reserve(original,"fictional-source"));completed();
        var first=barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day);assertTrue(first.closure().early());assertEquals(3,first.original().plan().directory().evidence().sourceCount());assertEquals(1,first.original().plan().unassignedSourceIds().size());assertEquals(2,first.closure().entries().size());
        f.jdbc.execute("DROP TABLE sources");Clock clock=mock(Clock.class);reset(gate);var replay=barrier(clock).close(day);assertEquals(first.closure().json(),replay.closure().json());verifyNoInteractions(clock,gate);assertEquals(1,closures());
    }
    @Test void genuineEmptyOriginalP1HasItsOwnFirstObservationAndCannotBeInferredFromMissingRegistration(){
        install(0);f.jdbc.update("UPDATE sources SET activation_stage='disabled' WHERE id='fictional-second'");var original=coordinator.open(day);
        assertNull(barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day));assertNotNull(coordinator.reserve(original,"fictional-source"));
        assertNull(barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day));assertEquals(p1At,graph.dedupObserver().capture(p1.ref()));
        var result=barrier(Clock.fixed(p1At.plusSeconds(60),ZoneOffset.UTC)).close(day);assertEquals(SourceDedupBarrier.State.EMPTY_SUCCESS,result.closure().entries().stream().filter(e->e.sourceId().equals("fictional-source")).findFirst().orElseThrow().progress().state());assertTrue(result.closure().early());assertEquals(0,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void changedActualRunOrPermanentReceiptCannotGrantOriginalReadyBarrier(){
        var original=coordinator.open(day);var reserved=coordinator.reserve(original,"fictional-source");completed();f.jdbc.update("UPDATE source_runs SET config_version=4 WHERE id=?",reserved.request().runId());
        assertThrows(RuntimeException.class,()->barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day));assertEquals(0,closures());f.jdbc.update("UPDATE source_runs SET config_version=3 WHERE id=?",reserved.request().runId());
        f.jdbc.update("DELETE FROM request_receipts");assertThrows(RuntimeException.class,()->barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day));assertEquals(0,closures());
    }
    @Test void actualP1DamageAndReceiptSqlFailureNeverPublishSuccessfulEmptyClosure(){
        var original=coordinator.open(day);coordinator.reserve(original,"fictional-source");completed();f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE id=?",new byte[32],p1.f.part());
        assertThrows(RuntimeException.class,()->barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day));assertEquals(0,closures());
        install(0);original=coordinator.open(day);coordinator.reserve(original,"fictional-source");f.jdbc.execute("DROP TABLE request_receipts");
        assertThrows(RuntimeException.class,()->barrier(Clock.fixed(SourceDedupBarrier.deadline(day),ZoneOffset.UTC)).close(day));assertEquals(0,closures());
    }
}
