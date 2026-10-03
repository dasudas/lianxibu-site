package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.OperationalNoticeDelivery.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class OperationalNoticeDeliveryTest {
    final OperationalPersistenceFixture fixture=new OperationalPersistenceFixture();final MemoryStore store=new MemoryStore();
    final AtomicInteger sequence=new AtomicInteger();
    final Event event=new Event("fictional-notice","OPS-04","database","fictional-resource","L1","triggered",fixture.clock.instant(),"4","5");
    OperationalNoticeDelivery service(Sink sink){return new OperationalNoticeDelivery(store,sink,fixture.writes,fixture.clock,Duration.ofSeconds(60),Duration.ofSeconds(10),()->"fictional-token-"+sequence.incrementAndGet());}
    @Test void failureSurvivesRestartAndRetryKeepsOriginalEventIdentity() {
        var seen=new ArrayList<String>();
        assertEquals(Outcome.RETRY_PENDING,service(e->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());seen.add(e.id());throw new java.io.IOException("must never persist this detail");}).deliverNext());
        assertEquals("log_handoff_failed",store.error);assertEquals(Outcome.IDLE,service(e->fail()).deliverNext());
        fixture.clock.now=fixture.clock.now.plusSeconds(10);
        assertEquals(Outcome.LOG_HANDED_OFF,service(e->seen.add(e.id())).deliverNext());assertEquals(List.of(event.id(),event.id()),seen);
        assertEquals(Outcome.IDLE,service(e->fail()).deliverNext());assertEquals(2,store.attempt);
    }
    @Test void expiredHandoffCannotConfirmAndRetryRetainsSameId() {
        assertEquals(Outcome.STALE_CLAIM,service(e->fixture.clock.now=fixture.clock.now.plusSeconds(61)).deliverNext());
        assertFalse(store.done);assertEquals(Outcome.LOG_HANDED_OFF,service(e->assertEquals(event,e)).deliverNext());assertEquals(2,store.attempt);
    }
    @Test void commitFailureDoesNotPretendSuccessOrRepeatExternalWriteInsideTransaction() {
        store.failFinish=true;var sent=new AtomicInteger();assertThrows(IllegalStateException.class,()->service(e->sent.incrementAndGet()).deliverNext());
        assertFalse(store.done);assertEquals(1,sent.get());store.failFinish=false;fixture.clock.now=fixture.clock.now.plusSeconds(61);
        assertEquals(Outcome.LOG_HANDED_OFF,service(e->sent.incrementAndGet()).deliverNext());assertEquals(2,sent.get());
    }
    @Test void callerTransactionAndInvalidConfigurationAreRejected() {
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(fixture.tx).execute(status->service(e->fail()).deliverNext()));
        assertThrows(IllegalArgumentException.class,()->new OperationalNoticeDelivery(store,e->{},fixture.writes,fixture.clock,Duration.ZERO,Duration.ofSeconds(1),()->"fictional"));
    }
    final class MemoryStore implements Store {
        Claim claim;long attempt;boolean done,failFinish;Instant retryAt;String error;
        final Map<String,Claim> claims=new HashMap<>();final Map<String,Boolean> finishes=new HashMap<>();
        public Claim claimed(String key){return claims.get(key);}
        public Claim claim(String key,String token,Instant now,Instant expires) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(done||retryAt!=null&&now.isBefore(retryAt)||claim!=null&&now.isBefore(claim.expiresAt()))return null;
            claim=new Claim(event,token,++attempt,expires);claims.put(key,claim);return claim;
        }
        public Boolean finished(String key){return finishes.get(key);}
        public boolean finish(String key,Claim value,Instant now,boolean accepted,Instant next,String code) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());if(failFinish)throw new IllegalStateException("fictional commit failure");
            if(!claim.equals(value)||!now.isBefore(claim.expiresAt()))return false;
            done=accepted;retryAt=next;error=code;claim=null;finishes.put(key,true);return true;
        }
    }
}
