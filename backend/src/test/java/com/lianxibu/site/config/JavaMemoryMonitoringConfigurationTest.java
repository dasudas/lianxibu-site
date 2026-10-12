package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.JavaMemoryMonitoringCycle;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JavaMemoryMonitoringConfigurationTest {
    @TempDir Path directory;
    static String input() { return "{\"initial_heap_bytes\":1,\"maximum_heap_bytes\":2,\"maximum_metaspace_bytes\":1,\"maximum_direct_bytes\":1,\"reserved_code_cache_bytes\":1,\"native_allowance_bytes\":1,\"hard_limit_bytes\":8,\"exit_on_out_of_memory_error\":true,\"oom_hook_sha256\":null}"; }

    @Test void DisabledOrIncompleteFlagsCreateNoProvidersThreadsOrPrivateReads() {
        for(String[] flags:new String[][] {new String[0],{"site.ops.java-memory.enabled=true"},{"site.ops.java-memory.enabled=true","site.browser.enabled=true"},
                {"site.browser.enabled=true","site.collector.enabled=true"},{"site.ops.java-memory.enabled=true","site.collector.enabled=true"}})
            new ApplicationContextRunner().withUserConfiguration(JavaMemoryMonitoringConfiguration.class).withPropertyValues(flags).run(context->{
                assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(JavaMemoryMonitoringCycle.class).isEmpty());assertTrue(context.getBeansOfType(ScheduledExecutorService.class).isEmpty());
            });
    }

    @Test void PrivateBudgetIsAbsoluteStrictBoundedAndDiagnosticRedacted() throws Exception {
        Path file=directory.resolve("budget.json");Files.writeString(file,input());var loaded=JavaMemoryMonitoringConfiguration.Input.load(file.toString());
        assertEquals(8,loaded.budget().hardLimitBytes());assertEquals("JavaMemoryMonitoringInput[redacted]",loaded.toString());
        assertThrows(IllegalStateException.class,()->JavaMemoryMonitoringConfiguration.Input.load("relative.json"));
        for(String value:new String[]{input().replace("{","{\"extra\":true,"),input().replace("\"initial_heap_bytes\":1,",""),input().replace("\"maximum_heap_bytes\":2","\"maximum_heap_bytes\":0"),
                input().replace("\"maximum_heap_bytes\":2","\"maximum_heap_bytes\":2.1"),input().replace("\"hard_limit_bytes\":8","\"hard_limit_bytes\":5"),
                input().replace("\"hard_limit_bytes\":8","\"hard_limit_bytes\":9007199254740992"),input().replace("true","\"true\""),input().replace("null","\"not-a-hash\""),
                input().replace("\"initial_heap_bytes\":1","\"initial_heap_bytes\":1,\"initial_heap_bytes\":1"),input()+"{}"," ".repeat(65537),"{bad JSON"}) {
            Files.writeString(file,value);var failure=assertThrows(IllegalStateException.class,()->JavaMemoryMonitoringConfiguration.Input.load(file.toString()));
            assertEquals("private Java memory budget unavailable",failure.getMessage());assertNull(failure.getCause());
        }
        Files.delete(file);assertThrows(IllegalStateException.class,()->JavaMemoryMonitoringConfiguration.Input.load(file.toString()));
        assertThrows(IllegalStateException.class,()->JavaMemoryMonitoringConfiguration.Input.load(directory.toString()));
    }

    @Test void CompleteAssemblyIsLazyAndWrongJavaResourceRefusedBeforeSql() throws Exception {
        Path input=directory.resolve("budget.json"),scopes=directory.resolve("scopes.json");Files.writeString(input,input());Files.writeString(scopes,OperationalScopeCatalogTest.scopes());
        var db=mock(javax.sql.DataSource.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);var executor=mock(ScheduledExecutorService.class);
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(mock(ScheduledFuture.class));
        var context=new ApplicationContextRunner().withUserConfiguration(JavaMemoryMonitoringConfiguration.class).withBean(javax.sql.DataSource.class,()->db)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->tx).withBean("javaMemoryMonitoringExecutor",ScheduledExecutorService.class,()->executor)
                .withPropertyValues("site.ops.java-memory.enabled=true","site.browser.enabled=true","site.collector.enabled=true","SITE_JAVA_MEMORY_BUDGET_CONFIG="+input,"SITE_OPERATIONAL_SCOPES="+scopes);
        context.run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(JavaMemoryMonitoringCycle.class));verifyNoInteractions(db,tx);});
        Files.writeString(scopes,OperationalScopeCatalogTest.scopes().replace("\"component\":\"java\"","\"component\":\"collector\""));
        context.run(c->{assertNotNull(c.getStartupFailure());verifyNoInteractions(db,tx);});
    }

    @Test void MinuteLoopRetriesFixedFailuresAndCancelsWithoutRestartingStoppedCycle() {
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(JavaMemoryMonitoringCycle.class);var tick=new AtomicReference<Runnable>();var future=mock(ScheduledFuture.class);
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(call->{tick.set(call.getArgument(0));
            assertEquals(TimeUnit.SECONDS.toNanos(50),(long)call.getArgument(1));assertEquals(TimeUnit.SECONDS.toNanos(60),(long)call.getArgument(2));return future;});
        when(cycle.tick()).thenThrow(new IllegalStateException("fictional private error")).thenReturn(JavaMemoryMonitoringCycle.Outcome.SAMPLED);
        var loop=new JavaMemoryMonitoringConfiguration.Loop(cycle,executor,Clock.fixed(Instant.parse("2026-10-01T00:00:10Z"),ZoneOffset.UTC));
        loop.start();loop.start();verify(cycle,times(1)).start();verify(cycle,never()).tick();tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());
        loop.stop();tick.get().run();verify(cycle,times(2)).tick();verify(cycle).stop();verify(future).cancel(true);assertFalse(loop.isRunning());
    }

    @Test void StopCallbackWaitsForOwnedInFlightAndRejectedScheduleIsNotRunning() throws Exception {
        var executor=mock(ScheduledExecutorService.class);var cycle=mock(JavaMemoryMonitoringCycle.class);var tick=new AtomicReference<Runnable>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenAnswer(call->{tick.set(call.getArgument(0));return mock(ScheduledFuture.class);});
        when(cycle.tick()).thenAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return JavaMemoryMonitoringCycle.Outcome.STOPPED;});
        var loop=new JavaMemoryMonitoringConfiguration.Loop(cycle,executor,Clock.systemUTC());var runner=Executors.newSingleThreadExecutor();
        try{loop.start();var work=runner.submit(()->tick.get().run());assertTrue(entered.await(1,TimeUnit.SECONDS));loop.stop(done::countDown);
            assertEquals(1,done.getCount());assertThrows(IllegalStateException.class,loop::start);release.countDown();assertTrue(done.await(1,TimeUnit.SECONDS));work.get(1,TimeUnit.SECONDS);}
        finally{release.countDown();runner.shutdownNow();}
        when(executor.scheduleAtFixedRate(any(Runnable.class),anyLong(),anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new RejectedExecutionException());
        assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());
    }
}
