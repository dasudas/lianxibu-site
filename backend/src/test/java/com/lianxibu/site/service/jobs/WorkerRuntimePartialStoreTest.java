package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.lianxibu.site.config.WorkerRuntimeConfiguration;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Configuration consumer only, actual original partial proof and fictional sender. */
class WorkerRuntimePartialStoreTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @Test void ActualConfiguredStoreReservesAndFreshlyAuthorizesOne201MemberPartialAttempt()throws Exception{
        var f=new SourcePartialDispatchReadinessTest(){@Override GenerationJdbcStoreTest database(){return WorkerRuntimePartialStoreTest.this.database();}};f.setup();WorkerCandidateSelectionScopeTest.installWorker(f.db);
        var settings=mock(SettingsUpdateService.class);when(settings.newAttemptAllowed()).thenReturn(true);var readiness=spy(f.service);
        var store=WorkerRuntimeConfiguration.workerStore(f.db.source,new DataSourceTransactionManager(f.db.source),settings,readiness,"fictional-configured-worker",f.q.clock);
        var worker=new WorkerAuthorizationService(f.db.writes,store,f.q.clock,n->0,()->true);var action=new SharedWriteExecutor.ActionContext("fictional-worker-configured-claim","fictional-worker-configured-receipt",null);var claim=worker.claim(action);assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());var id=claim.permit().identity();assertTrue(f.roots.contains(id.rootId()));var sends=new AtomicInteger();
        assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),sent->{assertEquals(id.frozenRequestJson(),sent);sends.incrementAndGet();return "fictional-result";}).state());assertEquals(1,sends.get());assertEquals(1,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs WHERE task_type='news_value'",Integer.class));assertEquals(1,f.db.count("target_type='worker_claim'"));assertEquals(1,f.db.count("target_type='worker_send'"));verify(readiness,times(2)).captureSource(id.rootId());verify(readiness,times(2)).readyCaptured(eq(id.rootId()),any());verify(readiness,never()).ready(id.rootId());verify(settings,never()).freezeForNewInput();
        worker=new WorkerAuthorizationService(f.db.writes,WorkerRuntimeConfiguration.workerStore(f.db.source,new DataSourceTransactionManager(f.db.source),settings,f.service,"fictional-configured-worker",f.q.clock),f.q.clock,n->0,()->true);var replay=worker.claim(action);assertEquals(id,replay.permit().identity());assertEquals(WorkerAuthorizationService.State.UNKNOWN,worker.dispatch(replay.permit(),sent->sends.incrementAndGet()).state());assertEquals(1,sends.get());
    }
    @Test void ExplicitAssemblyIsLazyAndMissingActualTransactionManagerIsRejected(){var source=mock(DataSource.class);var tx=mock(PlatformTransactionManager.class);var settings=mock(SettingsUpdateService.class);var ready=mock(DispatchReadinessService.class);var clock=mock(Clock.class);assertNotNull(WorkerRuntimeConfiguration.workerStore(source,tx,settings,ready,"fictional-configured-worker",clock));verifyNoInteractions(source,tx,settings,ready,clock);assertThrows(RuntimeException.class,()->WorkerRuntimeConfiguration.workerStore(source,null,settings,ready,"fictional-configured-worker",clock));verifyNoInteractions(source,tx,settings,ready,clock);}
}
