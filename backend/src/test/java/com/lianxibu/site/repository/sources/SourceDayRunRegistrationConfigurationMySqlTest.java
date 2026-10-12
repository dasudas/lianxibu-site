package com.lianxibu.site.repository.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.service.sources.SourceDayRunRegistrationService;
import java.sql.SQLException;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class SourceDayRunRegistrationConfigurationMySqlTest {
    JdbcSourceDayRunRegistrationsMySqlTest fixture;OperationalInputGate gate;DataSourceTransactionManager manager;
    @BeforeEach void setup()throws Exception{
        fixture=new JdbcSourceDayRunRegistrationsMySqlTest();fixture.setup();fixture.sql.execute("DROP TABLE IF EXISTS worker_lock");fixture.sql.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");fixture.sql.update("INSERT INTO worker_lock VALUES(1)");
        manager=new DataSourceTransactionManager(fixture.data);gate=mock(OperationalInputGate.class);
        when(gate.newRunAllowed("fictional-source")).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return true;});
    }
    SourceDayRunRegistrationService service(Clock clock){return new SourceDayRunRegistrationConfiguration().create(fixture.data,manager,gate,clock);}
    @Test void originalActualRunAndReceiptSurviveRestartWithoutCurrentSourceClockOrGate(){
        var original=service(Clock.fixed(fixture.reserved,ZoneOffset.UTC)).reserve(fixture.request);assertNotNull(original);fixture.sql.execute("DROP TABLE sources");
        reset(gate);Clock clock=mock(Clock.class);var restart=service(clock);assertEquals(original,restart.reserve(fixture.request));assertEquals(original,restart.lookup(fixture.request));verifyNoInteractions(gate,clock);
        assertEquals(1,fixture.sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));assertEquals(1,fixture.sql.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void gatedConfigurationCannotCreateSuccessfulOriginalOrTouchClock(){
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return false;}).when(gate).newRunAllowed("fictional-source");Clock clock=mock(Clock.class);assertNull(service(clock).reserve(fixture.request));verifyNoInteractions(clock);
        assertEquals(0,fixture.sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));assertEquals(0,fixture.sql.queryForObject("SELECT COUNT(*) FROM request_receipts",Integer.class));
    }
    @Test void actualMysqlCommitAckLossRecoversOriginalWithOneGateAndRun(){
        manager=new DataSourceTransactionManager(fixture.data){boolean lost;
            @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);if(!status.isReadOnly()&&!lost){lost=true;throw new TransactionSystemException("fictional acknowledgement lost",new SQLException("fictional transport","08006"));}}
        };
        var api=service(Clock.fixed(fixture.reserved,ZoneOffset.UTC));var original=api.reserve(fixture.request);assertNotNull(original);assertEquals(original,api.reserve(fixture.request));verify(gate,times(1)).newRunAllowed("fictional-source");assertEquals(1,fixture.sql.queryForObject("SELECT COUNT(*) FROM source_runs",Integer.class));
    }
}
