package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcOperationalSummaries;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.OperationalMonitoringService.Action;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class OperationalJdbcStoreTest {
    static final Key KEY=new Key("OPS-04","database","fictional-capacity");
    static final Actor OWNER=new Actor("fictional-owner","owner");
    OperationalPersistenceFixture fixture;
    OperationalJdbcStore store;
    OperationalMonitoringService monitor;
    @BeforeEach void setup(){fixture=new OperationalPersistenceFixture();store=fixture.store();monitor=fixture.monitor(store);}
    State sample(int minute,String free){
        fixture.clock.now=Instant.parse("2026-10-01T00:00:00Z").plusSeconds(minute*60L);
        return monitor.sample(KEY,true,new Capacity(new BigDecimal(free)),fixture.clock.instant(),action("sample-"+minute));
    }
    static ActionContext action(String value){return new ActionContext(value,"fictional-ops",null);}
    long count(){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);}
    @Test void realStoreReconstructsGuardsAndLatestSampleReceiptWithoutRepeatedAudit(){
        State first=sample(0,"4");assertEquals(1,count());
        store=fixture.store();monitor=fixture.monitor(store);
        assertEquals(first,store.read(KEY));
        assertEquals(first,monitor.sample(KEY,true,new Capacity(new BigDecimal("4")),fixture.clock.instant(),action("sample-0")));
        assertEquals(1,count());
        State next=sample(1,"4");assertEquals(1,count());assertEquals(4,store.read(KEY).guards().size());
        assertThrows(IllegalArgumentException.class,()->monitor.sample(KEY,true,new Capacity(new BigDecimal("4")),
                Instant.parse("2026-10-01T00:00:00Z"),action("sample-0")));
        assertEquals(next,store.read(KEY));assertEquals(1,count());
    }
    @Test void permanentAdminReceiptSurvivesLaterSamplesAndDoesNotUnlockANewAlert(){
        State state=sample(0,"4");for(int m=1;m<=31;m++)state=sample(m,"15");
        State resumed=monitor.act(OWNER,KEY,state.revision(),Action.RESUME_CAPACITY,"Fictional proof",null,action("resume"));
        long audits=count();assertEquals(4,audits); // trigger, 30-minute reminder, admin receipt, resolved intent
        store=fixture.store();monitor=fixture.monitor(store);
        State guardedAgain=sample(32,"4");assertEquals(4,guardedAgain.guards().size());
        assertEquals(resumed,monitor.act(OWNER,KEY,state.revision(),Action.RESUME_CAPACITY,"Fictional proof",null,action("resume")));
        assertEquals(guardedAgain,store.read(KEY));assertEquals(audits+1,count());
        final long revision=state.revision();
        assertThrows(OperationalJdbcStore.Conflict.class,()->monitor.act(OWNER,KEY,revision,Action.RESUME_CAPACITY,"Different proof",null,action("resume")));
    }
    @Test void auditFailureRollsBackSummaryAndNoPartialReceiptSurvives(){
        fixture.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT reject_ops CHECK (target_type IS NULL OR target_type <> 'ops_rule')");
        assertThrows(RuntimeException.class,()->sample(0,"4"));
        assertNull(store.read(KEY));assertEquals(0,count());
        assertEquals(0L,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM runtime_settings",Long.class));
    }
    @Test void adminAuditFailureDoesNotAcknowledgeTheAlert(){
        State first=sample(0,"4");
        fixture.jdbc.execute("ALTER TABLE jobs ALTER COLUMN action_payload VARCHAR(512)");
        assertThrows(RuntimeException.class,()->monitor.act(OWNER,KEY,first.revision(),Action.ACKNOWLEDGE,"Fictional acknowledgement",null,action("ack-fail")));
        assertEquals(first,store.read(KEY));assertFalse(store.read(KEY).acknowledged());assertEquals(1,count());
    }
    @Test void repositoryCasAndByteBoundRejectWithoutReplacingStoredState(){
        State first=sample(0,"4");var repository=new JdbcOperationalSummaries(fixture.source);
        assertThrows(JdbcOperationalSummaries.Conflict.class,()->fixture.writes.execute(action("cas"),key->null,
                ctx->{repository.replace(2,"{}",fixture.clock.instant());return true;}));
        assertThrows(IllegalArgumentException.class,()->fixture.writes.execute(action("oversized"),key->null,
                ctx->{repository.replace(1,"{\"padding\":\""+"x".repeat(262144)+"\"}",fixture.clock.instant());return true;}));
        assertEquals(first,store.read(KEY));assertEquals(1,count());
    }
    @Test void expiredResolvedProjectionLeavesHealthBaselineAndPermanentAudits(){
        State state=sample(0,"4");for(int m=1;m<=31;m++)state=sample(m,"15");
        monitor.act(OWNER,KEY,state.revision(),Action.RESUME_CAPACITY,"Fictional proof",null,action("resume"));
        long audits=count();sample(8*24*60,"15");
        assertNull(store.views().getFirst().resolvedAt());assertNull(store.read(KEY).firstSeenAt());
        assertTrue(store.read(KEY).guards().isEmpty());assertEquals(audits,count());
    }
}
