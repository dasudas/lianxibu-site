package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit report opt-in, actual native event and first stores, with one independent lazy thread. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class EntityHeatObservationConfiguration {
    @Bean public JdbcEntityHeatEvents entityHeatNativeEvents(DataSource source){return new JdbcEntityHeatEvents(source);}
    @Bean public EntityHeatEventProofService entityHeatEventProof(JdbcEntityHeatEvents events,PlatformTransactionManager tx){return new EntityHeatEventProofService(events,tx);}
    @Bean public EntityHeatEventAvailabilityService entityHeatEventAvailability(DataSource source,JdbcEntityHeatEvents events,EntityHeatEventProofService proofs,PlatformTransactionManager tx){return new EntityHeatEventAvailabilityService(proofs,new JdbcEntityHeatAvailability(source,events),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,Clock.systemUTC());}
    @Bean public EntityHeatEventAvailabilityPoller entityHeatEventAvailabilityPoller(DataSource source,EntityHeatEventAvailabilityService observations,PlatformTransactionManager tx){return new EntityHeatEventAvailabilityPoller(new JdbcEntityHeatEventLocators(source),observations,tx,64);}
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="entityHeatObservationExecutor")
    ScheduledExecutorService entityHeatObservationExecutor(){var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"entity-heat-observation");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;}
    @Bean Loop entityHeatObservationLoop(EntityHeatEventAvailabilityPoller poller,@Qualifier("entityHeatObservationExecutor") ScheduledExecutorService executor){return new Loop(poller,executor);}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final EntityHeatEventAvailabilityPoller poller;private final ScheduledExecutorService executor;private final List<Runnable> callbacks=new ArrayList<>();private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        Loop(EntityHeatEventAvailabilityPoller poller,ScheduledExecutorService executor){this.poller=Objects.requireNonNull(poller);this.executor=Objects.requireNonNull(executor);}
        @Override public synchronized void start(){if(running)return;if(inFlight)throw new IllegalStateException("entity heat observation still stopping");running=true;try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS));}catch(RuntimeException rejected){running=false;future=null;throw rejected;}}
        private void tick(){synchronized(this){if(!running||inFlight)return;inFlight=true;}try{var outcome=Objects.requireNonNull(poller.poll());if(outcome.failed()>0)throw new IllegalStateException("entity heat observation incomplete");failure=false;}catch(RuntimeException unavailable){if(!failure)LOG.warn("entity_heat_observation_recovery_required");failure=true;}finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}}
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){boolean done;synchronized(this){running=false;if(future!=null){future.cancel(false);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}if(done&&callback!=null)callback.run();}
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
