package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.jobs.OperationalNotification.Destination;
import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Fictional receipt history; the native subclass also inspects the actual database access plan. */
public class OperationalLogDeliveryLocatorTest {
    protected boolean mysql(){return false;}
    protected final Db database(){return new Db(mysql());}
    static final class Db implements AutoCloseable {
        final DataSource source;final JdbcTemplate jdbc;final SharedWriteExecutor writes;
        final OperationalPersistenceFixture.MutableClock clock=new OperationalPersistenceFixture.MutableClock();
        final PooledResultJdbcMySqlFixture nativeFixture;
        Db(boolean nativeDatabase){
            if(nativeDatabase){nativeFixture=new PooledResultJdbcMySqlFixture();nativeFixture.fixture();source=nativeFixture.source;jdbc=nativeFixture.jdbc;writes=nativeFixture.writes;}
            else{nativeFixture=null;var h=new OperationalPersistenceFixture();source=h.source;jdbc=h.jdbc;writes=h.writes;}
        }
        JdbcOperationalDeliveries log(){return new JdbcOperationalDeliveries(source);}
        JdbcOperationalDeliveries cloud(){return new JdbcOperationalDeliveries(source,Destination.CLOUD,"a".repeat(64));}
        String notice(char digit){
            String id="opsn_"+String.valueOf(digit).repeat(48);var target=JsonMapper.shared().createObjectNode().put("rule_id","OPS-04").put("component","java").put("resource_key","fictional-capacity");
            var payload=JsonMapper.shared().createObjectNode().put("record_type","ops_notice_intent").put("delivery_state","pending").put("level","CRITICAL").put("action","trigger").put("at",clock.instant().toString()).put("observed_value","4").put("threshold","5");
            appendDisposition(id,"fictional-notice-"+digit,"ops_rule",target.toString(),payload.toString());return id;
        }
        void appendDisposition(String id,String key,String type,String target,String payload){writes.execute(OperationalJdbcStoreTest.action(key),ignored->null,c->{new JdbcJobRecords(source).appendDisposition(new JdbcJobRecords.Disposition(id,c.actionKey(),type,target,"fictional-operator","fictional audit",payload),clock.instant());return true;});}
        void receipt(JdbcOperationalDeliveries repository,String notice,String key,long revision,Instant due,boolean complete){writes.execute(OperationalJdbcStoreTest.action(key),ignored->null,c->{repository.append(complete?repository.completionId(notice):repository.receiptId(key),c.actionKey(),notice,revision,complete?"{\"phase\":\"log_handed_off\"}":"{\"phase\":\"claimed\"}",due,clock.instant());return true;});}
        public void close(){if(nativeFixture!=null)nativeFixture.close();}
    }
    @Test void aFutureLeaseBlocksOnlyItsOwnNoticeAndTheOldestEligibleOrderIsPreserved(){try(var d=database()){
        String first=d.notice('1'),second=d.notice('2');d.receipt(d.log(),first,"fictional-live-lease",1,d.clock.instant().plusSeconds(60),false);
        assertEquals(second,d.log().next(d.clock.instant()).id());assertEquals(first,d.log().next(d.clock.instant().plusSeconds(61)).id());
        assertEquals(1,d.jdbc.queryForObject("SELECT COUNT(*)FROM jobs WHERE target_type='ops_log_delivery'",Integer.class));
    }}
    @Test void newerDueReceiptSupersedesAnOlderFutureLeaseAndCompletionSurvivesReconstruction(){try(var d=database()){
        String id=d.notice('1');String original=d.log().notice(id).payload();
        d.receipt(d.log(),id,"fictional-older-future",1,d.clock.instant().plusSeconds(3600),false);d.receipt(d.log(),id,"fictional-newer-due",2,d.clock.instant().minusSeconds(1),false);
        assertEquals(id,d.log().next(d.clock.instant()).id());assertEquals(2,d.log().latest(id).sequence());
        d.receipt(d.log(),id,"fictional-latest-future",3,d.clock.instant().plusSeconds(90),false);assertNull(d.log().next(d.clock.instant()));
        d.receipt(d.log(),id,"fictional-complete",4,d.clock.instant(),true);assertNull(new JdbcOperationalDeliveries(d.source).next(d.clock.instant().plusSeconds(4000)));assertEquals(original,d.log().notice(id).payload());
    }}
    @Test void cloudReceiptAndUnrelatedHistoryCannotSuppressTheLogCandidate(){try(var d=database()){
        String id=d.notice('1');d.receipt(d.cloud(),id,"fictional-cloud-only",1,d.clock.instant().plusSeconds(3600),false);
        d.receipt(d.log(),"opsn_"+"2".repeat(48),"fictional-other-target",1,d.clock.instant().plusSeconds(3600),false);
        assertEquals(id,d.log().next(d.clock.instant()).id());assertNull(d.log().latest("opsn_"+"3".repeat(48)));
    }}
    @Test void corruptedReceiptLocatorNeverCreatesANewClaimOrOverridesItsOriginalProof(){try(var d=database()){
        String id=d.notice('1');d.receipt(d.log(),id,"fictional-corrupt-receipt",1,d.clock.instant().plusSeconds(3600),false);
        d.jdbc.update("UPDATE jobs SET target_key_hash=? WHERE target_type='ops_log_delivery'",new byte[32]);int before=d.jdbc.queryForObject("SELECT COUNT(*)FROM jobs",Integer.class);
        var store=new OperationalDeliveryJdbcStore(d.log());
        try{var claim=d.writes.execute(OperationalJdbcStoreTest.action("fictional-denied-claim"),key->{var old=store.claimed(key);return old==null?null:Optional.of(old);},c->Optional.ofNullable(store.claim(c.actionKey(),"fictional-new-token",d.clock.instant(),d.clock.instant().plusSeconds(60))));assertTrue(claim.isEmpty());}
        catch(IllegalStateException refused){assertEquals("delivery audit hash mismatch",refused.getMessage());}
        assertEquals(before,d.jdbc.queryForObject("SELECT COUNT(*)FROM jobs",Integer.class));assertThrows(IllegalStateException.class,()->d.log().latest(id));
    }}
    // Only cryptographic/JSON scalar compatibility for H2. All business SQL is the production provider.
    public static String sha2(String value,int bits)throws Exception{if(value==null)return null;if(bits!=256)throw new IllegalArgumentException("fixture only supports SHA-256");return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    public static byte[] unhex(String value){return value==null?null:HexFormat.of().parseHex(value);}
    public static String jsonQuote(String value){return value==null?null:JsonMapper.shared().writeValueAsString(value);}
}
