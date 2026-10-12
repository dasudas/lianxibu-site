package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.repository.jobs.JdbcOperationalDeliveries;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OperationalDeliveryPartitionTest {
    static final String CHANNEL=Channel.forAccount("1212121212121212","fictional-ops").fingerprint();
    static final String OTHER=Channel.forAccount("1212121212121212","fictional-other").fingerprint();
    @Test void logCompletionAndOtherChannelLeaseNeverSuppressCloudAndReconstructionRetainsPartition() {
        var f=new OperationalPersistenceFixture();aliases(f);
        f.monitor(f.store()).sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),f.clock.instant(),OperationalJdbcStoreTest.action("fictional-partition-notice"));
        var log=new JdbcOperationalDeliveries(f.source);var cloud=new JdbcOperationalDeliveries(f.source,Destination.CLOUD,CHANNEL);
        var other=new JdbcOperationalDeliveries(f.source,Destination.CLOUD,OTHER);String notice=log.next(f.clock.instant()).id();
        append(f,log,log.completionId(notice),"fictional-log-finish",notice,"{}",0);
        assertNull(log.next(f.clock.instant()));assertNotNull(cloud.next(f.clock.instant()));
        append(f,other,other.receiptId("fictional-other-claim"),"fictional-other-claim",notice,"{\"phase\":\"claimed\"}",60);
        assertNotNull(cloud.next(f.clock.instant()));assertNull(other.next(f.clock.instant()));
        append(f,cloud,cloud.receiptId("fictional-cloud-claim"),"fictional-cloud-claim",notice,"{\"phase\":\"claimed\"}",60);
        assertNull(cloud.next(f.clock.instant()));assertNull(log.receipt("fictional-cloud-claim"));assertNull(other.receipt("fictional-cloud-claim"));
        assertEquals(cloud.latest(notice),new JdbcOperationalDeliveries(f.source,Destination.CLOUD,CHANNEL).latest(notice));
        f.clock.now=f.clock.now.plusSeconds(61);assertNotNull(cloud.next(f.clock.instant()));
        f.writes.execute(OperationalJdbcStoreTest.action("fictional-cloud-completion"),k->null,c->{cloud.append(cloud.completionId(notice),c.actionKey(),notice,2,"{\"phase\":\"cloud_accepted\"}",f.clock.instant(),f.clock.instant());return true;});
        assertNull(new JdbcOperationalDeliveries(f.source,Destination.CLOUD,CHANNEL).next(f.clock.instant()));assertNotNull(other.next(f.clock.instant()));
        assertNotEquals(log.completionId(notice),cloud.completionId(notice));assertNotEquals(other.completionId(notice),cloud.completionId(notice));
        assertFalse(cloud.latest(notice).target().contains("token"));assertTrue(cloud.latest(notice).target().contains(CHANNEL));
    }
    @Test void invalidPurposeAndTamperedChannelProofFailClosed() {
        var f=new OperationalPersistenceFixture();aliases(f);
        assertThrows(IllegalArgumentException.class,()->new JdbcOperationalDeliveries(f.source,Destination.CLOUD,null));
        assertThrows(IllegalArgumentException.class,()->new JdbcOperationalDeliveries(f.source,Destination.LOG,CHANNEL));
        assertThrows(IllegalArgumentException.class,()->Channel.forAccount("1212121212121212","unsafe/group"));
        var cloud=new JdbcOperationalDeliveries(f.source,Destination.CLOUD,CHANNEL);String notice="opsn_"+"a".repeat(48);
        append(f,cloud,cloud.receiptId("fictional-cloud-proof"),"fictional-cloud-proof",notice,"{}",0);
        f.jdbc.update("UPDATE jobs SET target_key_json=? WHERE target_type='ops_cloud_delivery'","{\"notice_id\":\"opsn_"+"b".repeat(48)+"\",\"channel_id\":\""+CHANNEL+"\"}");
        assertThrows(IllegalStateException.class,()->cloud.receipt("fictional-cloud-proof"));
    }
    static void append(OperationalPersistenceFixture f,JdbcOperationalDeliveries r,String id,String key,String notice,String payload,int seconds) {
        f.writes.execute(OperationalJdbcStoreTest.action(key),k->null,c->{r.append(id,c.actionKey(),notice,1,payload,f.clock.instant().plusSeconds(seconds),f.clock.instant());return true;});
    }
    static void aliases(OperationalPersistenceFixture f) {
        f.jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");
        f.jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");
    }
}
