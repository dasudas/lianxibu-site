package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.VersionAvailabilityPoller;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class VersionAvailabilityConfigurationTest {
    @Test void disabledConfigurationDoesNotRequireDatabaseOrScheduler(){new ApplicationContextRunner().withUserConfiguration(VersionAvailabilityConfiguration.class).run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(VersionAvailabilityPoller.class).isEmpty());});}
    @Test void schedulingFailureDoesNotPretendRunning(){var scheduler=mock(TaskScheduler.class);when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenThrow(new RejectedExecutionException());var loop=new VersionAvailabilityConfiguration.ObservationLoop(mock(VersionAvailabilityPoller.class),scheduler,Clock.systemUTC());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());}
    @Test void failureCanRecoverWithoutEndingSchedule(){
        var tick=new AtomicReference<Runnable>();var poller=mock(VersionAvailabilityPoller.class);var scheduler=mock(TaskScheduler.class);var future=mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenAnswer(call->{tick.set(call.getArgument(0));return future;});
        when(poller.poll()).thenReturn(new VersionAvailabilityPoller.Outcome(2,1,1)).thenReturn(new VersionAvailabilityPoller.Outcome(0,0,0));
        var loop=new VersionAvailabilityConfiguration.ObservationLoop(poller,scheduler,Clock.systemUTC());loop.start();tick.get().run();assertTrue(loop.hasFailure());assertTrue(loop.isRunning());tick.get().run();assertFalse(loop.hasFailure());loop.stop();tick.get().run();verify(poller,times(2)).poll();verify(future).cancel(false);
    }
    @Test void stopCallbackWaitsForInFlightStepAndPreventsReentry() throws Exception {
        var tick=new AtomicReference<Runnable>();var poller=mock(VersionAvailabilityPoller.class);var scheduler=mock(TaskScheduler.class);var future=mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenAnswer(call->{tick.set(call.getArgument(0));return future;});
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var stopped=new CountDownLatch(1);
        when(poller.poll()).thenAnswer(call->{entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return new VersionAvailabilityPoller.Outcome(1,1,0);});
        var loop=new VersionAvailabilityConfiguration.ObservationLoop(poller,scheduler,Clock.systemUTC());loop.start();var runner=new Thread(()->tick.get().run());runner.start();
        try{assertTrue(entered.await(3,TimeUnit.SECONDS));tick.get().run();loop.stop(stopped::countDown);assertEquals(1,stopped.getCount());assertFalse(loop.isRunning());assertThrows(IllegalStateException.class,loop::start);finish.countDown();assertTrue(stopped.await(3,TimeUnit.SECONDS));tick.get().run();verify(poller,times(1)).poll();}
        finally{finish.countDown();runner.join(3000);loop.stop();}
    }
}
