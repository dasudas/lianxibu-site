package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit worker activation; private provider credentials are read only at runtime. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.worker.enabled"},havingValue="true")
public class WorkerRuntimeConfiguration {
    @Bean WorkerRuntimeCycle workerRuntimeCycle(DataSource source,PlatformTransactionManager tx,DispatchReadinessService readiness,SettingsUpdateService settings,OperationalInputGate gate,AttemptOutcomeService.Applications applications,@Qualifier("scheduleExecutor") ThreadPoolTaskScheduler scheduler,Environment environment,WorkerRecoveryMonitor observedRecovery){
        String key=environment.getProperty("SITE_DEEPSEEK_API_KEY");
        if(key==null||key.isBlank())throw new IllegalStateException("worker provider credential unavailable");
        Clock clock=Clock.systemUTC();String epoch=UUID.randomUUID().toString();
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var state=new JdbcWorkerState(source);var records=new JdbcJobRecords(source);
        var store=workerStore(source,tx,settings,readiness,epoch,clock);
        var worker=new WorkerAuthorizationService(writes,store,clock,gate::dispatchAllowed);
        var unknown=new TransportUnknownService(writes,new JdbcTransportUnknown(source),clock,epoch);
        var outcomes=new AttemptOutcomeService(new AttemptUsageService(writes,new JdbcAttemptUsage(source),clock,epoch),new AttemptFailureService(writes,new JdbcAttemptFailure(source),clock,epoch),unknown,applications);
        var runner=new WorkerAttemptRunner(worker,new DeepSeekChatClient(()->environment.getProperty("SITE_DEEPSEEK_API_KEY")),new WorkerLeaseService(writes,new JdbcWorkerLease(source),clock,epoch),unknown,outcomes,scheduler.getScheduledExecutor(),clock);
        return new WorkerRuntimeCycle(observedRecovery::scan,worker,runner,epoch);
    }
    @Bean WorkerRecoveryMonitor workerRecoveryMonitor(DataSource source,PlatformTransactionManager tx){
        Clock clock=Clock.systemUTC();String actor=UUID.randomUUID().toString();
        var state=new JdbcWorkerState(source);var rows=new JdbcWorkerRecovery(source,state);
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        // New process identity is not proof that the original holder stopped.
        var store=new UnknownCallJdbcStore(state,rows,new JdbcJobRecords(source),actor,ignored->false);
        var sweep=new WorkerRecoverySweep(rows,store,new UnknownCallRecoveryService(writes,store,clock),clock);
        return new WorkerRecoveryMonitor(sweep,()->{
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("recovery snapshot must own its readonly transaction");
            var reader=new org.springframework.transaction.support.TransactionTemplate(tx);reader.setReadOnly(true);
            reader.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return reader.execute(status->rows.operationalSnapshot(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        },clock);
    }
    static WorkerJdbcStore workerStore(DataSource source,SettingsUpdateService settings,DispatchReadinessService readiness,String epoch,Clock clock){
        return workerStore(source,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),settings,readiness,epoch,clock);
    }
    public static WorkerJdbcStore workerStore(DataSource source,PlatformTransactionManager tx,SettingsUpdateService settings,DispatchReadinessService readiness,String epoch,Clock clock){
        Objects.requireNonNull(settings);Objects.requireNonNull(readiness);
        return new WorkerJdbcStore(new JdbcWorkerState(source),new JdbcJobRecords(source),epoch,()->!settings.newAttemptAllowed(),readiness,clock,tx);
    }
    @Bean @DependsOn({"aiExecutor","scheduleExecutor"}) WorkerLoop workerLoop(WorkerRuntimeCycle cycle,@Qualifier("scheduleExecutor") TaskScheduler scheduler,@Qualifier("aiExecutor") Executor executor){return new WorkerLoop(cycle,scheduler,executor,Clock.systemUTC());}
    public static final class WorkerLoop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(WorkerLoop.class);
        private final WorkerRuntimeCycle cycle;private final TaskScheduler scheduler;private final Executor executor;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        WorkerLoop(WorkerRuntimeCycle cycle,TaskScheduler scheduler,Executor executor,Clock clock){this.cycle=Objects.requireNonNull(cycle);this.scheduler=Objects.requireNonNull(scheduler);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("worker cycle still stopping");running=true;
            try{future=Objects.requireNonNull(scheduler.scheduleWithFixedDelay(this::tick,clock.instant().plusSeconds(1),Duration.ofSeconds(1)));}
            catch(RuntimeException error){running=false;future=null;throw error;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{executor.execute(this::execute);}
            catch(RuntimeException rejected){failed();complete();}
        }
        private void execute(){
            try{
                if(!isRunning())return;
                var result=Objects.requireNonNull(cycle.run(this::isRunning));
                if(result==WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED||result==WorkerRuntimeCycle.Outcome.BUSY)failed();else failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{complete();}
        }
        private void failed(){if(!failure)LOG.warn("worker_runtime_recovery_required");failure=true;}
        private void complete(){List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){
            boolean complete;synchronized(this){running=false;if(future!=null){future.cancel(false);future=null;}complete=!inFlight;if(!complete&&callback!=null)callbacks.add(callback);}
            if(complete&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
