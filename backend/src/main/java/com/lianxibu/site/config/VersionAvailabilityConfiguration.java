package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit opt-in to committed version observation on the existing scheduler. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.content.availability.enabled"},havingValue="true")
public class VersionAvailabilityConfiguration {
    @Bean VersionAvailabilityPoller versionAvailabilityPoller(DataSource source,PlatformTransactionManager tx){
        var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var observer=new VersionAvailabilityService(versions,new JdbcVersionAvailability(source),writes,tx,Clock.systemUTC());
        return new VersionAvailabilityPoller(new JdbcValidityPropagation(source),observer,tx,50);
    }
    @Bean @DependsOn("scheduleExecutor") ObservationLoop validityObservationLoop(VersionAvailabilityPoller poller,@Qualifier("scheduleExecutor") TaskScheduler scheduler){return new ObservationLoop(poller,scheduler,Clock.systemUTC());}
    public static final class ObservationLoop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ObservationLoop.class);
        private final VersionAvailabilityPoller poller;private final TaskScheduler scheduler;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> scheduled;
        private final List<Runnable> stopCallbacks=new ArrayList<>();
        ObservationLoop(VersionAvailabilityPoller poller,TaskScheduler scheduler,Clock clock){this.poller=Objects.requireNonNull(poller);this.scheduler=Objects.requireNonNull(scheduler);this.clock=Objects.requireNonNull(clock);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("observation step still stopping");
            running=true;
            try{scheduled=Objects.requireNonNull(scheduler.scheduleWithFixedDelay(this::tick,clock.instant().plusSeconds(1),Duration.ofSeconds(1)));}
            catch(RuntimeException error){running=false;scheduled=null;throw error;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{var result=Objects.requireNonNull(poller.poll());if(result.failed()>0)throw new IllegalStateException("observation batch incomplete");failure=false;}
            catch(RuntimeException error){if(!failure)LOG.warn("content_version_observation_failed");failure=true;}
            finally{
                List<Runnable> callbacks;
                synchronized(this){inFlight=false;callbacks=List.copyOf(stopCallbacks);stopCallbacks.clear();}
                callbacks.forEach(Runnable::run);
            }
        }
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){
            boolean complete;
            synchronized(this){running=false;if(scheduled!=null){scheduled.cancel(false);scheduled=null;}complete=!inFlight;if(!complete&&callback!=null)stopCallbacks.add(callback);}
            if(complete&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        public boolean hasFailure(){return failure;}
    }
}
