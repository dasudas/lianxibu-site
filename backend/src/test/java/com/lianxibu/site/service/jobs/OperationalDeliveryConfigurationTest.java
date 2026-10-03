package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ExecutionConfiguration;
import com.lianxibu.site.config.OperationalDeliveryConfiguration;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class OperationalDeliveryConfigurationTest {
    @TempDir Path directory;
    final OperationalPersistenceFixture fixture=new OperationalPersistenceFixture();
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class,OperationalDeliveryConfiguration.class)
            .withBean(DataSource.class,()->fixture.source).withBean(PlatformTransactionManager.class,()->fixture.tx);}
    String[] enabled(){return new String[]{"site.ops.delivery.enabled=true","SITE_OPS_LOG_DIRECTORY="+directory.toAbsolutePath(),
            "SITE_OPS_LOG_SEGMENT_BYTES=4096","SITE_OPS_LOG_TOTAL_BYTES=8192","SITE_OPS_LOG_RETENTION=PT168H",
            "SITE_OPS_DELIVERY_LEASE=PT60S","SITE_OPS_DELIVERY_RETRY=PT10S","SITE_OPS_DELIVERY_INTERVAL=PT1S","SITE_OPS_DELIVERY_BATCH=2"};}
    @Test void disabledByDefaultAndMissingConfigurationFailsStartup() {
        runner().run(context->{assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(OperationalNoticeDelivery.class).size());});
        runner().withPropertyValues("site.ops.delivery.enabled=true").run(context->assertNotNull(context.getStartupFailure()));
    }
    @Test void actualSchedulerHandsOffPermanentNoticeAndShutdownStopsFuturePolling() {
        fixture.monitor(fixture.store()).sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),fixture.clock.instant(),OperationalJdbcStoreTest.action("fictional-runtime-notice"));
        final OperationalDeliveryConfiguration.DeliveryLoop[] loop=new OperationalDeliveryConfiguration.DeliveryLoop[1];
        runner().withPropertyValues(enabled()).run(context->{
            assertNull(context.getStartupFailure());loop[0]=context.getBean(OperationalDeliveryConfiguration.DeliveryLoop.class);assertTrue(loop[0].isRunning());
            long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
            while(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id LIKE 'opsd!_%' ESCAPE '!'",Integer.class)==0&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id LIKE 'opsd!_%' ESCAPE '!'",Integer.class));
            assertEquals(1,Files.readAllLines(directory.resolve("ops-notice-current.jsonl")).size());
        });
        assertFalse(loop[0].isRunning());
    }
    @Test void invalidRelativeDirectoryAndZeroPollIntervalAreRejected() {
        runner().withPropertyValues(enabled()).withPropertyValues("SITE_OPS_LOG_DIRECTORY=fictional-relative").run(context->assertNotNull(context.getStartupFailure()));
        runner().withPropertyValues(enabled()).withPropertyValues("SITE_OPS_DELIVERY_INTERVAL=PT0S").run(context->assertNotNull(context.getStartupFailure()));
    }
}
