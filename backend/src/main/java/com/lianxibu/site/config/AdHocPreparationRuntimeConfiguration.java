package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsUpdateService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Opt-in actual original-question acceptance and preparation; no provider calls. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.execution.enabled","site.execution.ad-hoc.enabled"},havingValue="true")
public class AdHocPreparationRuntimeConfiguration {
    @Bean JdbcOwnerAdHocCreation ownerAdHocOriginals(DataSource source){return new JdbcOwnerAdHocCreation(source);}
    @Bean OwnerAdHocCreateService ownerAdHocCreation(DataSource source,PlatformTransactionManager tx,JdbcOwnerAdHocCreation originals,SettingsUpdateService settings,OperationalInputGate gate){
        return new OwnerAdHocCreateService(new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),originals,()->{var frozen=settings.freezeForNewInput();return new FrozenGenerationSettings(frozen.settingsRevision(),frozen.followKeywords(),frozen.blockedKeywords());},gate::preparationAllowed,Clock.tick(Clock.systemUTC(),Duration.ofNanos(1000)));
    }
    @Bean OwnerAdHocPreparationService ownerAdHocPreparation(DataSource source,PlatformTransactionManager tx,JdbcOwnerAdHocCreation originals,OperationalInputGate gate){return new OwnerAdHocPreparationService(source,originals,new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,Clock.tick(Clock.systemUTC(),Duration.ofNanos(1000)),gate::preparationAllowed);}
    @Bean AdHocPreparationCycle adHocPreparationCycle(DataSource source,PlatformTransactionManager tx,JdbcOwnerAdHocCreation originals,OwnerAdHocPreparationService preparation){return new AdHocPreparationCycle(new JdbcAdHocIntentScan(source),originals,preparation,tx);}
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="adHocPreparationExecutor") ScheduledExecutorService adHocPreparationExecutor(){var executor=new ScheduledThreadPoolExecutor(1,work->{var thread=new Thread(work,"temporary-preparation");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;}
    @Bean Loop adHocPreparationLoop(AdHocPreparationCycle cycle,@Qualifier("adHocPreparationExecutor") ScheduledExecutorService executor){return new Loop(cycle,executor);}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final AdHocPreparationCycle cycle;private final ScheduledExecutorService executor;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(AdHocPreparationCycle cycle,ScheduledExecutorService executor){this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("temporary preparation still stopping");running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS));}
            catch(RuntimeException rejected){running=false;future=null;throw rejected;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{
                if(!isRunning())return;var outcome=Objects.requireNonNull(cycle.run(this::isRunning));
                if(outcome==AdHocPreparationCycle.Outcome.RECOVERY_REQUIRED||outcome==AdHocPreparationCycle.Outcome.BUSY)failed();else failure=false;
            }catch(RuntimeException unavailable){failed();}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        private void failed(){if(!failure)LOG.warn("temporary_preparation_recovery_required");failure=true;}
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
