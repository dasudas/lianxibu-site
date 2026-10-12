package com.lianxibu.site.config;

import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit host-only notification activation, isolated from worker leases and local log polling. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="site.ops.cloud.enabled",havingValue="true")
public class CloudNotificationConfiguration {
    record Input(Channel channel,AliyunNotificationClient.Credentials credentials) {
        @Override public String toString(){return "CloudNotificationInput[redacted]";}
        static Input load(String location) {
            try {
                if(location==null||location.isBlank())throw new IllegalArgumentException();Path path=Path.of(location);
                if(!path.isAbsolute())throw new IllegalArgumentException();
                for(Path entry=path;entry!=null;entry=entry.getParent())if(Files.isSymbolicLink(entry))throw new IllegalArgumentException();
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException();
                byte[] bytes;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(65537);}if(bytes.length>65536)throw new IllegalArgumentException();
                var fields=Set.of("account_id","contact_group","token");var value=WireValues.object(CanonicalJson.parse(bytes),fields,fields);
                String account=WireValues.text(value.get("account_id")),group=WireValues.text(value.get("contact_group"));
                return new Input(Channel.forAccount(account,group),new AliyunNotificationClient.Credentials(account,group,WireValues.text(value.get("token"))));
            }catch(java.io.IOException|RuntimeException unavailable){throw new IllegalStateException("private cloud notification configuration unavailable");}
        }
    }
    @Bean OperationalNoticeDelivery cloudNoticeDelivery(DataSource source,PlatformTransactionManager tx,
            @Value("${SITE_OPS_CLOUD_CONFIG}") String inputPath,@Value("${SITE_OPS_LOG_DIRECTORY}") String directory,
            @Value("${SITE_OPS_LOG_SEGMENT_BYTES}") long segmentBytes,@Value("${SITE_OPS_LOG_TOTAL_BYTES}") long totalBytes,
            @Value("${SITE_OPS_LOG_RETENTION}") String retention,@Value("${SITE_OPS_CLOUD_LEASE}") String lease,
            @Value("${SITE_OPS_CLOUD_RETRY}") String retry)throws java.io.IOException {
        Input input=Input.load(inputPath);Path path=Path.of(directory);
        if(directory.isBlank()||!path.isAbsolute())throw new IllegalArgumentException("absolute dedicated log directory required");
        Duration duration=Duration.parse(lease);if(duration.compareTo(Duration.ofSeconds(30))<0)throw new IllegalArgumentException("cloud lease requires network and transaction margin");
        Clock clock=Clock.systemUTC();var log=new OperationalNoticeLog(path,clock,segmentBytes,totalBytes,Duration.parse(retention));
        var store=new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(source,Destination.CLOUD,input.channel().fingerprint()));
        return new OperationalNoticeDelivery(store,new CloudNotificationSink(log,new AliyunNotificationClient(input::credentials)),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock,duration,Duration.parse(retry),()->UUID.randomUUID().toString());
    }
    @Bean(destroyMethod="shutdownNow") @ConditionalOnMissingBean(name="cloudNotificationExecutor")
    ScheduledExecutorService cloudNotificationExecutor() {
        var executor=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"cloud-notify");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
    @Bean Loop cloudNotificationLoop(@Qualifier("cloudNoticeDelivery") OperationalNoticeDelivery delivery,
            @Qualifier("cloudNotificationExecutor") ScheduledExecutorService executor,
            @Value("${SITE_OPS_CLOUD_INTERVAL}") String interval,@Value("${SITE_OPS_CLOUD_BATCH}") int batch) {
        return new Loop(delivery,executor,Duration.parse(interval),batch);
    }
    public static final class Loop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Loop.class);
        private final OperationalNoticeDelivery delivery;private final ScheduledExecutorService executor;private final Duration interval;private final int batch;
        private volatile boolean running,failure;private boolean inFlight;private ScheduledFuture<?> future;private final List<Runnable> callbacks=new ArrayList<>();
        Loop(OperationalNoticeDelivery delivery,ScheduledExecutorService executor,Duration interval,int batch) {
            if(interval==null||interval.compareTo(Duration.ofSeconds(1))<0||interval.compareTo(Duration.ofHours(1))>0||batch<1||batch>4)throw new IllegalArgumentException("bounded cloud notification scheduling required");
            this.delivery=Objects.requireNonNull(delivery);this.executor=Objects.requireNonNull(executor);this.interval=interval;this.batch=batch;
        }
        @Override public synchronized void start() {
            if(running)return;if(inFlight)throw new IllegalStateException("cloud notification still stopping");delivery.start();running=true;
            try{future=Objects.requireNonNull(executor.scheduleWithFixedDelay(this::tick,interval.toNanos(),interval.toNanos(),TimeUnit.NANOSECONDS));}
            catch(RuntimeException rejected){running=false;delivery.stop();future=null;throw rejected;}
        }
        private void tick() {
            synchronized(this){if(!running||inFlight||Thread.currentThread().isInterrupted())return;inFlight=true;}
            try{for(int n=0;running&&!Thread.currentThread().isInterrupted()&&n<batch;n++)if(delivery.deliverNext()==OperationalNoticeDelivery.Outcome.IDLE)break;failure=false;}
            catch(RuntimeException unavailable){if(!failure)LOG.warn("ops_cloud_notification_poll_failed");failure=true;}
            finally{List<Runnable> done;synchronized(this){inFlight=false;done=List.copyOf(callbacks);callbacks.clear();}for(var callback:done)try{callback.run();}catch(RuntimeException failed){LOG.warn("ops_cloud_notification_stop_callback_failed");}}
        }
        @Override public void stop(){stop(null);}
        @Override public void stop(Runnable callback) {
            boolean done;synchronized(this){running=false;delivery.stop();if(future!=null){future.cancel(true);future=null;}done=!inFlight;if(!done&&callback!=null)callbacks.add(callback);}
            if(done&&callback!=null)callback.run();
        }
        @Override public boolean isRunning(){return running;}
        @Override public int getPhase(){return Integer.MAX_VALUE;}
        public boolean hasFailure(){return failure;}
    }
}
