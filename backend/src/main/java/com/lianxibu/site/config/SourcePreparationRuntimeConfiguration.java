package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSourceIntentScan;
import com.lianxibu.site.service.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in source preparation; one parent step on its own thread, separate from AI dispatch. */
@Configuration(proxyBeanMethods=false)
@Import(NewsSourcePreparationConfiguration.class)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled"},havingValue="true")
public class SourcePreparationRuntimeConfiguration {
    @Bean SourceAnalysisPreparationCycle sourceAnalysisPreparationCycle(DataSource source,PlatformTransactionManager tx,SourceAnalysisParentStepService parent){return new SourceAnalysisPreparationCycle(new JdbcSourceIntentScan(source),parent,tx);}
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="sourcePreparationExecutor")
    ScheduledExecutorService sourcePreparationExecutor(){
        var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"source-preparation");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop sourcePreparationLoop(SourceAnalysisPreparationCycle cycle,@Qualifier("sourcePreparationExecutor") ScheduledExecutorService executor){return new Loop(cycle,executor);}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final SourceAnalysisPreparationCycle cycle;private final ScheduledExecutorService executor;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(SourceAnalysisPreparationCycle cycle,ScheduledExecutorService executor){this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("source preparation still stopping");running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS));}
            catch(RuntimeException rejected){running=false;future=null;throw rejected;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{
                if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.run(this::isRunning));
                if(outcome==SourceAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED||outcome==SourceAnalysisPreparationCycle.Outcome.BUSY)failed();else failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        private void failed(){if(!failure)LOG.warn("source_preparation_recovery_required");failure=true;}
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
