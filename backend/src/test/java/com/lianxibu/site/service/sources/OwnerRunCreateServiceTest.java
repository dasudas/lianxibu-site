package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.SourcesService.RunCreate;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class OwnerRunCreateServiceTest {
    JdbcTemplate jdbc;OwnerRunCreateService service;boolean allowed=true;
    final IdentityService.Actor actor=new IdentityService.Actor("fictional-owner","owner");
    SourceRunLeaseServiceTest fixture(){return new SourceRunLeaseServiceTest();}
    @BeforeEach void setup(){
        var fixture=fixture();fixture.setup();jdbc=fixture.jdbc;
        jdbc.execute("ALTER TABLE source_runs ADD expected_count BIGINT");jdbc.execute("ALTER TABLE source_runs ADD barrier_closed_at TIMESTAMP");
        fixture.ddl("ALTER TABLE source_runs ADD result_json CLOB");jdbc.execute("ALTER TABLE source_runs ADD manifest_hash BINARY(32)");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64),run_id VARCHAR(64),applied_at TIMESTAMP)");
        var ds=jdbc.getDataSource();var tx=new DataSourceTransactionManager(ds);
        service=new OwnerRunCreateService(new JdbcSourceRunStore(ds),new JdbcSourceAdmission(ds),new JdbcOwnerRunReceipts(ds),
            new SharedWriteExecutor(new JdbcSharedWriteEntry(ds),tx),new SourceRunReadService(new JdbcSourceRunReads(ds),tx),
            Clock.fixed(Instant.parse("2026-01-04T00:00:00Z"),ZoneOffset.UTC),id->allowed);
    }
    RunCreate command(String kind){return new RunCreate("fixture:feed",LocalDate.of(2026,1,1),Instant.parse("2025-12-31T16:00:00Z"),Instant.parse("2026-01-01T16:00:00Z"),kind,1,1,"Fictional owner collection");}
    @Test void originalRunReplaySurvivesClaimAndFurtherGenerations(){
        var c=command("historical_backfill");var first=service.create(actor,c,"fictional-key");assertEquals(1,first.generation());assertEquals("materialize",first.fetchMode());
        jdbc.update("UPDATE source_runs SET delivery_state='claimed',revision=2 WHERE id=?",first.runId());
        allowed=false;assertEquals(first,service.create(actor,c,"fictional-key"));allowed=true;
        var next=service.create(actor,c,"fictional-next");assertEquals(2,next.generation());assertNotEquals(first.runId(),next.runId());
        var qualification=service.create(actor,command("production_qualification"),"fictional-qualified");assertEquals(1,qualification.generation());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE expires_at IS NULL",Integer.class));
        assertThrows(IdentityService.Rejected.class,()->service.create(actor,command("production_qualification"),"fictional-key"));
        jdbc.update("UPDATE request_receipts SET response_json='{}' WHERE request_key='fictional-key'");
        assertThrows(IdentityService.Rejected.class,()->service.create(actor,c,"fictional-key"));
    }
    @Test void staleSourceDisabledAdmissionCapacityAndReceiptFailureNeverCreateRuns(){
        allowed=false;assertThrows(IdentityService.Rejected.class,()->service.create(actor,command("historical_backfill"),"fictional-capacity"));allowed=true;
        jdbc.update("UPDATE sources SET revision=2");assertThrows(IdentityService.Rejected.class,()->service.create(actor,command("historical_backfill"),"fictional-stale"));
        jdbc.update("UPDATE sources SET revision=1,activation_stage='disabled'");assertThrows(IdentityService.Rejected.class,()->service.create(actor,command("historical_backfill"),"fictional-disabled"));
        jdbc.update("UPDATE sources SET activation_stage='production'");
        jdbc.execute("ALTER TABLE request_receipts ADD CONSTRAINT fictional_run_receipt_failure CHECK(scope<>'owner-run-create-v1')");
        assertThrows(IdentityService.Rejected.class,()->service.create(actor,command("historical_backfill"),"fictional-failure"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void ownerAndExplicitWindowAreRequired(){
        assertThrows(IdentityService.Rejected.class,()->service.create(new IdentityService.Actor("fictional-user","user"),command("historical_backfill"),"fictional-user"));
        var c=command("historical_backfill");
        assertThrows(IllegalArgumentException.class,()->service.create(actor,new RunCreate(c.sourceId(),c.businessDate(),c.windowStart().minusSeconds(1),c.windowEnd(),c.runKind(),1,1,c.reason()),"fictional-window"));
        assertThrows(IllegalArgumentException.class,()->service.create(actor,command("trial"),"fictional-trial"));
    }
}
