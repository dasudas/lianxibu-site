package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.repository.jobs.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OperationalDeliveryPartitionMySqlTest {
    @Test void actualMysqlJsonPartitionIgnoresLocalCompletionAndOtherChannelLease() {
        try(var f=new PooledResultJdbcMySqlFixture()) {
            f.fixture();f.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");
            f.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
            var clock=new OperationalPersistenceFixture.MutableClock();var state=new OperationalJdbcStore(new JdbcOperationalSummaries(f.source),new JdbcJobRecords(f.source),clock);
            new OperationalMonitoringService(state,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),f.writes,clock)
                    .sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),clock.instant(),OperationalJdbcStoreTest.action("fictional-mysql-partition"));
            var log=new JdbcOperationalDeliveries(f.source);var cloud=new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.CHANNEL);
            var other=new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.OTHER);String notice=log.next(clock.instant()).id();
            f.writes.execute(OperationalJdbcStoreTest.action("fictional-local-completion"),k->null,c->{log.append(log.completionId(notice),c.actionKey(),notice,1,"{}",clock.instant(),clock.instant());return true;});
            assertNull(log.next(clock.instant()));assertNotNull(cloud.next(clock.instant()));
            f.writes.execute(OperationalJdbcStoreTest.action("fictional-other-lease"),k->null,c->{other.append(other.receiptId(c.actionKey()),c.actionKey(),notice,1,"{\"phase\":\"claimed\"}",clock.instant().plusSeconds(60),clock.instant());return true;});
            assertNotNull(cloud.next(clock.instant()));assertNull(other.next(clock.instant()));
            f.writes.execute(OperationalJdbcStoreTest.action("fictional-cloud-accepted"),k->null,c->{cloud.append(cloud.completionId(notice),c.actionKey(),notice,1,"{\"phase\":\"cloud_accepted\"}",clock.instant(),clock.instant());return true;});
            assertNull(new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.CHANNEL).next(clock.instant()));
            clock.now=clock.now.plusSeconds(61);assertNotNull(other.next(clock.instant()));
            assertNotNull(cloud.receipt("fictional-cloud-accepted"));assertNull(log.receipt("fictional-cloud-accepted"));assertNull(other.receipt("fictional-cloud-accepted"));
        }
    }
}
