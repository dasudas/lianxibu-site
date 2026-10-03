package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.OperationalNoticeDelivery.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationalDeliveryJdbcStoreTest {
    @TempDir Path directory;
    final OperationalPersistenceFixture fixture=new OperationalPersistenceFixture();final AtomicInteger tokens=new AtomicInteger();
    OperationalDeliveryJdbcStore store(){return new OperationalDeliveryJdbcStore(new JdbcOperationalDeliveries(fixture.source));}
    OperationalNoticeDelivery service(Sink sink){return new OperationalNoticeDelivery(store(),sink,fixture.writes,fixture.clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-delivery-"+tokens.incrementAndGet());}
    void notice(){fixture.monitor(fixture.store()).sample(OperationalJdbcStoreTest.KEY,true,new OperationalRuleEngine.Capacity(new BigDecimal("4")),fixture.clock.instant(),OperationalJdbcStoreTest.action("fictional-delivery-sample"));}
    @Test void permanentIntentToLogHandoffSurvivesReconstructionAndNeverChangesOriginalAudit() throws Exception {
        notice();String original=fixture.jdbc.queryForObject("SELECT action_payload FROM jobs",String.class);
        var log=new OperationalNoticeLog(directory,fixture.clock,4096,8192,Duration.ofDays(7));
        assertEquals(Outcome.LOG_HANDED_OFF,service(log).deliverNext());assertEquals(Outcome.IDLE,service(e->fail()).deliverNext());
        assertEquals(original,fixture.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='ops_rule'",String.class));
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        assertEquals(1,java.nio.file.Files.readAllLines(directory.resolve("ops-notice-current.jsonl")).size());
        assertEquals("L1",com.lianxibu.site.domain.protocol.CanonicalJson.parse(java.nio.file.Files.readAllLines(directory.resolve("ops-notice-current.jsonl")).getFirst()).get("level").asString());
    }
    @Test void failureRetriesAfterDelayWithSameEventAndPermanentFinishLookup() {
        notice();var seen=new ArrayList<String>();assertEquals(Outcome.RETRY_PENDING,service(e->{seen.add(e.id());throw new java.io.IOException("fictional private detail");}).deliverNext());
        assertEquals(Outcome.IDLE,service(e->fail()).deliverNext());fixture.clock.now=fixture.clock.now.plusSeconds(10);
        assertEquals(Outcome.LOG_HANDED_OFF,service(e->seen.add(e.id())).deliverNext());assertEquals(seen.getFirst(),seen.getLast());
        assertTrue(store().finished("ops-finish:fictional-delivery-1"));
        assertFalse(fixture.jdbc.queryForList("SELECT action_payload FROM jobs",String.class).toString().contains("fictional private detail"));
    }
    @Test void expiredClaimCannotFinishNewLeaseAndReceiptRecoversOriginalClaim() {
        notice();var first=claim("fictional-first");assertEquals(first,store().claimed("fictional-first"));
        fixture.clock.now=fixture.clock.now.plusSeconds(61);var second=claim("fictional-second");assertEquals(first.event(),second.event());assertEquals(2,second.attempt());
        assertFalse(fixture.writes.execute(OperationalJdbcStoreTest.action("fictional-stale"),store()::finished,c->store().finish(c.actionKey(),first,fixture.clock.instant(),true,null,null)));
        assertTrue(fixture.writes.execute(OperationalJdbcStoreTest.action("fictional-current"),store()::finished,c->store().finish(c.actionKey(),second,fixture.clock.instant(),true,null,null)));
        assertEquals(Outcome.IDLE,service(e->fail()).deliverNext());
    }
    @Test void auditFailureRollsBackClaimAndChangedIntentHashCannotReachSink() {
        notice();fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_delivery_reject CHECK(target_type<>'ops_log_delivery')");
        assertThrows(RuntimeException.class,()->service(e->fail()).deliverNext());assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        fixture.jdbc.execute("ALTER TABLE jobs DROP CONSTRAINT fictional_delivery_reject");
        fixture.jdbc.update("UPDATE jobs SET action_payload='{}'");assertThrows(IllegalStateException.class,()->service(e->fail()).deliverNext());
    }
    Claim claim(String key){return fixture.writes.execute(OperationalJdbcStoreTest.action(key),store()::claimed,c->store().claim(c.actionKey(),key,fixture.clock.instant(),fixture.clock.instant().plusSeconds(60)));}
}
