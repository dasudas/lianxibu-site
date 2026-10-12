package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.repository.jobs.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class CloudNotificationDeliveryMySqlTest {
    @Test void mysqlCloudUnknownCommitRecoveryRetriesWithoutTouchingOriginalAndOtherChannel() {
        try(var f=new PooledResultJdbcMySqlFixture()) {
            f.fixture();f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
            var clock=new OperationalPersistenceFixture.MutableClock();var state=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            new OperationalMonitoringService(state,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,clock)
                    .sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),clock.instant(),OperationalJdbcStoreTest.action("fictional-mysql-cloud-notice"));
            String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class);var sequence=new AtomicInteger();var messages=new ArrayList<String>();
            var store=new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.CHANNEL));
            var delivery=new OperationalNoticeDelivery(store,new CloudNotificationSink(e->{},p->{messages.add(p.message());f.tx.ackLost=true;if(messages.size()==1)return null;return new Receipt(CloudNotificationDeliveryTest.AliyunNotificationClientTestId.VALUE);}),f.writes,clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-mysql-cloud-"+sequence.incrementAndGet());
            f.tx.ackLost=true;assertEquals(OperationalNoticeDelivery.Outcome.RETRY_PENDING,delivery.deliverNext());assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery.deliverNext());clock.now=clock.now.plusSeconds(10);
            assertEquals(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED,delivery.deliverNext());assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery.deliverNext());
            assertEquals(2,messages.size());assertEquals(messages.getFirst(),messages.getLast());
            assertNotNull(new JdbcOperationalDeliveries(f.source).next(clock.instant()));assertNotNull(new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.OTHER).next(clock.instant()));
            assertEquals(original,f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class));
            assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_cloud_delivery'",Integer.class));
        }
    }
}
