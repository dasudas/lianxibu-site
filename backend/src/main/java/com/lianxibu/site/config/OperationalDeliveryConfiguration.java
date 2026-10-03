package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcOperationalDeliveries;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Host-only opt-in. No default activation, destination, timing, or external recipient settings. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="site.ops.delivery.enabled",havingValue="true")
public class OperationalDeliveryConfiguration {
    @Bean OperationalNoticeDelivery operationalNoticeDelivery(DataSource source,PlatformTransactionManager tx,
            @Value("${SITE_OPS_LOG_DIRECTORY}") String directory,
            @Value("${SITE_OPS_LOG_SEGMENT_BYTES}") long segmentBytes,@Value("${SITE_OPS_LOG_TOTAL_BYTES}") long totalBytes,
            @Value("${SITE_OPS_LOG_RETENTION}") String retention,@Value("${SITE_OPS_DELIVERY_LEASE}") String lease,
            @Value("${SITE_OPS_DELIVERY_RETRY}") String retry) throws java.io.IOException {
        Path path=Path.of(directory);if(directory.isBlank()||!path.isAbsolute())throw new IllegalArgumentException("absolute dedicated log directory required");
        var clock=Clock.systemUTC();
        return new OperationalNoticeDelivery(new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(source)),
                new OperationalNoticeLog(path,clock,segmentBytes,totalBytes,Duration.parse(retention)),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),clock,Duration.parse(lease),Duration.parse(retry),()->UUID.randomUUID().toString());
    }
    @Bean DeliveryLoop operationalDeliveryLoop(OperationalNoticeDelivery delivery,
            @Qualifier("scheduleExecutor") TaskScheduler scheduler,
            @Value("${SITE_OPS_DELIVERY_INTERVAL}") String interval,@Value("${SITE_OPS_DELIVERY_BATCH}") int batch) {
        return new DeliveryLoop(delivery,scheduler,Duration.parse(interval),batch);
    }
    public static final class DeliveryLoop implements SmartLifecycle {
        private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DeliveryLoop.class);
        private final OperationalNoticeDelivery delivery;private final TaskScheduler scheduler;private final Duration interval;private final int batch;
        private volatile boolean running;private ScheduledFuture<?> scheduled;
        DeliveryLoop(OperationalNoticeDelivery delivery,TaskScheduler scheduler,Duration interval,int batch) {
            if(interval==null||interval.compareTo(Duration.ofSeconds(1))<0||interval.compareTo(Duration.ofHours(1))>0||batch<1||batch>10)
                throw new IllegalArgumentException("bounded delivery scheduling required");
            this.delivery=delivery;this.scheduler=scheduler;this.interval=interval;this.batch=batch;
        }
        @Override public synchronized void start(){if(running)return;running=true;try{scheduled=scheduler.scheduleWithFixedDelay(this::tick,Instant.now().plus(interval),interval);}catch(RuntimeException error){running=false;throw error;}}
        private void tick(){
            for(int n=0;running&&n<batch;n++) {
                try{if(delivery.deliverNext()==OperationalNoticeDelivery.Outcome.IDLE)return;}
                catch(RuntimeException failure){LOG.warn("ops_log_delivery_poll_failed");return;}
            }
        }
        @Override public synchronized void stop(){running=false;if(scheduled!=null)scheduled.cancel(false);}
        @Override public boolean isRunning(){return running;}
    }
}
