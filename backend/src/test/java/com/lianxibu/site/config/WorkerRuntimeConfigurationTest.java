package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class WorkerRuntimeConfigurationTest {
 String[] enabled(){return new String[]{"site.browser.enabled=true","site.content.enabled=true","site.articles.enabled=true","site.sources.enabled=true","site.execution.enabled=true","site.collector.enabled=true","site.execution.worker.enabled=true"};}
 ApplicationContextRunner assembled(){
  var scheduler=mock(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);when(scheduler.getScheduledExecutor()).thenReturn(mock(ScheduledExecutorService.class));when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenReturn(mock(ScheduledFuture.class));
  return new ApplicationContextRunner().withUserConfiguration(WorkerRuntimeConfiguration.class).withBean(javax.sql.DataSource.class,()->mock(javax.sql.DataSource.class)).withBean(org.springframework.transaction.PlatformTransactionManager.class,()->mock(org.springframework.transaction.PlatformTransactionManager.class)).withBean(DispatchReadinessService.class,()->mock(DispatchReadinessService.class)).withBean(SettingsUpdateService.class,()->mock(SettingsUpdateService.class)).withBean(OperationalInputGate.class,()->new OperationalInputGate(null)).withBean(AttemptOutcomeService.Applications.class,()->mock(AttemptOutcomeService.Applications.class)).withBean("scheduleExecutor",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class,()->scheduler).withBean("aiExecutor",Executor.class,()->task->{});
 }
 @Test void enabledActualFactoryNeedsCredentialAndDoesNotSendOnConstruction(){
  assembled().withPropertyValues(enabled()).run(c->{assertNotNull(c.getStartupFailure());assertTrue(c.getStartupFailure().getMessage().contains("workerRuntimeCycle"));});
  assembled().withPropertyValues(enabled()).withPropertyValues("SITE_DEEPSEEK_API_KEY=fictional-runtime-only").run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(WorkerRuntimeCycle.class));assertEquals(1,c.getBeansOfType(WorkerRecoveryMonitor.class).size());assertNull(c.getBean(WorkerRecoveryMonitor.class).read());assertTrue(c.getBean(WorkerRuntimeConfiguration.WorkerLoop.class).isRunning());verifyNoInteractions(c.getBean(javax.sql.DataSource.class));});
 }
 @Test void disabledByDefaultRequiresNoSecretsOrDatabase(){new ApplicationContextRunner().withUserConfiguration(WorkerRuntimeConfiguration.class).run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(WorkerRuntimeCycle.class).isEmpty());assertTrue(c.getBeansOfType(WorkerRecoveryMonitor.class).isEmpty());});}
 @Test void tickOnlySubmitsOneCycleAndStopWaitsForSubmittedWork(){
  var tick=new AtomicReference<Runnable>();var pending=new AtomicReference<Runnable>();var scheduler=mock(TaskScheduler.class);var future=mock(ScheduledFuture.class);var cycle=mock(WorkerRuntimeCycle.class);var stopped=new AtomicBoolean();
  when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenAnswer(a->{tick.set(a.getArgument(0));return future;});when(cycle.run(any())).thenReturn(WorkerRuntimeCycle.Outcome.IDLE);
  var loop=new WorkerRuntimeConfiguration.WorkerLoop(cycle,scheduler,pending::set,Clock.systemUTC());loop.start();tick.get().run();tick.get().run();verifyNoInteractions(cycle);assertNotNull(pending.get());loop.stop(()->stopped.set(true));assertFalse(stopped.get());assertThrows(IllegalStateException.class,loop::start);pending.get().run();assertTrue(stopped.get());verifyNoInteractions(cycle);verify(future).cancel(false);
 }
 @Test void rejectedSubmissionAndRecoveryFailureRemainRetryable(){
  var tick=new AtomicReference<Runnable>();var reject=new AtomicBoolean(true);var scheduler=mock(TaskScheduler.class);var cycle=mock(WorkerRuntimeCycle.class);
  when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});when(cycle.run(any())).thenReturn(WorkerRuntimeCycle.Outcome.RECOVERY_REQUIRED,WorkerRuntimeCycle.Outcome.IDLE);
  var loop=new WorkerRuntimeConfiguration.WorkerLoop(cycle,scheduler,r->{if(reject.get())throw new RejectedExecutionException();r.run();},Clock.systemUTC());loop.start();tick.get().run();assertTrue(loop.hasFailure());reject.set(false);tick.get().run();assertTrue(loop.hasFailure());tick.get().run();assertFalse(loop.hasFailure());loop.stop();verify(cycle,times(2)).run(any());
 }
 @Test void inFlightCycleObservesStopAndCallbackWaits()throws Exception{
  var tick=new AtomicReference<Runnable>();var scheduler=mock(TaskScheduler.class);var cycle=mock(WorkerRuntimeCycle.class);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var done=new CountDownLatch(1);
  when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenAnswer(a->{tick.set(a.getArgument(0));return mock(ScheduledFuture.class);});when(cycle.run(any())).thenAnswer(a->{java.util.function.BooleanSupplier running=a.getArgument(0);assertTrue(running.getAsBoolean());entered.countDown();assertTrue(finish.await(4,TimeUnit.SECONDS));assertFalse(running.getAsBoolean());return WorkerRuntimeCycle.Outcome.COMPLETED;});
  var executor=Executors.newSingleThreadExecutor();var loop=new WorkerRuntimeConfiguration.WorkerLoop(cycle,scheduler,executor,Clock.systemUTC());
  try{loop.start();tick.get().run();assertTrue(entered.await(3,TimeUnit.SECONDS));tick.get().run();loop.stop(done::countDown);assertEquals(1,done.getCount());finish.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));verify(cycle,times(1)).run(any());}finally{finish.countDown();loop.stop();executor.shutdownNow();}
 }
 @Test void schedulerFailureDoesNotPretendRunning(){var scheduler=mock(TaskScheduler.class);when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(Instant.class),any(Duration.class))).thenThrow(new RejectedExecutionException());var loop=new WorkerRuntimeConfiguration.WorkerLoop(mock(WorkerRuntimeCycle.class),scheduler,Runnable::run,Clock.systemUTC());assertThrows(RejectedExecutionException.class,loop::start);assertFalse(loop.isRunning());}

 @Test void configuredPauseUsesActualMappedSettingsAndRefusesMissingOrDamagedMapping(){
  var source=new org.h2.jdbcx.JdbcDataSource();source.setURL("jdbc:h2:mem:worker-mapped-pause-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
  jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB,revision BIGINT,updated_at TIMESTAMP(6))");
  String mapped="{\"mapping_version\":\"1\",\"follow_keywords\":[],\"blocked_keywords\":[],\"ai_paused\":false,\"pause_new_jobs\":true,\"default_package_id\":null,\"operational_limits\":[],\"operational_config_revision\":0}";
  jdbc.update("INSERT INTO runtime_settings VALUES('preferences',?,4,CURRENT_TIMESTAMP)",mapped);
  var tx=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);var writes=new SharedWriteExecutor(new com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry(source),tx);
  var settings=new SettingsUpdateService(new SettingsJdbcStore(new com.lianxibu.site.repository.settings.JdbcSettingsWrites(source),id->false,change->{throw new AssertionError("no write during pause read");},Clock.systemUTC()),writes);
  var store=WorkerRuntimeConfiguration.workerStore(source,settings,mock(DispatchReadinessService.class),"fictional-worker",Clock.systemUTC());
  var reader=new org.springframework.transaction.support.TransactionTemplate(tx);
  assertEquals(Boolean.FALSE,reader.execute(status->store.paused()));
  jdbc.update("UPDATE runtime_settings SET value_json=?",mapped.replace("\"ai_paused\":false","\"ai_paused\":true").replace("\"pause_new_jobs\":true","\"pause_new_jobs\":false"));
  assertEquals(Boolean.TRUE,reader.execute(status->store.paused()));
  for(String invalid:java.util.List.of("{\"pause_new_jobs\":false}","{}",mapped.replace("\"operational_config_revision\":0","\"operational_config_revision\":2"))){jdbc.update("UPDATE runtime_settings SET value_json=?",invalid);assertThrows(RuntimeException.class,()->reader.execute(status->store.paused()));}
  jdbc.update("DELETE FROM runtime_settings");assertThrows(RuntimeException.class,()->reader.execute(status->store.paused()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM runtime_settings",Integer.class));
 }
}
