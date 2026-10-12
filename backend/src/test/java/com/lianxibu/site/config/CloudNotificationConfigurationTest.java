package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.OperationalNoticeDelivery;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class CloudNotificationConfigurationTest {
    @TempDir Path directory;
    static String input() {
        String claims="{\"aliUid\":\"1212121212121212\",\"name\":\"fictional-ops\",\"secure\":\"11111111-1111-4111-8111-111111111111\",\"tokenType\":\"contactGroup\"}";
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))+".fictionalSignature";
        return "{\"account_id\":\"1212121212121212\",\"contact_group\":\"fictional-ops\",\"token\":\""+token+"\"}";
    }
    String[] properties(Path input) {
        return new String[]{"site.ops.cloud.enabled=true","SITE_OPS_CLOUD_CONFIG="+input,"SITE_OPS_LOG_DIRECTORY="+directory.resolve("log"),
                "SITE_OPS_LOG_SEGMENT_BYTES=4096","SITE_OPS_LOG_TOTAL_BYTES=8192","SITE_OPS_LOG_RETENTION=PT168H",
                "SITE_OPS_CLOUD_LEASE=PT60S","SITE_OPS_CLOUD_RETRY=PT10S","SITE_OPS_CLOUD_INTERVAL=PT1S","SITE_OPS_CLOUD_BATCH=2"};
    }
    @Test void defaultDisabledAndMissingPrivateConfigurationFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(CloudNotificationConfiguration.class).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(OperationalNoticeDelivery.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});
        new ApplicationContextRunner().withUserConfiguration(CloudNotificationConfiguration.class).withPropertyValues("site.ops.cloud.enabled=true").run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void privateFileIsClosedBoundedAbsoluteIdentityCheckedAndRedacted()throws Exception {
        Path file=directory.resolve("input.json");Files.writeString(file,input());var loaded=CloudNotificationConfiguration.Input.load(file.toString());assertFalse(loaded.toString().contains("fictionalSignature"));
        assertThrows(IllegalStateException.class,()->CloudNotificationConfiguration.Input.load("relative.json"));
        for(String value:List.of(input().replace("{","{\"extra\":true,"),input().replace("fictional-ops","wrong-group"),input().replace("1212121212121212","not-account")," ".repeat(65537))) {
            Files.writeString(file,value);var error=assertThrows(IllegalStateException.class,()->CloudNotificationConfiguration.Input.load(file.toString()));assertNull(error.getCause());assertFalse(error.getMessage().contains("fictionalSignature"));
        }
    }
    @Test void localAndCloudAssemblyHaveDistinctConsumersAndExecutorsWithNoStartupSql()throws Exception {
        Path input=directory.resolve("input.json");Files.writeString(input,input());var db=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var cloud=mock(ScheduledExecutorService.class);when(cloud.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(mock(ScheduledFuture.class));
        var scheduler=mock(TaskScheduler.class);when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(java.time.Instant.class),any(Duration.class))).thenReturn(mock(ScheduledFuture.class));
        var runner=new ApplicationContextRunner().withUserConfiguration(CloudNotificationConfiguration.class,OperationalDeliveryConfiguration.class).withBean(javax.sql.DataSource.class,()->db).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->tx)
                .withBean("cloudNotificationExecutor",ScheduledExecutorService.class,()->cloud).withBean("scheduleExecutor",TaskScheduler.class,()->scheduler).withPropertyValues(properties(input))
                .withPropertyValues("site.ops.delivery.enabled=true","SITE_OPS_DELIVERY_LEASE=PT60S","SITE_OPS_DELIVERY_RETRY=PT10S","SITE_OPS_DELIVERY_INTERVAL=PT1S","SITE_OPS_DELIVERY_BATCH=2");
        runner.run(c->{assertNull(c.getStartupFailure());assertEquals(2,c.getBeansOfType(OperationalNoticeDelivery.class).size());assertNotSame(c.getBean("cloudNoticeDelivery"),c.getBean("operationalNoticeDelivery"));verifyNoInteractions(db);});
        runner.withPropertyValues("SITE_OPS_CLOUD_LEASE=PT8S").run(c->assertNotNull(c.getStartupFailure()));
        runner.withPropertyValues("SITE_OPS_CLOUD_BATCH=5").run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void boundedBatchRetriesAndQueuedTickCannotRunAfterStop() {
        var executor=mock(ScheduledExecutorService.class);var delivery=mock(OperationalNoticeDelivery.class);var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return future;});
        when(delivery.deliverNext()).thenReturn(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED);
        var loop=new CloudNotificationConfiguration.Loop(delivery,executor,Duration.ofSeconds(1),2);loop.start();verify(delivery).start();tick.get().run();verify(delivery,times(2)).deliverNext();
        when(delivery.deliverNext()).thenThrow(new IllegalStateException("fictional private error"));tick.get().run();assertTrue(loop.hasFailure());
        loop.stop();tick.get().run();verify(delivery,times(3)).deliverNext();verify(delivery).stop();verify(future).cancel(true);assertFalse(loop.isRunning());
        assertThrows(IllegalArgumentException.class,()->new CloudNotificationConfiguration.Loop(delivery,executor,Duration.ZERO,1));
    }
    @Test void realDedicatedThreadInterruptsInFlightAndShutdownCallbackWaits()throws Exception {
        var delivery=mock(OperationalNoticeDelivery.class);var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var completed=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(delivery.deliverNext()).thenAnswer(a->{assertEquals("cloud-notify",Thread.currentThread().getName());entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException expected){interrupted.countDown();Thread.currentThread().interrupt();}return OperationalNoticeDelivery.Outcome.RETRY_PENDING;});
        var executor=new CloudNotificationConfiguration().cloudNotificationExecutor();
        try{var loop=new CloudNotificationConfiguration.Loop(delivery,executor,Duration.ofSeconds(1),4);loop.start();assertTrue(entered.await(3,TimeUnit.SECONDS));loop.stop(completed::countDown);assertTrue(interrupted.await(1,TimeUnit.SECONDS));assertTrue(completed.await(1,TimeUnit.SECONDS));verify(delivery,times(1)).deliverNext();}
        finally{release.countDown();executor.shutdownNow();}
    }
    @Test void stoppingBlocksRestartAndRejectedScheduleLeavesConsumerStopped()throws Exception {
        var delivery=mock(OperationalNoticeDelivery.class);var executor=mock(ScheduledExecutorService.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(executor.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});
        when(delivery.deliverNext()).thenAnswer(a->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return OperationalNoticeDelivery.Outcome.IDLE;});
        var loop=new CloudNotificationConfiguration.Loop(delivery,executor,Duration.ofSeconds(1),1);var runner=Executors.newSingleThreadExecutor();
        try{loop.start();runner.submit(()->tick.get().run());assertTrue(entered.await(1,TimeUnit.SECONDS));loop.stop(done::countDown);assertEquals(1,done.getCount());assertThrows(IllegalStateException.class,loop::start);release.countDown();assertTrue(done.await(1,TimeUnit.SECONDS));}
        finally{release.countDown();runner.shutdownNow();}
        when(executor.scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
}
