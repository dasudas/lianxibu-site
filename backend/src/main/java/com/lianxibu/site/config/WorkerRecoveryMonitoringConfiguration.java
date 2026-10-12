package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit actual worker observation binding, separate executor and no constructor database work. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.recovery.enabled","site.browser.enabled","site.collector.enabled","site.execution.worker.enabled"},havingValue="true")
public class WorkerRecoveryMonitoringConfiguration {
 @Bean WorkerRecoveryMonitoringCycle workerRecoveryMonitoringCycle(DataSource source,PlatformTransactionManager tx,WorkerRecoveryMonitor facts,@Value("${SITE_OPERATIONAL_SCOPES}")String location){
  var scopes=OperationalScopeCatalog.load(location).scopes();if(scopes==null)throw new IllegalStateException("native worker operational binding unavailable");
  var keys=scopes.dispatch().stream().filter(key->"OPS-03".equals(key.ruleId())).toList();
  if(keys.size()!=1||!"java".equals(keys.getFirst().component())||!"analysis-worker".equals(keys.getFirst().resourceKey()))throw new IllegalStateException("native worker operational binding unavailable");
  var key=keys.getFirst();Clock clock=Clock.systemUTC();
  var settings=new SettingsJdbcStore(new JdbcSettingsWrites(source),ignored->false,change->{throw new IllegalStateException("read-only monitoring settings");},clock);
  java.util.function.Supplier<OperationalMonitoringService.Configuration> current=()->{var config=settings.operationalConfiguration();return config.ready()?new OperationalMonitoringService.Configuration(config.revision(),config.rules()):null;};
  OperationalMonitoringService.ConfigurationSource configuration=()->{
   if(TransactionSynchronizationManager.isActualTransactionActive())return current.get();
   var reader=new TransactionTemplate(tx);reader.setReadOnly(true);reader.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);return reader.execute(status->current.get());
  };
  var store=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);
  var monitor=new OperationalMonitoringService(store,configuration,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);
  return new WorkerRecoveryMonitoringCycle(key,facts,new WorkerRecoveryEvidence(),store,monitor,configuration,clock);
 }
 @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="workerRecoveryMonitoringExecutor") ScheduledExecutorService workerRecoveryMonitoringExecutor(){
  var executor=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"worker-recovery-monitor");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
 }
 @Bean Loop workerRecoveryMonitoringLoop(WorkerRecoveryMonitoringCycle cycle,@Qualifier("workerRecoveryMonitoringExecutor")ScheduledExecutorService executor){return new Loop(cycle,executor,Clock.systemUTC());}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final WorkerRecoveryMonitoringCycle cycle;private final ScheduledExecutorService executor;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(WorkerRecoveryMonitoringCycle cycle,ScheduledExecutorService executor,Clock clock){this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("worker recovery sampling still stopping");
            cycle.start();running=true;
            Instant now=clock.instant();long delay=Duration.between(now,now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)).toNanos();
            try{future=Objects.requireNonNull(executor.scheduleAtFixedRate(this::tick,delay,TimeUnit.SECONDS.toNanos(60),TimeUnit.NANOSECONDS));}
            catch(RuntimeException rejected){running=false;cycle.stop();future=null;throw rejected;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{cycle.tick();failure=false;}catch(RuntimeException unavailable){if(!failure)LOG.warn("worker_recovery_monitoring_sample_failed");failure=true;}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){
            boolean done;synchronized(this){running=false;cycle.stop();if(future!=null){future.cancel(true);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}
            if(done&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
