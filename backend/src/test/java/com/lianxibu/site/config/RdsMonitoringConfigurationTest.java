package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.RdsMonitoringCycle;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class RdsMonitoringConfigurationTest {
    @TempDir Path directory;
    static String input(){return "{\"account_id\":\"1212121212121212\",\"region_id\":\"cn-hangzhou\",\"instance_id\":\"rm-fictional\",\"access_key_id\":\"fictionalAccessId000001\",\"access_key_secret\":\"fictionalSecret000000000000000001\"}";}
    @Test void disabledAndIncompleteFlagsCreateNoProviderOrThreads() {
        for(String[] flags:new String[][]{new String[0],{"site.ops.rds.enabled=true"},{"site.ops.rds.enabled=true","site.browser.enabled=true"}})
            new ApplicationContextRunner().withUserConfiguration(RdsMonitoringConfiguration.class).withPropertyValues(flags).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(RdsMonitoringCycle.class).isEmpty());assertTrue(c.getBeansOfType(ScheduledExecutorService.class).isEmpty());});
    }
    @Test void privateFileIsClosedBoundedAbsoluteAndRedacted()throws Exception {
        Path file=directory.resolve("input.json");Files.writeString(file,input());var loaded=RdsMonitoringConfiguration.Input.load(file.toString());assertFalse(loaded.toString().contains("fictionalAccess"));
        assertThrows(IllegalStateException.class,()->RdsMonitoringConfiguration.Input.load("relative.json"));
        for(String value:new String[]{input().replace("cn-hangzhou","cn-other"),input().replace("1212121212121212","not-an-account"),input().replace("fictionalSecret000000000000000001","bad"),input().replace("{","{\"extra\":true,")," ".repeat(65537)}){
            Files.writeString(file,value);assertThrows(IllegalStateException.class,()->RdsMonitoringConfiguration.Input.load(file.toString()));
        }
    }
    @Test void completePrivateAssemblyMakesNoSqlOrNetworkOnConstructionAndRejectsWrongScope()throws Exception {
        Path input=directory.resolve("input.json"),scopes=directory.resolve("scopes.json");Files.writeString(input,input());Files.writeString(scopes,OperationalScopeCatalogTest.scopes().replace("fictional-resource","rm-fictional").replace("\"component\":\"monitor\"","\"component\":\"database\""));
        var db=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);var executor=mock(ScheduledExecutorService.class);when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(mock(ScheduledFuture.class));
        var context=new ApplicationContextRunner().withUserConfiguration(RdsMonitoringConfiguration.class).withBean(javax.sql.DataSource.class,()->db).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->tx).withBean("rdsMonitoringExecutor",ScheduledExecutorService.class,()->executor)
                .withPropertyValues("site.ops.rds.enabled=true","site.browser.enabled=true","site.collector.enabled=true","SITE_RDS_MONITOR_CONFIG="+input,"SITE_OPERATIONAL_SCOPES="+scopes);
        context.run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(RdsMonitoringCycle.class));verifyNoInteractions(db);});
        Files.writeString(scopes,OperationalScopeCatalogTest.scopes());context.run(c->{assertNotNull(c.getStartupFailure());verifyNoInteractions(db);});
    }
    @Test void loopRunsOnItsExecutorKeepsRetryingAndCancelsOnStop() {
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(RdsMonitoringCycle.class);var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));assertEquals(TimeUnit.SECONDS.toNanos(60),(long)a.getArgument(2));return future;});
        when(cycle.tick()).thenThrow(new IllegalStateException("fictional failure")).thenReturn(RdsMonitoringCycle.Outcome.SAMPLED);
        var loop=new RdsMonitoringConfiguration.Loop(cycle,executor,Clock.fixed(Instant.parse("2026-10-01T00:00:10Z"),ZoneOffset.UTC));loop.start();verify(cycle).start();verify(cycle,never()).tick();tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());loop.stop();tick.get().run();verify(cycle,times(2)).tick();verify(cycle).stop();verify(future).cancel(true);assertFalse(loop.isRunning());
    }
    @Test void shutdownCallbackWaitsForInFlightAndSchedulerFailureDoesNotPretendRunning()throws Exception {
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(RdsMonitoringCycle.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});
        when(cycle.tick()).thenAnswer(a->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return RdsMonitoringCycle.Outcome.STOPPED;});
        var loop=new RdsMonitoringConfiguration.Loop(cycle,executor,Clock.systemUTC());var runner=Executors.newSingleThreadExecutor();try{loop.start();runner.submit(()->tick.get().run());assertTrue(entered.await(1,TimeUnit.SECONDS));loop.stop(done::countDown);assertEquals(1,done.getCount());assertThrows(IllegalStateException.class,loop::start);release.countDown();assertTrue(done.await(1,TimeUnit.SECONDS));}finally{release.countDown();runner.shutdownNow();}
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new RejectedExecutionException());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
}
