package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual full day/run/lease graph. Node authorization and the OPS capacity gate are doubles. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunLeaseIntegrationMySqlTest {
    private SourceDayRunClaimAdmissionIntegrationMySqlTest graph;
    private SourceDayRunCoordinatorServiceMySqlTest fixture;
    private NodeIdentityService nodes;
    private SourceRunLeaseService leases;
    private Clock clock;

    @BeforeEach void setup() {
        graph=new SourceDayRunClaimAdmissionIntegrationMySqlTest();graph.setup();fixture=graph.fixture;
        fixture.fixture.jdbc.execute("ALTER TABLE source_runs ADD claim_node_id VARCHAR(64),ADD claim_token_hash BINARY(32),ADD claim_token_ciphertext BLOB,ADD claim_key_id VARCHAR(64),ADD lease_until DATETIME(6),ADD node_id VARCHAR(64),ADD started_at DATETIME(6)");
        fixture.fixture.jdbc.execute("ALTER TABLE request_receipts ADD control_operation VARCHAR(32)");
        nodes=mock(NodeIdentityService.class);authorize();clock=Clock.fixed(fixture.now,ZoneOffset.UTC);leases=service(fixture.manager,true);
    }
    private void authorize() {
        doAnswer(call->{String source=call.getArgument(2);if(!"fictional-bearer".equals(call.getArgument(0))||source!=null&&!Set.of("fictional-a","fictional-b").contains(source))throw new SecurityException("fictional unauthorized node");return new NodeIdentityService.Principal("fictional-node",List.of("fictional-a","fictional-b"),List.of("run:claim","run:write"),1);}).when(nodes).authorizeBearer(anyString(),anyString(),nullable(String.class));
    }
    private SourceRunLeaseService service(PlatformTransactionManager manager,boolean dayGuard) {
        return new CollectorRuntimeConfiguration().createLeases(fixture.fixture.source,manager,nodes,fixture.gate,Base64.getEncoder().encodeToString(new byte[32]),"fictional",dayGuard?graph.consumer:null,clock);
    }
    private SourceRunLeaseService.Claim claim(String key) {return leases.claimNext("fictional-bearer",false,new SharedWriteExecutor.ActionContext(key,"fictional-control-resource",null));}
    private int controls() {return fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE receipt_kind='collector_control'",Integer.class);}
    private int activeLeases() {return fixture.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM source_runs WHERE claim_token_hash IS NOT NULL",Integer.class);}
    private Instant utc(String sql) {return fixture.fixture.jdbc.queryForObject(sql,(rs,index)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());}
    private SourceDayRunRegistration.Reservation reserveA() {var day=graph.coordinator.open(fixture.day);return graph.coordinator.reserve(day,"fictional-a");}

    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void originalFullDayAndActualRunReceiptProduceOneUtcLeaseAndReplayWithoutSealSql(String zone) {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var day=graph.coordinator.open(fixture.day);var registered=graph.coordinator.reserve(day,"fictional-a");var claim=claim("fictional-full-claim");
            assertEquals(registered.request().runId(),claim.runId());assertNotNull(claim.claimToken());assertTrue(claim.currentLeaseValid());
            assertEquals(2,day.plan().cohort("agent").members().size());assertEquals(1,day.plan().unassignedSourceIds().size());assertEquals(1,activeLeases());assertEquals(1,controls());
            assertEquals(fixture.day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),claim.windowStart());
            assertEquals(fixture.now.plusSeconds(150),utc("SELECT lease_until FROM source_runs"));
            assertEquals(fixture.now,utc("SELECT created_at FROM request_receipts WHERE receipt_kind='collector_control'"));
            assertEquals(fixture.now,utc("SELECT created_at FROM request_receipts WHERE operation='daily_register'"));
            fixture.fixture.jdbc.execute("DROP TABLE jobs");fixture.fixture.jdbc.execute("DROP TABLE sources");
            var replay=claim("fictional-full-claim");assertEquals(claim.claimToken(),replay.claimToken());assertEquals(claim.leaseUntil(),replay.leaseUntil());assertEquals(1,controls());
        } finally {TimeZone.setDefault(original);}
    }

    @Test void missingFullSealOrDefaultUnconfiguredGuardCannotCreateDailyLease() {
        reserveA();fixture.fixture.jdbc.update("DELETE FROM jobs WHERE record_kind='disposition'");
        assertNull(claim("fictional-no-seal").runId());assertEquals(0,controls());assertEquals(0,activeLeases());
        leases=service(fixture.manager,false);assertNull(claim("fictional-no-guard").runId());assertEquals(0,controls());assertEquals(0,activeLeases());
    }

    @Test void corruptOriginalCannotStarveIndependentValidSourceOrShrinkOriginalCohort() {
        var day=graph.coordinator.open(fixture.day);var a=graph.coordinator.reserve(day,"fictional-a");var b=graph.coordinator.reserve(day,"fictional-b");
        var corrupted=a.request().runId().compareTo(b.request().runId())<0?a:b;var valid=corrupted==a?b:a;
        fixture.fixture.jdbc.update("UPDATE source_runs SET base_run_id='fictional-invalid-base' WHERE id=?",corrupted.request().runId());
        var claim=claim("fictional-independent");assertEquals(valid.request().runId(),claim.runId());assertEquals(1,controls());assertEquals(1,activeLeases());
        assertEquals(2,day.plan().cohort("agent").members().size());assertEquals(2,day.requests().size());assertEquals("pending",new JdbcSourceRunStore(fixture.fixture.source).byId(corrupted.request().runId()).deliveryState());
    }

    @Test void currentNodeAndConfigFencesStillRejectWithoutTokens() {
        reserveA();doThrow(new SecurityException("fictional revoked node")).when(nodes).authorizeBearer(anyString(),anyString(),nullable(String.class));
        assertThrows(SecurityException.class,()->claim("fictional-revoked"));assertEquals(0,controls());assertEquals(0,activeLeases());authorize();
        fixture.fixture.jdbc.update("UPDATE sources SET config_revision=4 WHERE id='fictional-a'");assertNull(claim("fictional-changed-config").runId());assertEquals(0,controls());assertEquals(0,activeLeases());
    }

    @Test void committedClaimWithLostAcknowledgementReplaysOneOriginalTokenAndReceipt() {
        reserveA();var tx=new DataSourceTransactionManager(fixture.fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional claim commit acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        leases=service(tx,true);var first=claim("fictional-ack-loss");assertNotNull(first.claimToken());assertEquals(first.claimToken(),claim("fictional-ack-loss").claimToken());assertEquals(1,controls());assertEquals(1,activeLeases());
    }

    @Test void missingSqlIsFailureAndNeverSuccessfulEmptyLease() {
        reserveA();fixture.fixture.jdbc.execute("DROP TABLE request_receipts");assertThrows(RuntimeException.class,()->claim("fictional-sql-failure"));assertEquals(0,activeLeases());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(fixture.manager).execute(s->claim("fictional-outer")));
    }
}
