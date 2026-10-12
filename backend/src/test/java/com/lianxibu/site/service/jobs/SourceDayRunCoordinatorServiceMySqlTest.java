package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunCoordinatorServiceMySqlTest extends SourceDayCohortSealServiceMySqlTest {
    OperationalInputGate gate;
    @BeforeEach void runTables(){
        fixture.jdbc.execute("DROP TABLE IF EXISTS source_run_items");fixture.jdbc.execute("DROP TABLE IF EXISTS request_receipts");fixture.jdbc.execute("DROP TABLE IF EXISTS source_runs");
        fixture.jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,created_at DATETIME(6),revision BIGINT,source_id VARCHAR(64),run_kind VARCHAR(32),business_date DATE,generation BIGINT,window_start DATETIME(6),window_end DATETIME(6),base_run_id VARCHAR(64),config_version BIGINT,config_snapshot_json JSON,activation_stage VARCHAR(32),fetch_mode VARCHAR(32),collection_reason VARCHAR(32),delivery_state VARCHAR(32),fetch_state VARCHAR(32),analysis_state VARCHAR(32),accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,coverage_json JSON,job_refs_json JSON,UNIQUE(source_id,run_kind,business_date,generation))");
        fixture.jdbc.execute("CREATE TABLE request_receipts(id VARCHAR(64) PRIMARY KEY,principal VARCHAR(80),operation VARCHAR(191),request_key VARCHAR(128),request_hash BINARY(32),status_code INT,response_json JSON,expires_at DATETIME(6),receipt_kind VARCHAR(32),actor_id VARCHAR(64),scope VARCHAR(64),key_hash BINARY(32),run_id VARCHAR(64),original_status VARCHAR(32),current_status VARCHAR(32),ack_json JSON,result_ref_json JSON,created_at DATETIME(6),revision BIGINT,request_key_raw VARCHAR(128),business_hash BINARY(32),UNIQUE(actor_id,scope,key_hash))");
        gate=mock(OperationalInputGate.class);doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(1,dispositions());return true;}).when(gate).newRunAllowed(anyString());
    }
    void twoConfigured(){
        for(String id:List.of("fictional-a","fictional-b"))fixture.jdbc.update(c->{var ps=c.prepareStatement("INSERT INTO sources VALUES(?,'agent',2,3,'production','disabled',?,NULL,?)");ps.setString(1,id);ps.setTimestamp(2,Timestamp.from(now.plusSeconds(86400)),Calendar.getInstance(TimeZone.getTimeZone("UTC")));ps.setString(3,"{\"valid_until\":\"2026-09-21T16:10:00.000001Z\",\"fixture\":\"fictional\"}");return ps;});
        fixture.jdbc.update("INSERT INTO sources VALUES('fictional-legacy',NULL,0,0,'draft','disabled',NULL,NULL,NULL)");
    }
    private SourceDayRunCoordinatorService coordinator(Clock clock){
        var seals=new SourceDayCohortSealConfiguration().create(fixture.source,manager,clock);var runs=new SourceDayRunRegistrationConfiguration().create(fixture.source,manager,gate,clock);return new SourceDayRunCoordinatorConfiguration().create(seals,runs);
    }
    private int runs(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class);}
    private int receipts(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class);}
    @Test void productionGraphSealsFullDayBeforeIndependentRunsAndOriginalReplayNeedsNoCurrentFacts(){
        twoConfigured();var service=coordinator(Clock.fixed(now,ZoneOffset.UTC));var day=service.open(this.day);assertEquals(0,runs());assertEquals(0,receipts());assertEquals(2,day.requests().size());assertEquals(1,day.plan().unassignedSourceIds().size());
        var first=service.reserve(day,"fictional-a");assertNotNull(first);assertEquals(1,runs());assertEquals(1,receipts());assertEquals(2,day.plan().cohort("agent").members().size());assertNotNull(service.reserve(day,"fictional-b"));assertEquals(2,runs());assertEquals(2,receipts());assertEquals(1,dispositions());
        fixture.jdbc.update("UPDATE source_runs SET revision=9,delivery_state='complete',analysis_state='complete'");fixture.jdbc.execute("DROP TABLE sources");reset(gate);var clock=mock(Clock.class);var restarted=coordinator(clock);var original=restarted.open(this.day);assertEquals(first,restarted.reserve(original,"fictional-a"));assertEquals(2,runs());assertEquals(2,receipts());verifyNoInteractions(gate,clock);
    }
    @Test void deniedOrChangedSourceIsStillPresentInCompleteOriginalCohort(){
        twoConfigured();var service=coordinator(Clock.fixed(now,ZoneOffset.UTC));var day=service.open(this.day);doReturn(false).when(gate).newRunAllowed("fictional-a");assertNull(service.reserve(day,"fictional-a"));assertEquals(0,runs());assertEquals(0,receipts());
        fixture.jdbc.update("UPDATE sources SET config_revision=4 WHERE id='fictional-b'");assertNull(service.reserve(day,"fictional-b"));assertEquals(0,runs());assertEquals(0,receipts());assertEquals(2,day.plan().requests().size());assertEquals(2,day.plan().cohort("agent").members().size());assertEquals(1,dispositions());
    }
    @Test void actualRunCommitAcknowledgementLossReturnsOriginalWithSingleGateAndReceipt(){
        twoConfigured();var seals=new SourceDayCohortSealConfiguration().create(fixture.source,manager,Clock.fixed(now,ZoneOffset.UTC));seals.seal(day);
        var tx=new DataSourceTransactionManager(fixture.source){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional run acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        SourceDayRunRegistration.Api api=new SourceDayRunRegistrationConfiguration().create(fixture.source,tx,gate,Clock.fixed(now,ZoneOffset.UTC));var coordinator=new SourceDayRunCoordinatorConfiguration().create(seals,api);var original=coordinator.open(day);var reserved=coordinator.reserve(original,"fictional-a");assertNotNull(reserved);assertEquals(reserved,coordinator.reserve(original,"fictional-a"));assertEquals(1,runs());assertEquals(1,receipts());verify(gate,times(1)).newRunAllowed("fictional-a");
    }
}
