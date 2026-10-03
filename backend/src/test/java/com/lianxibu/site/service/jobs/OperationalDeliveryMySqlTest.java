package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="B44_MYSQL",matches="true")
class OperationalDeliveryMySqlTest {
    @Test void mysqlPermanentFailureRetryAndReceiptWithOriginalNoticeUnchanged() {
        try(var fixture=new PooledResultJdbcMySqlFixture()) {
            fixture.fixture();fixture.jdbc.execute("DROP TABLE IF EXISTS runtime_settings");
            fixture.jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json JSON NOT NULL,revision BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL)");
            var clock=new OperationalPersistenceFixture.MutableClock();
            var state=new OperationalJdbcStore(new JdbcOperationalSummaries(fixture.source),new JdbcJobRecords(fixture.source),clock);
            var monitor=new OperationalMonitoringService(state,()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),fixture.writes,clock);
            monitor.sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),clock.instant(),OperationalJdbcStoreTest.action("fictional-mysql-notice"));
            String original=fixture.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class);
            var sequence=new AtomicInteger();var events=new ArrayList<String>();
            var store=new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(fixture.source));
            var delivery=new OperationalNoticeDelivery(store,event->{events.add(event.id());fixture.tx.ackLost=true;if(events.size()==1)throw new java.io.IOException("fictional failure");},fixture.writes,clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-mysql-"+sequence.incrementAndGet());
            fixture.tx.ackLost=true; // Both claim and finish commits lose their acknowledgement.
            assertEquals(OperationalNoticeDelivery.Outcome.RETRY_PENDING,delivery.deliverNext());assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery.deliverNext());clock.now=clock.now.plusSeconds(10);
            assertEquals(OperationalNoticeDelivery.Outcome.LOG_HANDED_OFF,delivery.deliverNext());assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery.deliverNext());
            assertEquals(2,events.size());assertEquals(events.getFirst(),events.getLast());assertTrue(new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(fixture.source)).finished("ops-finish:fictional-mysql-3"));
            assertEquals(original,fixture.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class));
        }
    }
}
