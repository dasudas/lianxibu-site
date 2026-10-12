package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
class ProviderCallNativeTest {
 TransportUnknownServiceTest fixture;ResultJdbcStoreTest f;
 @BeforeEach void setup(){fixture=new TransportUnknownServiceTest();fixture.setup();f=fixture.fixture;}
 @AfterEach void clear(){TransactionSynchronizationManager.clear();}
 JdbcProviderCallTelemetry reader(Clock clock){return new JdbcProviderCallTelemetry(f.source,f.tx,clock);}
 void unknown(){var rows=new JdbcWorkerRecovery(f.source,new JdbcWorkerState(f.source));var store=new UnknownCallJdbcStore(new JdbcWorkerState(f.source),rows,new JdbcJobRecords(f.source),"fictional-native-call-monitor",e->true);new UnknownCallRecoveryService(f.writes,store,f.clock).release(f.identity.attemptId(),UnknownCallRecoveryService.Cause.STOPPED_PROCESS,new SharedWriteExecutor.ActionContext("fictional-native-call-unknown",f.identity.attemptId(),null));}
 @Test void actualRunningIsZeroUnknownAndPermanentRecoveryHasExactAge(){var value=reader(f.clock).read();assertNotNull(value);assertEquals(0,value.unknownCalls());unknown();var before=new JdbcWorkerState(f.source).slot();var fact=reader(Clock.fixed(f.clock.instant().plusSeconds(10),ZoneOffset.UTC)).read();assertNotNull(fact);assertEquals(1,fact.unknownCalls());assertEquals(10,fact.oldestUnknownSeconds());assertEquals(0,fact.configurationBlockedRoots());assertEquals(before,new JdbcWorkerState(f.source).slot());assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());}
 @Test void actualConfigurationFailureIsCountedWithoutInventingAccountReadiness(){new AttemptFailureService(f.writes,new JdbcAttemptFailure(f.source),f.clock,"fictional-epoch").fail(f.identity,AttemptFailureService.Failure.CONFIGURATION,null,new SharedWriteExecutor.ActionContext("fictional-native-config-failure",f.identity.attemptId(),null));var value=reader(f.clock).read();assertNotNull(value);assertEquals(1,value.configurationBlockedRoots());assertEquals(0,value.unknownCalls());}
 @Test void missingOriginalClaimFutureUnknownAndActiveTransactionAreUnknown(){unknown();assertNull(reader(Clock.fixed(f.clock.instant().minusSeconds(1),ZoneOffset.UTC)).read());f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertNull(reader(f.clock).read());TransactionSynchronizationManager.setActualTransactionActive(true);assertNull(reader(f.clock).read());}
 @Test void absentNativeSchemaNeverReturnsDefaultZeros(){f.jdbc.execute("DROP TABLE jobs");assertNull(reader(f.clock).read());}
}
