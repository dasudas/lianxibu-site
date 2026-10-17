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

class CatalogSourcePreparationRuntimeConfigurationTest {
    static final String[] FLAGS={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled"};
    @Test void DefaultAndEachMissingFlagCreateNoCatalogThreadOrLoop(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);
        var context=new ApplicationContextRunner().withUserConfiguration(CatalogSourcePreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate);
        context.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());assertTrue(c.getBeansOfType(CatalogSourcePreparationRuntimeConfiguration.Loop.class).isEmpty());});
        for(String absent:FLAGS)context.withPropertyValues(Arrays.stream(FLAGS).map(n->n+"="+!n.equals(absent)).toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(CatalogSourcePreparationRuntimeConfiguration.Loop.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(source,tx,settings,gate);
    }
    @Test void EnabledActualAssemblySchedulesOnlyTheDedicatedExecutorAndKeepsNewsBeansSeparate(){
        var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenReturn(future);
        new ApplicationContextRunner().withUserConfiguration(CatalogSourcePreparationRuntimeConfiguration.class,SourcePreparationRuntimeConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate).withBean("catalogSourcePreparationExecutor",ScheduledExecutorService.class,()->executor).withBean("sourcePreparationExecutor",ScheduledExecutorService.class,()->executor).withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(CatalogSourceProofConfiguration.Graphs.class));assertTrue(c.getBean(CatalogSourcePreparationRuntimeConfiguration.Loop.class).isRunning());assertNotNull(c.getBean(SourceAnalysisPreparationCycle.class));assertNotNull(c.getBean(SourceAnalysisParentStepService.class));assertNotNull(c.getBean(SourceAnalysisProofGraph.class));verifyNoInteractions(source,tx,settings,gate);verify(executor,times(2)).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS));});verify(future,times(2)).cancel(false);
    }
    @Test void ExecutorHasOneLazyThreadAndNoDeferredWorkAfterShutdown(){var executor=(ScheduledThreadPoolExecutor)new CatalogSourcePreparationRuntimeConfiguration().catalogSourcePreparationExecutor();try{assertEquals(1,executor.getCorePoolSize());assertTrue(executor.getRemoveOnCancelPolicy());assertFalse(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy());assertFalse(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy());assertEquals(0,executor.getPoolSize());}finally{executor.shutdownNow();}}
    List<CatalogSourcePreparationCycle> cycles(){return List.of(mock(CatalogSourcePreparationCycle.class),mock(CatalogSourcePreparationCycle.class),mock(CatalogSourcePreparationCycle.class),mock(CatalogSourcePreparationCycle.class));}
    @Test void FourCategoryRotationContinuesAfterFailureBusyGateAndRestartsWithoutNewTicksWhileStopped(){
        var executor=mock(ScheduledExecutorService.class);var cycles=cycles();var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return future;});when(cycles.get(0).run(any())).thenThrow(new IllegalStateException("fictional unavailable")).thenReturn(CatalogSourcePreparationCycle.Outcome.FINALIZED);when(cycles.get(1).run(any())).thenReturn(CatalogSourcePreparationCycle.Outcome.BUSY);when(cycles.get(2).run(any())).thenReturn(CatalogSourcePreparationCycle.Outcome.GATED);when(cycles.get(3).run(any())).thenReturn(CatalogSourcePreparationCycle.Outcome.IDLE);
        var loop=new CatalogSourcePreparationRuntimeConfiguration.Loop(cycles,executor);verifyNoInteractions(executor);loop.start();loop.start();tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());tick.get().run();tick.get().run();assertFalse(loop.hasFailure());var order=inOrder(cycles.toArray());order.verify(cycles.get(0)).run(any());order.verify(cycles.get(1)).run(any());order.verify(cycles.get(2)).run(any());order.verify(cycles.get(3)).run(any());order.verify(cycles.get(0)).run(any());loop.stop();tick.get().run();verify(cycles.get(1),times(1)).run(any());verify(future).cancel(false);loop.start();tick.get().run();verify(cycles.get(1),times(2)).run(any());loop.stop();
    }
    @Test void RealConcurrentStopWaitsOnlyForOneInflightStepAndPreventsOverlapAndPrematureRestart()throws Exception{
        var executor=mock(ScheduledExecutorService.class);var cycles=cycles();var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});when(cycles.get(0).run(any())).thenAnswer(a->{var running=a.getArgument(0,java.util.function.BooleanSupplier.class);assertTrue(running.getAsBoolean());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));assertFalse(running.getAsBoolean());return CatalogSourcePreparationCycle.Outcome.IDLE;});
        var loop=new CatalogSourcePreparationRuntimeConfiguration.Loop(cycles,executor);try(var runner=Executors.newSingleThreadExecutor()){loop.start();var active=runner.submit(()->tick.get().run());try{assertTrue(entered.await(5,TimeUnit.SECONDS));tick.get().run();verify(cycles.get(0),times(1)).run(any());loop.stop(stopped::countDown);assertEquals(1,stopped.getCount());assertThrows(IllegalStateException.class,loop::start);}finally{release.countDown();}active.get(5,TimeUnit.SECONDS);assertTrue(stopped.await(1,TimeUnit.SECONDS));}tick.get().run();verifyNoInteractions(cycles.get(1),cycles.get(2),cycles.get(3));when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(1L),eq(TimeUnit.SECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
    @Test void ConstructorRejectsIncompleteNullOrReusedCyclesWithoutScheduling(){var executor=mock(ScheduledExecutorService.class);var cycles=cycles();assertThrows(RuntimeException.class,()->new CatalogSourcePreparationRuntimeConfiguration.Loop(cycles.subList(0,3),executor));assertThrows(RuntimeException.class,()->new CatalogSourcePreparationRuntimeConfiguration.Loop(List.of(cycles.get(0),cycles.get(0),cycles.get(2),cycles.get(3)),executor));assertThrows(RuntimeException.class,()->new CatalogSourcePreparationRuntimeConfiguration.Loop(Arrays.asList(cycles.get(0),null,cycles.get(2),cycles.get(3)),executor));assertThrows(RuntimeException.class,()->new CatalogSourcePreparationRuntimeConfiguration.Loop(cycles,null));verifyNoInteractions(executor);}
    ApplicationContextRunner intervalContext(String seconds,ScheduledExecutorService executor,DataSource source,PlatformTransactionManager tx,SettingsUpdateService settings,OperationalInputGate gate){
        return new ApplicationContextRunner().withUserConfiguration(CatalogSourcePreparationRuntimeConfiguration.class)
            .withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->tx).withBean(SettingsUpdateService.class,()->settings).withBean(OperationalInputGate.class,()->gate)
            .withBean("catalogSourcePreparationExecutor",ScheduledExecutorService.class,()->executor)
            .withPropertyValues(Arrays.stream(FLAGS).map(n->n+"=true").toArray(String[]::new))
            .withPropertyValues("site.execution.catalog-source-runtime.interval-seconds="+seconds);
    }
    @Test void ConfiguredIntervalSchedulesTheRealAssemblyWithoutDatabaseOrPaidWork(){
        for(long seconds:new long[]{10,300}){
            var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);
            when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(seconds),eq(seconds),eq(TimeUnit.SECONDS))).thenReturn(future);
            intervalContext(Long.toString(seconds),executor,source,tx,settings,gate).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBean(CatalogSourcePreparationRuntimeConfiguration.Loop.class).isRunning());verifyNoInteractions(source,tx,settings,gate);});
            verify(executor).scheduleWithFixedDelay(any(Runnable.class),eq(seconds),eq(seconds),eq(TimeUnit.SECONDS));verify(future).cancel(false);
        }
    }
    @Test void InvalidIntervalFailsClosedBeforeSchedulingOrAnyNativeWork(){
        for(String seconds:List.of("0","301","-1","fictional-invalid")){
            var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var gate=mock(OperationalInputGate.class);var executor=mock(ScheduledExecutorService.class);
            when(executor.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.SECONDS))).thenReturn(mock(ScheduledFuture.class));
            intervalContext(seconds,executor,source,tx,settings,gate).run(c->assertNotNull(c.getStartupFailure()));verifyNoInteractions(source,tx,settings,gate);verify(executor,never()).scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any());
        }
    }

}
