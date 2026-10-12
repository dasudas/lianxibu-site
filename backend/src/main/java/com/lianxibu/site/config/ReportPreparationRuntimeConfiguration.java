package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.JdbcReportSchedulePages;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Opt-in initial report scheduling. One native scope per tick; no executor is created by proof configuration. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled","site.execution.report-runtime.enabled"},havingValue="true")
public class ReportPreparationRuntimeConfiguration {
 @Bean public JdbcReportSchedulePages reportSchedulePages(DataSource source){return new JdbcReportSchedulePages(source);}
 @Bean public ReportPreparationCycle reportPreparationCycle(JdbcReportSchedulePages pages,ReportPreparationService preparation,ReportNoUpdateProofService noUpdates,PlatformTransactionManager tx,OperationalInputGate gate){return new ReportPreparationCycle(pages,preparation,noUpdates,tx,Clock.systemUTC(),Objects.requireNonNull(gate)::preparationAllowed);}
 @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="reportPreparationExecutor")
 ScheduledExecutorService reportPreparationExecutor(){var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"report-preparation");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;}
 @Bean public Loop reportPreparationLoop(ReportPreparationCycle cycle,@Qualifier("reportPreparationExecutor") ScheduledExecutorService executor,@Value("${site.execution.report-runtime.interval-seconds:1}") long seconds){return new Loop(cycle,executor,seconds);}
 public static final class Loop implements SmartLifecycle {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
  private final ReportPreparationCycle cycle;private final ScheduledExecutorService executor;private final long seconds;
  private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;private final List<Runnable> callbacks=new ArrayList<>();
  Loop(ReportPreparationCycle cycle,ScheduledExecutorService executor,long seconds){this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);if(seconds<1||seconds>300)throw new IllegalArgumentException("report preparation interval outside bounds");this.seconds=seconds;}
  @Override public synchronized void start(){if(running)return;if(inFlight)throw new IllegalStateException("report preparation still stopping");running=true;try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,seconds,seconds,TimeUnit.SECONDS));}catch(RuntimeException unavailable){running=false;future=null;throw unavailable;}}
  private void tick(){synchronized(this){if(!running||inFlight)return;inFlight=true;}try{if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.run(this::isRunning));if(outcome==ReportPreparationCycle.Outcome.RECOVERY_REQUIRED||outcome==ReportPreparationCycle.Outcome.BUSY)failed();else failure=false;}catch(RuntimeException unavailable){failed();}finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}}
  private void failed(){if(!failure)LOG.warn("report_preparation_recovery_required");failure=true;}
  @Override public void stop(){stop(null);}
  @Override public void stop(Runnable callback){boolean done;synchronized(this){running=false;if(future!=null){future.cancel(false);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}if(done&&callback!=null)callback.run();}
  @Override public synchronized boolean isRunning(){return running;}
  @Override public int getPhase(){return Integer.MAX_VALUE;}
  public boolean hasFailure(){return failure;}
 }
}
