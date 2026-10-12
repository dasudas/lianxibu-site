package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.SourceDayRunRegistrationService;
import java.sql.SQLException;
import java.time.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class SourceDayRunRegistrationServiceTest {
    JdbcSourceDayRunRegistrationsTest fixture;DataSourceTransactionManager manager;SharedWriteExecutor writes;
    @BeforeEach void setup()throws Exception{
        fixture=new JdbcSourceDayRunRegistrationsTest();fixture.setup();fixture.sql.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");fixture.sql.update("INSERT INTO worker_lock VALUES(1)");
        manager=new DataSourceTransactionManager(fixture.data);writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(fixture.data),manager,()->25,n->{});
    }
    SourceDayRunRegistrationService service(Clock clock,Predicate<String> gate){return new SourceDayRunRegistrationService(fixture.store,writes,manager,clock,gate);}
    int runs(){return fixture.sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class);}
    @Test void actualGateAndSharedWriterCreateOriginalReservation(){
        var value=service(Clock.fixed(fixture.reserved,ZoneOffset.UTC),id->{assertEquals("fictional-source",id);assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return true;}).reserve(fixture.request);
        assertNotNull(value);assertEquals(1,runs());assertEquals(value,service(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),id->false).lookup(fixture.request));
    }
    @Test void gatedAndSourceChangedRemainUnknownWithNoSuccessRowsOrClockTouch(){
        Clock clock=mock(Clock.class);assertNull(service(clock,id->false).reserve(fixture.request));verifyNoInteractions(clock);assertEquals(0,runs());
        fixture.sql.update("UPDATE sources SET revision=7");assertNull(service(Clock.fixed(fixture.reserved,ZoneOffset.UTC),id->true).reserve(fixture.request));assertEquals(0,runs());assertEquals(0,fixture.sql.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void originalReplayIgnoresCurrentSourceClockAndGate(){
        var value=service(Clock.fixed(fixture.reserved,ZoneOffset.UTC),id->true).reserve(fixture.request);fixture.sql.execute("DROP TABLE sources");
        Clock clock=mock(Clock.class);@SuppressWarnings("unchecked") Predicate<String> gate=mock(Predicate.class);var restarted=service(clock,gate);assertEquals(value,restarted.reserve(fixture.request));verifyNoInteractions(clock,gate);assertEquals(1,runs());
    }
    @Test void invalidOuterTransactionAndClockOrDamagedCoreCannotWriteReplacement(){
        Clock clock=mock(Clock.class);@SuppressWarnings("unchecked") Predicate<String> gate=mock(Predicate.class);var service=service(clock,gate);verifyNoInteractions(clock,gate);
        var tx=new TransactionTemplate(manager);assertThrows(IllegalStateException.class,()->tx.execute(s->service.reserve(fixture.request)));assertThrows(IllegalStateException.class,()->tx.execute(s->service.lookup(fixture.request)));verifyNoInteractions(clock,gate);
        when(clock.instant()).thenThrow(new IllegalStateException("fictional clock missing"));assertThrows(RuntimeException.class,()->service(clock,id->true).reserve(fixture.request));assertEquals(0,runs());
        service(Clock.fixed(fixture.reserved,ZoneOffset.UTC),id->true).reserve(fixture.request);fixture.sql.update("UPDATE source_runs SET config_version=5");assertThrows(RuntimeException.class,()->service.reserve(fixture.request));verifyNoInteractions(gate);assertEquals(1,runs());
    }
    @Test void actualCommitAckLossReturnsOriginalWithoutSecondGateOrRun(){
        var manager=new DataSourceTransactionManager(fixture.data){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional commit acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(fixture.data),manager,()->25,n->{});var gates=new java.util.concurrent.atomic.AtomicInteger();
        var service=new SourceDayRunRegistrationService(fixture.store,writes,manager,Clock.fixed(fixture.reserved,ZoneOffset.UTC),id->{gates.incrementAndGet();return true;});
        var value=service.reserve(fixture.request);assertNotNull(value);assertEquals(value,service.reserve(fixture.request));assertEquals(1,gates.get());assertEquals(1,runs());
    }
}
