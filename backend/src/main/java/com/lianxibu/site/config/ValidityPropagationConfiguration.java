package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EvidenceValidityService;
import com.lianxibu.site.service.content.*;
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

/** Explicit opt-in to durable non-AI propagation on the existing scheduler. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.content.propagation.enabled"},havingValue="true")
public class ValidityPropagationConfiguration {
    @Bean ValidityPropagationPoller validityPropagationPoller(DataSource source,PlatformTransactionManager tx){
        var clock=Clock.systemUTC();String actor="validity-worker";
        var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var contents=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        var writer=new ContentReviewWriteService(new JdbcReviewPublication(source),new GeneratedContentService(new JdbcGeneratedContents(source),clock),clock);
        var ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,actor);
        var queue=new JdbcValidityPropagation(source);var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        return new ValidityPropagationPoller(queue,()->new VersionValidityService(writes,new VersionValidityJdbcStore(ledger,queue,versions,contents,writer,clock,actor,null)),()->UUID.randomUUID().toString());
    }
    @Bean @DependsOn("scheduleExecutor") PropagationLoop validityPropagationLoop(ValidityPropagationPoller poller,@Qualifier("scheduleExecutor") TaskScheduler scheduler){return new PropagationLoop(poller,scheduler,Clock.systemUTC());}
    public static final class PropagationLoop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PropagationLoop.class);
        private final ValidityPropagationPoller poller;private final TaskScheduler scheduler;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> scheduled;
        private final List<Runnable> stopCallbacks=new ArrayList<>();
        PropagationLoop(ValidityPropagationPoller poller,TaskScheduler scheduler,Clock clock){this.poller=Objects.requireNonNull(poller);this.scheduler=Objects.requireNonNull(scheduler);this.clock=Objects.requireNonNull(clock);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("propagation step still stopping");
            running=true;
            try{scheduled=Objects.requireNonNull(scheduler.scheduleWithFixedDelay(this::tick,clock.instant().plusSeconds(1),Duration.ofSeconds(1)));}
            catch(RuntimeException error){running=false;scheduled=null;throw error;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{Objects.requireNonNull(poller.poll());failure=false;}
            catch(RuntimeException error){if(!failure)LOG.warn("content_validity_propagation_failed");failure=true;}
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
