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

class AdHocPreparationRuntimeConfigurationTest {
    static final String[] FLAGS=new String[]{"site.browser.enabled","site.content.enabled","site.execution.enabled","site.execution.ad-hoc.enabled"};
    @Test void DefaultAndEachMissingFlagCreateNoPreparationCycleOrThread(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);
        var context=new ApplicationContextRunner().withUserConfiguration(AdHocPreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate);
        context.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(AdHocPreparationCycle.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(AdHocPreparationCycle.class).isEmpty());assertTrue(c.getBeansOfType(AdHocPreparationRuntimeConfiguration.Loop.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,settings,gate);
    }
    @Test void EnabledActualAssemblyOnlySchedulesOnItsDedicatedExecutorAtStartup(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenReturn(future);
        new ApplicationContextRunner().withUserConfiguration(AdHocPreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withBean("adHocPreparationExecutor",ScheduledExecutorService.class,()->executor).withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(AdHocPreparationCycle.class));assertNotNull(c.getBean(OwnerAdHocCreateService.class));assertTrue(c.getBean(AdHocPreparationRuntimeConfiguration.Loop.class).isRunning());verifyNoInteractions(source,tx,settings,gate);verify(executor).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS));});verify(future).cancel(false);
    }
    @Test void DedicatedExecutorHasOneThreadAndNoDeferredWorkAfterShutdown(){var executor=(ScheduledThreadPoolExecutor)new AdHocPreparationRuntimeConfiguration().adHocPreparationExecutor();try{assertEquals(1,executor.getCorePoolSize());assertTrue(executor.getRemoveOnCancelPolicy());assertFalse(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy());assertFalse(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy());assertEquals(0,executor.getPoolSize());}finally{executor.shutdownNow();}}
    @Test void LoopRetriesFailuresAndCancellationPreventsLaterTicks(){
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(AdHocPreparationCycle.class);var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return future;});when(cycle.run(any())).thenThrow(new IllegalStateException("fictional failure")).thenReturn(AdHocPreparationCycle.Outcome.RECOVERY_REQUIRED,AdHocPreparationCycle.Outcome.GATED,AdHocPreparationCycle.Outcome.PREPARED);
        var loop=new AdHocPreparationRuntimeConfiguration.Loop(cycle,executor);verifyNoInteractions(cycle,executor);loop.start();loop.start();verify(executor,times(1)).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS));verifyNoInteractions(cycle);tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());loop.stop();tick.get().run();assertFalse(loop.isRunning());verify(cycle,times(4)).run(any());verify(future).cancel(false);
    }
    @Test void StopWaitsForInFlightPreventsOverlapAndRejectsPrematureRestart()throws Exception{
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(AdHocPreparationCycle.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});when(cycle.run(any())).thenAnswer(a->{var running=a.getArgument(0,java.util.function.BooleanSupplier.class);assertTrue(running.getAsBoolean());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));assertFalse(running.getAsBoolean());return AdHocPreparationCycle.Outcome.IDLE;});
        var loop=new AdHocPreparationRuntimeConfiguration.Loop(cycle,executor);try(var runner=Executors.newSingleThreadExecutor()){loop.start();var active=runner.submit(()->tick.get().run());try{assertTrue(entered.await(5,TimeUnit.SECONDS));tick.get().run();verify(cycle,times(1)).run(any());loop.stop(stopped::countDown);assertEquals(1,stopped.getCount());assertThrows(IllegalStateException.class,loop::start);}finally{release.countDown();}active.get(5,TimeUnit.SECONDS);assertTrue(stopped.await(1,TimeUnit.SECONDS));}tick.get().run();verify(cycle,times(1)).run(any());
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
    @Test void EnabledAssemblyRequiresActualMappedSettingsAndAdmission(){
        var base=new ApplicationContextRunner().withUserConfiguration(AdHocPreparationRuntimeConfiguration.class).withBean(DataSource.class,()->mock(DataSource.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new));
        base.withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).run(c->assertNotNull(c.getStartupFailure()));base.withBean(OperationalInputGate.class,()->mock(OperationalInputGate.class)).run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void EnabledJobsCannotSilentlySubstituteTheMissingActualCreation(){
        var source=mock(DataSource.class);var settings=mock(com.lianxibu.site.service.settings.SettingsJdbcStore.class);
        var base=new ApplicationContextRunner().withUserConfiguration(JobsRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).withBean(com.lianxibu.site.service.settings.SettingsJdbcStore.class,()->settings).withPropertyValues(Arrays.stream(FLAGS).map(f->f+"=true").toArray(String[]::new));base.run(c->assertNotNull(c.getStartupFailure()));
        var creation=mock(OwnerAdHocCreateService.class);base.withBean(OwnerAdHocCreateService.class,()->creation).run(c->{assertNull(c.getStartupFailure());assertInstanceOf(OwnerJobCommandsService.class,c.getBean(OwnerJobsService.Commands.class));});verifyNoInteractions(source,settings,creation);
    }
}
