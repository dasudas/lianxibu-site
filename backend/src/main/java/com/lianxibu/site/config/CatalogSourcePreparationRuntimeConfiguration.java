package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.CatalogSourcePreparationCycle;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;

/** Opt-in catalog scheduling: one outside parent step, one category per tick. */
@Configuration(proxyBeanMethods=false)
@Import(CatalogSourceProofConfiguration.class)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled"},havingValue="true")
public class CatalogSourcePreparationRuntimeConfiguration {
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="catalogSourcePreparationExecutor")
    ScheduledExecutorService catalogSourcePreparationExecutor(){
        var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"catalog-source-preparation");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop catalogSourcePreparationLoop(CatalogSourceProofConfiguration.Graphs graphs,@Qualifier("catalogSourcePreparationExecutor") ScheduledExecutorService executor){
        return new Loop(CatalogSourceProofConfiguration.CATEGORIES.stream().map(category->graphs.graph(category).cycle()).toList(),executor);
    }
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final List<CatalogSourcePreparationCycle> cycles;private final ScheduledExecutorService executor;
        private boolean running,inFlight;private int next;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(List<CatalogSourcePreparationCycle> cycles,ScheduledExecutorService executor){
            Objects.requireNonNull(cycles);if(cycles.size()!=4||cycles.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("four catalog cycles required");
            var unique=Collections.newSetFromMap(new IdentityHashMap<CatalogSourcePreparationCycle,Boolean>());unique.addAll(cycles);if(unique.size()!=4)throw new IllegalArgumentException("distinct catalog cycles required");
            this.cycles=List.copyOf(cycles);this.executor=Objects.requireNonNull(executor);
        }
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("catalog preparation still stopping");running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS));}catch(RuntimeException rejected){running=false;future=null;throw rejected;}
        }
        private void tick(){
            CatalogSourcePreparationCycle cycle;synchronized(this){if(!running||inFlight)return;inFlight=true;cycle=cycles.get(next);next=(next+1)%cycles.size();}
            try{
                if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.run(this::isRunning));
                if(outcome==CatalogSourcePreparationCycle.Outcome.RECOVERY_REQUIRED||outcome==CatalogSourcePreparationCycle.Outcome.BUSY)failed();else failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        private void failed(){if(!failure)LOG.warn("catalog_source_preparation_recovery_required");failure=true;}
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
