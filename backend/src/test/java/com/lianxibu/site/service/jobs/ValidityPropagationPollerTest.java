package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.JdbcValidityPropagation;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ValidityPropagationPollerTest {
    VersionValidityJdbcStoreTest f;AtomicInteger factories,keys;ValidityPropagationPoller poller;
    VersionValidityJdbcStoreTest fixture(){var value=new VersionValidityJdbcStoreTest();value.setup();return value;}
    @AfterEach void close() throws Exception {if(f instanceof AutoCloseable closeable)closeable.close();}
    @BeforeEach void setup(){f=fixture();factories=new AtomicInteger();keys=new AtomicInteger();
        poller=new ValidityPropagationPoller(new JdbcValidityPropagation(f.fixture.source),()->{factories.incrementAndGet();return new VersionValidityService(f.fixture.writes,new VersionValidityJdbcStore(f.base.ledger,new JdbcValidityPropagation(f.fixture.source),f.base.versions,f.base.contents,f.base.writer,f.fixture.clock,"fictional-owner",null));},()->"fictional-poll-"+keys.incrementAndGet());
    }
    @Test void idleDoesNotCreateEmptyReceiptsAndQueuedWorkFinishesDurably(){
        long before=f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);
        assertEquals(ValidityPropagationPoller.Outcome.IDLE,poller.poll());assertEquals(0,factories.get());assertEquals(before,f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));
        f.publish();f.invalidate(f.base.article(),0,"fictional-poll-invalid");
        int count=0;while(poller.poll()==ValidityPropagationPoller.Outcome.WORKED){if(++count>10)fail("queue did not finish");}
        assertTrue(count>0);assertEquals("withdrawn",f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().state());
        long after=f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);assertEquals(ValidityPropagationPoller.Outcome.IDLE,poller.poll());assertEquals(after,f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class));
    }
    @Test void failureRollsBackCurrentQualificationAndLeavesPendingWork(){
        f.publish();f.invalidate(f.base.article(),0,"fictional-poll-invalid");
        f.fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_poll_failure CHECK(target_type IS NULL OR target_type<>'validity_step')");
        assertThrows(RuntimeException.class,()->poller.poll());assertEquals("published",f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().state());
        assertTrue(f.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE task_type='validity_propagation' AND state='queued'",Integer.class)>0);
        f.fixture.jdbc.execute("ALTER TABLE jobs DROP CONSTRAINT fictional_poll_failure");assertEquals(ValidityPropagationPoller.Outcome.WORKED,poller.poll());
    }
    @Test void configuredLifecycleUsesPermanentQueuedWork(){
        f.publish();f.invalidate(f.base.article(),0,"fictional-configured-invalid");
        var tick=new java.util.concurrent.atomic.AtomicReference<Runnable>();var scheduler=mock(org.springframework.scheduling.TaskScheduler.class);var future=mock(java.util.concurrent.ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(java.time.Instant.class),any(java.time.Duration.class))).thenAnswer(call->{tick.set(call.getArgument(0));return future;});
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(com.lianxibu.site.config.ValidityPropagationConfiguration.class)
            .withBean(javax.sql.DataSource.class,()->f.fixture.source)
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->f.fixture.tx)
            .withBean("scheduleExecutor",org.springframework.scheduling.TaskScheduler.class,()->scheduler)
            .withPropertyValues("site.browser.enabled=true","site.content.enabled=true","site.content.propagation.enabled=true")
            .run(context->{assertNull(context.getStartupFailure());var loop=context.getBean(com.lianxibu.site.config.ValidityPropagationConfiguration.PropagationLoop.class);assertTrue(loop.isRunning());assertNotNull(tick.get());tick.get().run();assertFalse(loop.hasFailure());assertEquals("withdrawn",f.base.contents.reviewSnapshot("report","fictional-report").orElseThrow().state());});
        verify(future).cancel(false);
    }
}
