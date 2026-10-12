package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourcePreparationRuntimeConfigurationTest {
    static final String[] FLAGS=NewsSourcePreparationConfigurationTest.FLAGS;
    @Test void DefaultAndEachMissingFlagCreateNoPreparationCycleOrThread(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);
        var context=new ApplicationContextRunner().withUserConfiguration(SourcePreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate);
        context.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceAnalysisPreparationCycle.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceAnalysisPreparationCycle.class).isEmpty());assertTrue(c.getBeansOfType(SourcePreparationRuntimeConfiguration.Loop.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,settings,gate);
    }
    @Test void EnabledActualAssemblyOnlySchedulesOnItsDedicatedExecutorAtStartup(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenReturn(future);
        new ApplicationContextRunner().withUserConfiguration(SourcePreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withBean("sourcePreparationExecutor",ScheduledExecutorService.class,()->executor).withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(SourceAnalysisPreparationCycle.class));assertNotNull(c.getBean(SourceAnalysisParentStepService.class));assertTrue(c.getBean(SourcePreparationRuntimeConfiguration.Loop.class).isRunning());verifyNoInteractions(source,tx,settings,gate);verify(executor).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS));});verify(future).cancel(false);
    }
    @Test void DedicatedExecutorHasOneThreadAndNoDeferredWorkAfterShutdown(){var executor=(ScheduledThreadPoolExecutor)new SourcePreparationRuntimeConfiguration().sourcePreparationExecutor();try{assertEquals(1,executor.getCorePoolSize());assertTrue(executor.getRemoveOnCancelPolicy());assertFalse(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy());assertFalse(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy());assertEquals(0,executor.getPoolSize());}finally{executor.shutdownNow();}}
    @Test void LoopRetriesFailuresAndCancellationPreventsLaterTicks(){
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(SourceAnalysisPreparationCycle.class);var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return future;});when(cycle.run(any())).thenThrow(new IllegalStateException("fictional failure")).thenReturn(SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED,SourceAnalysisPreparationCycle.Outcome.GATED,SourceAnalysisPreparationCycle.Outcome.FINALIZED);
        var loop=new SourcePreparationRuntimeConfiguration.Loop(cycle,executor);verifyNoInteractions(cycle,executor);loop.start();loop.start();verify(executor,times(1)).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS));verifyNoInteractions(cycle);tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());loop.stop();tick.get().run();assertFalse(loop.isRunning());verify(cycle,times(4)).run(any());verify(future).cancel(false);
    }
    @Test void StopWaitsForInFlightPreventsOverlapAndRejectsPrematureRestart()throws Exception{
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(SourceAnalysisPreparationCycle.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});when(cycle.run(any())).thenAnswer(a->{var running=a.getArgument(0,java.util.function.BooleanSupplier.class);assertTrue(running.getAsBoolean());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));assertFalse(running.getAsBoolean());return SourceAnalysisPreparationCycle.Outcome.IDLE;});
        var loop=new SourcePreparationRuntimeConfiguration.Loop(cycle,executor);try(var runner=Executors.newSingleThreadExecutor()){loop.start();var active=runner.submit(()->tick.get().run());try{assertTrue(entered.await(5,TimeUnit.SECONDS));tick.get().run();verify(cycle,times(1)).run(any());loop.stop(stopped::countDown);assertEquals(1,stopped.getCount());assertThrows(IllegalStateException.class,loop::start);}finally{release.countDown();}active.get(5,TimeUnit.SECONDS);assertTrue(stopped.await(1,TimeUnit.SECONDS));}tick.get().run();verify(cycle,times(1)).run(any());
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
}
