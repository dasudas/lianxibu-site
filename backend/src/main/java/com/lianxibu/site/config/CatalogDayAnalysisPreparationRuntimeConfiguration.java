package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.CatalogDayAnalysisPreparationCycle;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;

/** Opt-in original day analysis: one entity and one category per tick, with graceful shutdown. */
@Configuration(proxyBeanMethods=false)
@Import(CatalogDayAnalysisProofConfiguration.class)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class CatalogDayAnalysisPreparationRuntimeConfiguration {
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="catalogDayAnalysisPreparationExecutor")
    ScheduledExecutorService catalogDayAnalysisPreparationExecutor(){
        var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"catalog-day-analysis-preparation");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop catalogDayAnalysisPreparationLoop(CatalogDayAnalysisProofConfiguration.Graphs graphs,@Qualifier("catalogDayAnalysisPreparationExecutor") ScheduledExecutorService executor,@org.springframework.beans.factory.annotation.Value("${site.execution.catalog-day-analysis-runtime.interval-seconds:1}") long seconds){
        return new Loop(CatalogSourceProofConfiguration.CATEGORIES.stream().map(category->graphs.graph(category).cycle()).toList(),executor,seconds);
    }
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final List<CatalogDayAnalysisPreparationCycle> cycles;private final ScheduledExecutorService executor;private final long seconds;
        private boolean running,inFlight;private int next;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(List<CatalogDayAnalysisPreparationCycle> cycles,ScheduledExecutorService executor){this(cycles,executor,1);}
        Loop(List<CatalogDayAnalysisPreparationCycle> cycles,ScheduledExecutorService executor,long seconds){
            if(seconds<1||seconds>300)throw new IllegalArgumentException("catalog day analysis preparation interval outside bounds");
            Objects.requireNonNull(cycles);if(cycles.size()!=4||cycles.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("four catalog day cycles required");
            var unique=Collections.newSetFromMap(new IdentityHashMap<CatalogDayAnalysisPreparationCycle,Boolean>());unique.addAll(cycles);if(unique.size()!=4)throw new IllegalArgumentException("distinct catalog day cycles required");
            this.cycles=List.copyOf(cycles);this.executor=Objects.requireNonNull(executor);this.seconds=seconds;
        }
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("catalog day preparation still stopping");running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,seconds,seconds,TimeUnit.SECONDS));}catch(RuntimeException rejected){running=false;future=null;throw rejected;}
        }
        private void tick(){
            CatalogDayAnalysisPreparationCycle cycle;synchronized(this){if(!running||inFlight)return;inFlight=true;cycle=cycles.get(next);next=(next+1)%cycles.size();}
            try{
                if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.run(this::isRunning));
                if(outcome==CatalogDayAnalysisPreparationCycle.Outcome.RECOVERY_REQUIRED||outcome==CatalogDayAnalysisPreparationCycle.Outcome.BUSY)failed();else failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        private void failed(){if(!failure)LOG.warn("catalog_day_analysis_preparation_recovery_required");failure=true;}
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
