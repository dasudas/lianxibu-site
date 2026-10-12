package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.sources.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;

class SourceDayRunLeaseAdmissionTest {
    JdbcSourceDayRunClaimSelectionTest selection;JdbcSourceDayRunRegistrationsTest fixture;SourceDayCollectionAdmission.Api days;SourceDayRunClaimAdmissionService admission;Clock clock;SourceRunLeaseService leases;
    @BeforeEach void setup()throws Exception{
        selection=new JdbcSourceDayRunClaimSelectionTest();selection.setup();fixture=selection.fixture;fixture.ddl("CREATE TABLE worker_lock(id INT PRIMARY KEY)");fixture.sql.update("INSERT INTO worker_lock VALUES(1)");fixture.ddl("ALTER TABLE request_receipts ADD control_operation VARCHAR(32)");
        days=mock(SourceDayCollectionAdmission.Api.class);when(days.read(fixture.day)).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return view(fixture.reserved);});
        admission=new SourceDayRunClaimAdmissionService(days,fixture.store,fixture.tx.getTransactionManager());clock=Clock.fixed(fixture.reserved.plusNanos(1000),ZoneOffset.UTC);leases=service(admission);
    }
    SourceDayCollectionAdmission.SealedDay view(Instant sealed){return new SourceDayCollectionAdmission.SealedDay(fixture.day,fixture.request.rosterHash(),"b".repeat(64),fixture.registered,sealed,1,0,Map.of("news_tech",0L,"agent",1L,"skill",0L,"workflow",0L,"model",0L),List.of(fixture.request));}
    SourceRunLeaseService service(SourceDayRunClaimAdmissionService admission){
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(fixture.data),fixture.tx.getTransactionManager(),()->25,n->{});var codec=new RunLeaseTokenCodec(new SecretKeySpec(new byte[32],"AES"),"fictional-key",new SecureRandom());
        SourceRunLeaseService.NodeAuthorizer nodes=(bearer,permission,sourceId)->{if(!"fictional-bearer".equals(bearer)||sourceId!=null&&!fixture.request.source().id().equals(sourceId))throw new SecurityException("fictional node unauthorized");return new NodeIdentityService.Principal("fictional-node",List.of(fixture.request.source().id()),List.of("run:claim","run:write"),1);};
        return new SourceRunLeaseService(selection.store,new JdbcSourceAdmission(fixture.data),writes,codec,nodes,new JdbcSourceRunReceipts(fixture.data,clock),clock,id->true,admission);
    }
    SourceRunLeaseService.Claim claim(String key){return leases.claimNext("fictional-bearer",false,new SharedWriteExecutor.ActionContext(key,"fictional-control",null));}
    int receipts(){return fixture.sql.queryForObject("SELECT COUNT(*) FROM request_receipts WHERE receipt_kind='collector_control'",Integer.class);}
    @Test void verifiedOriginalFactsCreateActualLeaseAndOriginalReplayDoesNotReadDayProvider(){
        var claim=claim("fictional-claim");assertEquals(fixture.request.runId(),claim.runId());assertNotNull(claim.claimToken());assertTrue(claim.currentLeaseValid());assertEquals(1,receipts());assertEquals("claimed",selection.store.byId(claim.runId()).deliveryState());
        reset(days);when(days.read(any())).thenThrow(new IllegalStateException("fictional current provider unavailable"));assertEquals(claim.claimToken(),claim("fictional-claim").claimToken());verifyNoInteractions(days);assertEquals(1,receipts());
    }
    @Test void missingFullDayAndDefaultUnconfiguredServiceNeverIssueNewDailyLease(){
        doReturn(null).when(days).read(fixture.day);assertNull(claim("fictional-missing").runId());assertEquals(0,receipts());assertEquals("pending",selection.store.byId(fixture.request.runId()).deliveryState());
        leases=service(null);assertNull(claim("fictional-unconfigured").runId());assertEquals(0,receipts());assertNull(selection.store.byId(fixture.request.runId()).claimTokenHash());
    }
    @Test void earlyOrDamagedActualOriginalIsExcludedBeforeLeaseAndToken(){
        doReturn(view(fixture.reserved.plusNanos(1000))).when(days).read(fixture.day);assertNull(claim("fictional-early").runId());assertEquals(0,receipts());
        doReturn(view(fixture.reserved)).when(days).read(fixture.day);fixture.sql.update("UPDATE source_runs SET config_version=4");assertNull(claim("fictional-corrupt").runId());assertEquals(0,receipts());assertNull(selection.store.byId(fixture.request.runId()).claimTokenHash());
    }
    @Test void sourceSqlFailureAndCallerTransactionCannotBeReportedAsSuccessfulEmptyClaim(){
        fixture.sql.execute("DROP TABLE request_receipts");assertThrows(RuntimeException.class,()->claim("fictional-sql-missing"));assertThrows(IllegalStateException.class,()->fixture.tx.execute(s->claim("fictional-outer")));assertNull(selection.store.byId(fixture.request.runId()).claimTokenHash());
    }
}
