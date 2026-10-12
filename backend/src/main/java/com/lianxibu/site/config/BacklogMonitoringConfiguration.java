package com.lianxibu.site.config;
import com.lianxibu.site.domain.protocol.WireValues;import com.lianxibu.site.repository.jobs.*;import com.lianxibu.site.repository.settings.JdbcSettingsWrites;import com.lianxibu.site.service.jobs.*;import com.lianxibu.site.service.settings.SettingsJdbcStore;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;import java.util.concurrent.*;import java.util.function.LongSupplier;import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.*;import org.springframework.boot.autoconfigure.condition.*;import org.springframework.context.SmartLifecycle;import org.springframework.context.annotation.*;import org.springframework.transaction.*;import org.springframework.transaction.support.*;
/** Original readiness/current settings, native commit witness and permanent backlog sampling. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.backlog-monitoring.enabled","site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled"},havingValue="true")
public class BacklogMonitoringConfiguration {
 public record Batch(List<BacklogMonitoringCycle> cycles,BacklogHistoryObservationService nativeFacts,JdbcBacklogMutationWitness.Scope scope){public Batch{cycles=List.copyOf(cycles);if(cycles.isEmpty()||nativeFacts==null||scope==null)throw new IllegalArgumentException("native backlog monitor required");}@Override public String toString(){return "BacklogMonitoringBatch[redacted]";}}
 /** Low phase keeps the witness active until HTTP and workers, then sampler, have stopped. */
 public static final class Witness implements SmartLifecycle,AutoCloseable{
  private final JdbcBacklogMutationWitness.Scope scope;private boolean running,closed;
  Witness(DataSource source,Clock clock){scope=JdbcBacklogMutationWitness.open(source,clock);}
  @Override public synchronized void start(){if(closed)throw new IllegalStateException("native backlog witness requires a fresh runtime");running=true;}
  @Override public synchronized void stop(){running=false;if(!closed){closed=true;scope.close();}}
  @Override public void stop(Runnable callback){stop();if(callback!=null)callback.run();}
  @Override public synchronized boolean isRunning(){return running;}@Override public int getPhase(){return Integer.MIN_VALUE+100;}@Override public void close(){stop();}
  @Override public String toString(){return "BacklogNativeWitness[redacted]";}
 }
 @Bean(destroyMethod="close") Witness backlogNativeWitness(DataSource source){return new Witness(source,Clock.systemUTC());}
 @Bean Batch backlogMonitoringBatch(DataSource source,PlatformTransactionManager tx,DispatchReadinessService readiness,Witness witness,@Value("${SITE_BACKLOG_MONITOR_RESOURCE}")String resource){return create(source,tx,readiness,witness.scope,new OperationalRuleEngine.Key("OPS-01","java",WireValues.id(resource,false)),Clock.systemUTC(),System::nanoTime);}
 public static Batch create(DataSource source,PlatformTransactionManager tx,DispatchReadinessService readiness,JdbcBacklogMutationWitness.Scope scope,OperationalRuleEngine.Key key,Clock clock,LongSupplier monotonic){
  Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(readiness);Objects.requireNonNull(clock);Objects.requireNonNull(monotonic);if(key==null||!"OPS-01".equals(key.ruleId())||!"java".equals(key.component()))throw new IllegalArgumentException("native backlog operational binding unavailable");WireValues.id(key.resourceKey(),false);
  var settings=new SettingsJdbcStore(new JdbcSettingsWrites(source),ignored->false,change->{throw new IllegalStateException("read-only backlog monitoring settings");},clock);
  java.util.function.Supplier<OperationalMonitoringService.Configuration> current=()->{var config=settings.operationalConfiguration();return config.ready()?new OperationalMonitoringService.Configuration(config.revision(),config.rules()):null;};
  OperationalMonitoringService.ConfigurationSource configuration=()->{if(TransactionSynchronizationManager.isActualTransactionActive())return current.get();var reader=new TransactionTemplate(tx);reader.setReadOnly(true);reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reader.setTimeout(10);return reader.execute(status->current.get());};
  var facts=new BacklogHistoryObservationService(new JdbcBacklogHistory(source,scope),readiness,settings,tx,clock,monotonic);var store=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
  return new Batch(List.of(new BacklogMonitoringCycle(key,facts,store,configuration,writes,clock)),facts,scope);
 }
 @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="backlogMonitoringExecutor") ScheduledExecutorService backlogMonitoringExecutor(){var executor=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"backlog-monitor");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;}
 @Bean Loop backlogMonitoringLoop(Batch batch,@Qualifier("backlogMonitoringExecutor")ScheduledExecutorService executor){return new Loop(batch,executor,Clock.systemUTC());}
 public static final class Loop implements SmartLifecycle {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);private final Batch batch;private final ScheduledExecutorService executor;private final Clock clock;private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;private final List<Runnable> callbacks=new ArrayList<>();
  Loop(Batch batch,ScheduledExecutorService executor,Clock clock){this.batch=Objects.requireNonNull(batch);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);}
  @Override public synchronized void start(){if(running)return;if(inFlight)throw new IllegalStateException("backlog sampling still stopping");batch.cycles().forEach(BacklogMonitoringCycle::start);running=true;Instant now=clock.instant();long delay=Duration.between(now,now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)).toNanos();try{future=Objects.requireNonNull(executor.scheduleAtFixedRate(this::tick,delay,TimeUnit.SECONDS.toNanos(60),TimeUnit.NANOSECONDS));}catch(RuntimeException rejected){running=false;batch.cycles().forEach(BacklogMonitoringCycle::stop);future=null;throw rejected;}}
  private void tick(){synchronized(this){if(!running||inFlight)return;inFlight=true;}try{boolean failed=false;for(var cycle:batch.cycles()){synchronized(this){if(!running)break;}try{cycle.tick();}catch(RuntimeException unavailable){failed=true;}}if(failed&&!failure)LOG.warn("backlog_monitoring_sample_failed");failure=failed;}finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}}
  @Override public void stop(){stop(null);}@Override public void stop(Runnable callback){boolean done;synchronized(this){running=false;batch.cycles().forEach(BacklogMonitoringCycle::stop);if(future!=null){future.cancel(true);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}if(done&&callback!=null)callback.run();}
  @Override public synchronized boolean isRunning(){return running;}@Override public int getPhase(){return Integer.MAX_VALUE;}public boolean hasFailure(){return failure;}
 }
}
