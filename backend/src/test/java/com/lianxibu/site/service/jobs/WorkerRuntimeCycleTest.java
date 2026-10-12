package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class WorkerRuntimeCycleTest {
 WorkerRecoverySweep recovery=mock(WorkerRecoverySweep.class);
 WorkerAuthorizationService worker=mock(WorkerAuthorizationService.class);
 WorkerAttemptRunner runner=mock(WorkerAttemptRunner.class);
 WorkerRuntimeCycle cycle;
 @BeforeEach void setup(){when(recovery.scan()).thenReturn(new WorkerRecoverySweep.Result(0,0,0,0,false));when(worker.claim(any())).thenReturn(new WorkerAuthorizationService.Claim(WorkerAuthorizationService.State.IDLE,null,"outside_window",null));cycle=new WorkerRuntimeCycle(recovery,worker,runner,"fictional-epoch");}
 @Test void recoveryRunsBeforeClaimAndIdleNeverCallsRunner(){assertEquals(WorkerRuntimeCycle.Outcome.IDLE,cycle.run(()->true));var order=inOrder(recovery,worker);order.verify(recovery).scan();order.verify(worker).claim(any());verifyNoInteractions(runner);}
 @Test void failedRecoveryAndStopBlockNewClaim(){when(recovery.scan()).thenReturn(new WorkerRecoverySweep.Result(1,0,0,1,false));assertEquals(WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));verifyNoInteractions(worker,runner);when(recovery.scan()).thenReturn(new WorkerRecoverySweep.Result(0,0,0,0,false));assertEquals(WorkerRuntimeCycle.Outcome.IDLE,cycle.run(()->false));verifyNoInteractions(worker,runner);}
 @Test void stoppingAfterClaimCancelsButStillHandsPermitToRefundRunner(){var running=new AtomicBoolean(true);var permit=mock(WorkerAuthorizationService.Permit.class);when(worker.claim(any())).thenAnswer(a->{running.set(false);return new WorkerAuthorizationService.Claim(WorkerAuthorizationService.State.CLAIMED,permit,null,null);});when(runner.run(permit)).thenReturn(WorkerAttemptRunner.State.NOT_SENT);assertEquals(WorkerRuntimeCycle.Outcome.COMPLETED,cycle.run(running::get));var order=inOrder(permit,runner);order.verify(permit).cancelBeforeSend();order.verify(runner).run(permit);}
 @Test void storageFailureAndRunnerRecoveryRemainVisible(){when(worker.claim(any())).thenThrow(new IllegalStateException("fictional"));assertEquals(WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));reset(worker);var permit=mock(WorkerAuthorizationService.Permit.class);when(worker.claim(any())).thenReturn(new WorkerAuthorizationService.Claim(WorkerAuthorizationService.State.CLAIMED,permit,null,null));when(runner.run(permit)).thenReturn(WorkerAttemptRunner.State.RECOVERY_REQUIRED);assertEquals(WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED,cycle.run(()->true));}
 @Test void reentryDoesNotDuplicateClaim(){when(recovery.scan()).thenAnswer(a->{assertEquals(WorkerRuntimeCycle.Outcome.BUSY,cycle.run(()->true));return new WorkerRecoverySweep.Result(0,0,0,0,false);});assertEquals(WorkerRuntimeCycle.Outcome.IDLE,cycle.run(()->true));verify(worker,times(1)).claim(any());}
 @Test void observedActualRecoverySupplierCompletesBeforeClaim(){var observed=mock(WorkerRecoveryMonitor.class);when(observed.scan()).thenReturn(new WorkerRecoverySweep.Result(0,0,0,0,false));var actual=new WorkerRuntimeCycle(observed::scan,worker,runner,"fictional-observed-epoch");assertEquals(WorkerRuntimeCycle.Outcome.IDLE,actual.run(()->true));var order=inOrder(observed,worker);order.verify(observed).scan();order.verify(worker).claim(any());verifyNoInteractions(runner);}
 @Test void unavailableNativeRecoveryEvidenceBlocksClaimEvenWhenTheOldSweepWasHealthy(){var actual=new WorkerRuntimeCycle(()->{throw new IllegalStateException("fictional complete native scan unavailable");},worker,runner,"fictional-observed-epoch");assertEquals(WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED,actual.run(()->true));verifyNoInteractions(worker,runner);}
}
