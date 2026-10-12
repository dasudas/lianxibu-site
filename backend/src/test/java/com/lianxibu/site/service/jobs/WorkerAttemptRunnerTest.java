package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class WorkerAttemptRunnerTest {
    AttemptOutcomeServiceTest http;ResultJdbcStoreTest f;ScheduledExecutorService timer;WorkerAttemptRunner runner;
    @BeforeEach void setup() throws Exception {
        http=new AttemptOutcomeServiceTest();http.setup(false);f=http.fixture;timer=Executors.newScheduledThreadPool(2);
        runner=new WorkerAttemptRunner(f.worker,http.client,new WorkerLeaseService(f.writes,new JdbcWorkerLease(f.source),f.clock,"fictional-epoch"),
                new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),f.clock,"fictional-epoch"),http.service,timer,f.clock,Duration.ofMillis(100));
    }
    @AfterEach void stop(){http.stop();timer.shutdownNow();}
    @Test void originalPermitSendsAndAppliesExactlyOnce() {
        assertEquals(WorkerAttemptRunner.State.APPLIED,runner.run(f.permit));
        assertEquals(1,http.calls.get());assertEquals(1,f.handlerCalls);assertEquals("free",new JdbcWorkerState(f.source).slot().state());
        assertEquals(WorkerAttemptRunner.State.RECOVERY_REQUIRED,runner.run(f.permit));assertEquals(1,http.calls.get());
    }
    @Test void lostLeaseCancelsActualWaitAndCannotReleaseANewSlot() throws Exception {
        http.stall=true;var result=new AtomicReference<WorkerAttemptRunner.State>();Thread worker=new Thread(()->result.set(runner.run(f.permit)));
        worker.start();try {
            assertTrue(http.headers.await(3,TimeUnit.SECONDS));
            assertEquals(WorkerAttemptRunner.State.BUSY,runner.run(f.permit));
            f.jdbc.update("UPDATE worker_lock SET job_id='fictional-other',holder='fictional-other-epoch',slot_revision=slot_revision+1,fencing_token=fencing_token+1");var newer=new JdbcWorkerState(f.source).slot();
            worker.join(3000);assertFalse(worker.isAlive());assertEquals(WorkerAttemptRunner.State.UNKNOWN,result.get());
            assertEquals(newer,new JdbcWorkerState(f.source).slot());assertEquals("unknown_external",new JdbcJobRecords(f.source).byId(f.identity.rootId()).state());assertEquals(1,http.calls.get());
        } finally {http.finishBody.countDown();worker.interrupt();worker.join(3000);}
    }
    @Test void externalInterruptReleasesUnknownAndPreservesThreadInterrupt() throws Exception {
        http.stall=true;var result=new AtomicReference<WorkerAttemptRunner.State>();var interrupted=new AtomicBoolean();
        Thread worker=new Thread(()->{result.set(runner.run(f.permit));interrupted.set(Thread.currentThread().isInterrupted());});
        worker.start();try {
            assertTrue(http.headers.await(3,TimeUnit.SECONDS));worker.interrupt();worker.join(3000);
            assertFalse(worker.isAlive());assertEquals(WorkerAttemptRunner.State.UNKNOWN,result.get());assertTrue(interrupted.get());assertEquals("free",new JdbcWorkerState(f.source).slot().state());
            assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());
        } finally {http.finishBody.countDown();worker.interrupt();worker.join(3000);}
    }
    @Test void unavailableMonitorCancelsBeforeSendWithTheExistingRefundProof() {
        timer.shutdownNow();assertEquals(WorkerAttemptRunner.State.NOT_SENT,runner.run(f.permit));
        assertEquals(0,http.calls.get());assertEquals(0,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());
        assertEquals("free",new JdbcWorkerState(f.source).slot().state());
    }
    @Test void renewsDuringAnActualBodyWaitThenCancelsAtTheOriginalDeadline() throws Exception {
        http.stall=true;var current=new AtomicReference<>(f.clock.instant());
        Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return current.get();}};
        runner=new WorkerAttemptRunner(f.worker,http.client,new WorkerLeaseService(f.writes,new JdbcWorkerLease(f.source),moving,"fictional-epoch"),
                new TransportUnknownService(f.writes,new JdbcTransportUnknown(f.source),moving,"fictional-epoch"),http.service,timer,moving,Duration.ofMillis(100));
        var result=new AtomicReference<WorkerAttemptRunner.State>();Thread worker=new Thread(()->result.set(runner.run(f.permit)));
        worker.start();try {
            assertTrue(http.headers.await(3,TimeUnit.SECONDS));current.set(f.clock.instant().plusSeconds(20));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_heartbeat'",Integer.class)==0&&System.nanoTime()<until)Thread.sleep(20);
            assertTrue(f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='worker_heartbeat'",Integer.class)>0);
            assertTrue(new JdbcWorkerState(f.source).slot().lease().isAfter(f.identity.leaseUntil()));
            current.set(f.clock.instant().plusSeconds(90));worker.join(3000);
            assertFalse(worker.isAlive());assertEquals(WorkerAttemptRunner.State.UNKNOWN,result.get());assertEquals("free",new JdbcWorkerState(f.source).slot().state());
            assertEquals(1,http.calls.get());assertEquals(0,f.handlerCalls);
        } finally {http.finishBody.countDown();worker.interrupt();worker.join(3000);}
    }
}
