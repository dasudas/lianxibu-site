package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.repository.jobs.JdbcOperationalDeliveries;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CloudNotificationDeliveryTest {
    @TempDir Path directory;
    final OperationalPersistenceFixture f=new OperationalPersistenceFixture();final AtomicInteger tokens=new AtomicInteger();
    CloudNotificationDeliveryTest(){OperationalDeliveryPartitionTest.aliases(f);}
    OperationalDeliveryJdbcStore store(){return new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OperationalDeliveryPartitionTest.CHANNEL));}
    OperationalNoticeDelivery delivery(OperationalNoticeDelivery.Sink sink){return new OperationalNoticeDelivery(store(),sink,f.writes,f.clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-cloud-token-"+tokens.incrementAndGet());}
    void notice(){f.monitor(f.store()).sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),f.clock.instant(),OperationalJdbcStoreTest.action("fictional-cloud-notice"));}
    @Test void logCompletionDoesNotSkipCloudAndRestartReadsOwnPlatformReceipt()throws Exception {
        notice();String original=f.jdbc.queryForObject("SELECT action_payload FROM jobs",String.class);var log=new OperationalNoticeLog(directory,f.clock,4096,8192,Duration.ofDays(7));
        assertEquals(OperationalNoticeDelivery.Outcome.LOG_HANDED_OFF,new OperationalNoticeDelivery(new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(f.source)),log,f.writes,f.clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-local-token").deliverNext());
        var seen=new ArrayList<String>();var sink=new CloudNotificationSink(log,p->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(java.nio.file.Files.exists(directory.resolve("ops-notice-current.jsonl")));seen.add(p.message());return new Receipt(AliyunNotificationClientTestId.VALUE);});
        assertEquals(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED,delivery(sink).deliverNext());assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery(e->fail()).deliverNext());
        assertEquals(1,seen.size());assertEquals(original,f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class));
        assertEquals("cloud_accepted",com.lianxibu.site.domain.protocol.CanonicalJson.parse(f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id LIKE 'opscd!_%' ESCAPE '!'",String.class)).get("phase").asString());
        assertEquals(2,java.nio.file.Files.readAllLines(directory.resolve("ops-notice-current.jsonl")).size());
    }
    @Test void unknownPlatformResultRetriesSameEventAndNeverStoresExceptionOrToken()throws Exception {
        notice();var log=new OperationalNoticeLog(directory,f.clock,4096,8192,Duration.ofDays(7));var messages=new ArrayList<String>();
        assertEquals(OperationalNoticeDelivery.Outcome.RETRY_PENDING,delivery(new CloudNotificationSink(log,p->{messages.add(p.message());throw new java.io.IOException("fictional-private-token-detail");})).deliverNext());
        assertEquals(OperationalNoticeDelivery.Outcome.IDLE,delivery(e->fail()).deliverNext());f.clock.now=f.clock.now.plusSeconds(10);
        assertEquals(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED,delivery(new CloudNotificationSink(log,p->{messages.add(p.message());return new Receipt(AliyunNotificationClientTestId.VALUE);})).deliverNext());
        assertEquals(messages.getFirst(),messages.getLast());String receipts=f.jdbc.queryForList("SELECT action_payload FROM jobs WHERE target_type='ops_cloud_delivery'",String.class).toString();
        assertTrue(receipts.contains("cloud_notification_failed"));assertFalse(receipts.contains("fictional-private-token-detail"));
    }
    @Test void failedLocalHandoffAndNullPlatformReceiptCannotMarkCloudAccepted() {
        notice();var calls=new AtomicInteger();
        assertEquals(OperationalNoticeDelivery.Outcome.RETRY_PENDING,delivery(new CloudNotificationSink(e->{throw new java.io.IOException("fictional log busy");},p->{calls.incrementAndGet();return new Receipt(AliyunNotificationClientTestId.VALUE);})).deliverNext());assertEquals(0,calls.get());
        f.clock.now=f.clock.now.plusSeconds(10);
        assertEquals(OperationalNoticeDelivery.Outcome.RETRY_PENDING,delivery(new CloudNotificationSink(e->{},p->null)).deliverNext());
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id LIKE 'opscd!_%' ESCAPE '!'",Integer.class));
    }
    @Test void expiredSendCannotFinishAndNextAttemptRetainsOriginalId() {
        notice();var seen=new ArrayList<String>();
        assertEquals(OperationalNoticeDelivery.Outcome.STALE_CLAIM,delivery(e->{seen.add(e.id());f.clock.now=f.clock.now.plusSeconds(61);}).deliverNext());
        assertEquals(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED,delivery(e->seen.add(e.id())).deliverNext());assertEquals(seen.getFirst(),seen.getLast());
    }
    @Test void stoppedConsumerCannotClaimAndCanResumeAfterInFlightIsReleased() {
        notice();var d=delivery(e->{});d.stop();assertEquals(OperationalNoticeDelivery.Outcome.IDLE,d.deliverNext());
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='ops_cloud_delivery'",Integer.class));
        d.start();assertEquals(OperationalNoticeDelivery.Outcome.PLATFORM_ACCEPTED,d.deliverNext());
    }
    static final class AliyunNotificationClientTestId {static final String VALUE="11111111-1111-4111-8111-111111111111";}
}
