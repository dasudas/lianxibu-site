package com.lianxibu.site.config;

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

/** Host-controlled Java memory sampling; no startup database access or native acquisition. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.java-memory.enabled","site.browser.enabled","site.collector.enabled"},havingValue="true")
public class JavaMemoryMonitoringConfiguration {
    record Input(JavaMemoryEvidence.Budget budget) {
        @Override public String toString() { return "JavaMemoryMonitoringInput[redacted]"; }
        static Input load(String location) {
            try {
                if(location==null||location.isBlank())throw new IllegalArgumentException();Path path=Path.of(location);
                if(!path.isAbsolute())throw new IllegalArgumentException();
                for(Path entry=path;entry!=null;entry=entry.getParent())if(Files.isSymbolicLink(entry))throw new IllegalArgumentException();
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException();
                byte[] bytes;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(65537);}if(bytes.length>65536)throw new IllegalArgumentException();
                var fields=Set.of("initial_heap_bytes","maximum_heap_bytes","maximum_metaspace_bytes","maximum_direct_bytes",
                        "reserved_code_cache_bytes","native_allowance_bytes","hard_limit_bytes","exit_on_out_of_memory_error","oom_hook_sha256");
                var root=WireValues.object(CanonicalJson.parse(bytes),fields,fields);
                if(!root.get("exit_on_out_of_memory_error").isBoolean())throw new IllegalArgumentException();
                return new Input(new JavaMemoryEvidence.Budget(WireValues.nonnegativeInteger(root.get("initial_heap_bytes")),
                        WireValues.nonnegativeInteger(root.get("maximum_heap_bytes")),WireValues.nonnegativeInteger(root.get("maximum_metaspace_bytes")),
                        WireValues.nonnegativeInteger(root.get("maximum_direct_bytes")),WireValues.nonnegativeInteger(root.get("reserved_code_cache_bytes")),
                        WireValues.nonnegativeInteger(root.get("native_allowance_bytes")),WireValues.nonnegativeInteger(root.get("hard_limit_bytes")),
                        root.get("exit_on_out_of_memory_error").booleanValue(),root.get("oom_hook_sha256").isNull()?null:WireValues.sha256(root.get("oom_hook_sha256"))));
            } catch(java.io.IOException|RuntimeException unavailable) { throw new IllegalStateException("private Java memory budget unavailable"); }
        }
    }
    @Bean JavaMemoryMonitoringCycle javaMemoryMonitoringCycle(DataSource source,PlatformTransactionManager tx,
            @Value("${SITE_JAVA_MEMORY_BUDGET_CONFIG}") String budgetPath,@Value("${SITE_OPERATIONAL_SCOPES}") String scopePath) {
        var input=Input.load(budgetPath);var catalog=OperationalScopeCatalog.load(scopePath);
        if(catalog.scopes()==null)throw new IllegalStateException("Java operational resource binding unavailable");
        var keys=catalog.scopes().dispatch().stream().filter(key->key.ruleId().equals("OPS-06")&&key.component().equals("java")).toList();
        if(keys.size()!=1)throw new IllegalStateException("Java operational resource binding unavailable");
        Clock clock=Clock.systemUTC();
        var settings=new SettingsJdbcStore(new JdbcSettingsWrites(source),ignored->false,change->{throw new IllegalStateException("read-only operational settings");},clock);
        var store=new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);
        var monitor=new OperationalMonitoringService(store,()->{
            var config=settings.operationalConfiguration();return config.ready()?new OperationalMonitoringService.Configuration(config.revision(),config.rules()):null;
        },new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock);
        return new JavaMemoryMonitoringCycle(new LinuxJavaMemoryProbe(clock),new LinuxJavaProcessProbe(clock),new LinuxJavaCgroupProbe(clock),
                new CurrentJavaVmMemoryProbe(clock),new CurrentJavaVmOomProbe(clock),new JavaMemoryEvidence(input.budget()),keys.getFirst(),store,monitor,clock);
    }
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="javaMemoryMonitoringExecutor")
    ScheduledExecutorService javaMemoryMonitoringExecutor() {
        var executor=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"java-memory-monitor");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop javaMemoryMonitoringLoop(JavaMemoryMonitoringCycle cycle,@Qualifier("javaMemoryMonitoringExecutor") ScheduledExecutorService executor) {
        return new Loop(cycle,executor,Clock.systemUTC());
    }
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final JavaMemoryMonitoringCycle cycle;private final ScheduledExecutorService executor;private final Clock clock;
        private boolean running,inFlight;private volatile boolean failure;private ScheduledFuture<?> future;
        private final List<Runnable> callbacks=new ArrayList<>();
        Loop(JavaMemoryMonitoringCycle cycle,ScheduledExecutorService executor,Clock clock) {
            this.cycle=Objects.requireNonNull(cycle);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);
        }
        @Override public synchronized void start() {
            if(running)return;if(inFlight)throw new IllegalStateException("Java memory sampling still stopping");cycle.start();running=true;
            Instant now=clock.instant();long delay=Duration.between(now,now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)).toNanos();
            try {future=Objects.requireNonNull(executor.scheduleAtFixedRate(this::tick,delay,TimeUnit.SECONDS.toNanos(60),TimeUnit.NANOSECONDS));}
            catch(RuntimeException rejected){running=false;cycle.stop();future=null;throw rejected;}
        }
        private void tick() {
            synchronized(this){if(!running||inFlight)return;inFlight=true;}
            try{cycle.tick();failure=false;}catch(RuntimeException unavailable){if(!failure)LOG.warn("java_memory_monitoring_sample_failed");failure=true;}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}done.forEach(Runnable::run);}
        }
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback) {
            boolean done;synchronized(this){running=false;cycle.stop();if(future!=null){future.cancel(true);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}
            if(done&&callback!=null)callback.run();
        }
        @Override public synchronized boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
