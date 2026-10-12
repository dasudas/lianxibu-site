package com.lianxibu.site.config;

import com.lianxibu.site.service.jobs.SourceDayRunCoordinatorService;
import com.lianxibu.site.service.jobs.SourceDayRunRegistrationCycle;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;

/** Opt-in, independent daily registration thread. Full proofs come from the actual coordinator. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayRunRegistrationRuntimeConfiguration {
    @Bean SourceDayRunRegistrationCycle sourceDayRunRegistrationCycle(SourceDayRunCoordinatorService coordinator,
            @Value("${site.sources.day-roster.batch-size:64}") int batch){return createCycle(coordinator,Clock.systemUTC(),batch);}
    public SourceDayRunRegistrationCycle createCycle(SourceDayRunCoordinatorService coordinator,Clock clock,int batch){return new SourceDayRunRegistrationCycle(coordinator,clock,batch);}
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="sourceDayRegistrationExecutor")
    ScheduledExecutorService sourceDayRegistrationExecutor(){
        var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"source-day-registration");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop sourceDayRegistrationLoop(SourceDayRunRegistrationCycle cycle,
            @Qualifier("sourceDayRegistrationExecutor") ScheduledExecutorService executor,
            @Value("${site.sources.day-roster.period-seconds:30}") int seconds){return new Loop(cycle,executor,seconds);}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final SourceDayRunRegistrationCycle cycle;
        private final ScheduledExecutorService executor;
        private final int seconds;
        private boolean running,inFlight;
        private volatile boolean failure;
        private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(SourceDayRunRegistrationCycle cycle,ScheduledExecutorService executor,int seconds){
            this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);
            if(seconds<5||seconds>300)throw new IllegalArgumentException("bounded source registration period required");this.seconds=seconds;
        }
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("source registration still stopping");running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,1,seconds,TimeUnit.SECONDS));}
            catch(RuntimeException rejected){running=false;future=null;throw rejected;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{
                if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.tick(this::isRunning));
                if(outcome.failed()>0||outcome.unverified()>0||outcome.status()==SourceDayRunRegistrationCycle.Status.BUSY)failed();
                else if(outcome.status()==SourceDayRunRegistrationCycle.Status.PROGRESSED)failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{
                List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}
                done.forEach(Runnable::run);
            }
        }
        private void failed(){if(!failure)LOG.warn("source_day_registration_recovery_required");failure=true;}
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){
            boolean done;synchronized(this){running=false;if(future!=null){future.cancel(false);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}
            if(done&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
