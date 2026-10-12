package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SourceDayRunRegistrationRuntimeConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final SourceDayRunCoordinatorService coordinator=mock(SourceDayRunCoordinatorService.class);
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(SourceDayRunRegistrationRuntimeConfiguration.class).withBean(SourceDayRunCoordinatorService.class,()->coordinator);}
    private String[] enabled(){return FLAGS.stream().map(f->f+"=true").toArray(String[]::new);}
    @Test void defaultAndEachMissingFlagCreateNoCycleOrThread() {
        context().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceDayRunRegistrationCycle.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});
        for(var omitted:FLAGS)context().withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceDayRunRegistrationCycle.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});verifyNoInteractions(coordinator);
    }
    @Test void allFlagsOnlyScheduleOneDedicatedLoopWithoutDatabaseWork() {
        var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS))).thenReturn(future);
        context().withBean("sourceDayRegistrationExecutor",ScheduledExecutorService.class,()->executor).withPropertyValues(enabled()).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayRunRegistrationCycle.class).size());assertTrue(c.getBean(SourceDayRunRegistrationRuntimeConfiguration.Loop.class).isRunning());verifyNoInteractions(coordinator);verify(executor).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS));});verify(future).cancel(false);
    }
    @Test void missingCoordinatorFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(SourceDayRunRegistrationRuntimeConfiguration.class).withPropertyValues(enabled()).run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void boundedBatchAndDelayAreRequiredAndHelperDoesNoWork() {
        var config=new SourceDayRunRegistrationRuntimeConfiguration();var clock=mock(Clock.class);assertNotNull(config.createCycle(coordinator,clock,64));verifyNoInteractions(coordinator,clock);
        for(String value:List.of("site.sources.day-roster.batch-size=0","site.sources.day-roster.batch-size=1001","site.sources.day-roster.period-seconds=4","site.sources.day-roster.period-seconds=301"))context().withPropertyValues(enabled()).withPropertyValues(value).run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void dedicatedExecutorHasOneThreadAndNoDeferredShutdownWork() {
        var executor=(ScheduledThreadPoolExecutor)new SourceDayRunRegistrationRuntimeConfiguration().sourceDayRegistrationExecutor();try{assertEquals(1,executor.getCorePoolSize());assertEquals(0,executor.getPoolSize());assertTrue(executor.getRemoveOnCancelPolicy());assertFalse(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy());assertFalse(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy());}finally{executor.shutdownNow();}
    }
    @Test void failuresRetryAndStopPreventsAnotherTick() {
        var executor=mock(ScheduledExecutorService.class);var future=mock(ScheduledFuture.class);var cycle=mock(SourceDayRunRegistrationCycle.class);var tick=new AtomicReference<Runnable>();
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS))).thenAnswer(call->{tick.set(call.getArgument(0));return future;});
        when(cycle.tick(any())).thenThrow(new IllegalStateException("fictional failed original seal")).thenReturn(new SourceDayRunRegistrationCycle.Outcome(SourceDayRunRegistrationCycle.Status.PROGRESSED,LocalDate.of(2026,9,20),1,1,0,1,0,0),new SourceDayRunRegistrationCycle.Outcome(SourceDayRunRegistrationCycle.Status.PROGRESSED,LocalDate.of(2026,9,20),1,1,1,0,0,0));
        var loop=new SourceDayRunRegistrationRuntimeConfiguration.Loop(cycle,executor,30);verifyNoInteractions(cycle,executor);loop.start();loop.start();verify(executor,times(1)).scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS));verifyNoInteractions(cycle);
        tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());loop.stop();tick.get().run();verify(cycle,times(3)).tick(any());verify(future).cancel(false);
    }
    @Test void stopWaitsForInFlightAndRejectsEarlyRestartOrSchedulingFailure()throws Exception {
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(SourceDayRunRegistrationCycle.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS))).thenAnswer(call->{tick.set(call.getArgument(0));return mock(ScheduledFuture.class);});
        when(cycle.tick(any())).thenAnswer(call->{var running=call.getArgument(0,java.util.function.BooleanSupplier.class);assertTrue(running.getAsBoolean());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));assertFalse(running.getAsBoolean());return new SourceDayRunRegistrationCycle.Outcome(SourceDayRunRegistrationCycle.Status.SKIPPED,null,0,0,0,0,0,0);});
        var loop=new SourceDayRunRegistrationRuntimeConfiguration.Loop(cycle,executor,30);
        try(var runner=Executors.newSingleThreadExecutor()){loop.start();var active=runner.submit(()->tick.get().run());try{assertTrue(entered.await(5,TimeUnit.SECONDS));tick.get().run();verify(cycle,times(1)).tick(any());loop.stop(stopped::countDown);assertEquals(1,stopped.getCount());assertThrows(IllegalStateException.class,loop::start);}finally{release.countDown();}active.get(5,TimeUnit.SECONDS);assertTrue(stopped.await(1,TimeUnit.SECONDS));}
        tick.get().run();verify(cycle,times(1)).tick(any());when(executor.scheduleWithFixedDelay(any(Runnable.class),eq(1L),eq(30L),eq(TimeUnit.SECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
}
