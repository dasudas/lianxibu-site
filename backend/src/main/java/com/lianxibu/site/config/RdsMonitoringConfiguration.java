package com.lianxibu.site.config;

import com.lianxibu.site.domain.jobs.RdsTelemetry.Resource;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.settings.SettingsJdbcStore;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit host-only activation with a dedicated thread; no startup SQL or network acquisition. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.rds.enabled","site.browser.enabled","site.collector.enabled"},havingValue="true")
public class RdsMonitoringConfiguration {
    record Input(Resource resource,AliyunRdsMetricsClient.Credentials credentials) {
        @Override public String toString(){return "RdsMonitoringInput[redacted]";}
        static Input load(String location) {
            try {
                if(location==null||location.isBlank())throw new IllegalArgumentException();Path path=Path.of(location);
                if(!path.isAbsolute())throw new IllegalArgumentException();
                for(Path entry=path;entry!=null;entry=entry.getParent())if(Files.isSymbolicLink(entry))throw new IllegalArgumentException();
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException();
                byte[] bytes;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(65537);}if(bytes.length>65536)throw new IllegalArgumentException();
                var fields=Set.of("account_id","region_id","instance_id","access_key_id","access_key_secret");
                var root=WireValues.object(CanonicalJson.parse(bytes),fields,fields);
                return new Input(new Resource(WireValues.text(root.get("account_id")),WireValues.text(root.get("region_id")),WireValues.text(root.get("instance_id"))),new AliyunRdsMetricsClient.Credentials(WireValues.text(root.get("access_key_id")),WireValues.text(root.get("access_key_secret"))));
            }catch(java.io.IOException|RuntimeException unavailable){throw new IllegalStateException("private RDS monitoring configuration unavailable");}
        }
    }
    @Bean RdsMonitoringCycle rdsMonitoringCycle(DataSource source,PlatformTransactionManager tx,
            @Value("${SITE_RDS_MONITOR_CONFIG}") String inputPath,@Value("${SITE_OPERATIONAL_SCOPES}") String scopePath) {
        Input input=Input.load(inputPath);var catalog=OperationalScopeCatalog.load(scopePath);
        if(catalog.scopes()==null)throw new IllegalStateException("RDS operational resource binding unavailable");
        var capacity=catalog.scopes().capacity().stream().filter(key->key.ruleId().equals("OPS-04")).findFirst().orElseThrow();
        var telemetry=catalog.scopes().capacity().stream().filter(key->key.ruleId().equals("OPS-10")).findFirst().orElseThrow();
        Clock clock=Clock.systemUTC();
        var settings=new SettingsJdbcStore(new JdbcSettingsWrites(source),ignored->false,change->{throw new IllegalStateException("read-only operational settings");},clock);
        var summaries=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);
        var monitor=new OperationalMonitoringService(summaries,()->{
            var config=settings.operationalConfiguration();return config.ready()?new OperationalMonitoringService.Configuration(config.revision(),config.rules()):null;
        },new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);
        return new RdsMonitoringCycle(new AliyunRdsMetricsClient(input::credentials,clock),new RdsCapacityEvidence(),input.resource(),capacity,telemetry,summaries,monitor,clock);
    }
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="rdsMonitoringExecutor")
    ScheduledExecutorService rdsMonitoringExecutor() {
        var executor=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"rds-monitor");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop rdsMonitoringLoop(RdsMonitoringCycle cycle,@Qualifier("rdsMonitoringExecutor") ScheduledExecutorService executor){return new Loop(cycle,executor,Clock.systemUTC());}
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final RdsMonitoringCycle cycle;private final ScheduledExecutorService executor;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(RdsMonitoringCycle cycle,ScheduledExecutorService executor,Clock clock){this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);}
        @Override public synchronized void start(){
            if(running)return;if(inFlight)throw new IllegalStateException("RDS sampling still stopping");
            cycle.start();running=true;
            Instant now=clock.instant();long delay=Duration.between(now,now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)).toNanos();
            try{future=Objects.requireNonNull(executor.scheduleAtFixedRate(this::tick,delay,TimeUnit.SECONDS.toNanos(60),TimeUnit.NANOSECONDS));}
            catch(RuntimeException rejected){running=false;cycle.stop();future=null;throw rejected;}
        }
        private void tick(){
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{cycle.tick();failure=false;}catch(RuntimeException unavailable){if(!failure)LOG.warn("rds_monitoring_sample_failed");failure=true;}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback){
            boolean done;synchronized(this){running=false;cycle.stop();if(future!=null){future.cancel(true);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}
            if(done&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
