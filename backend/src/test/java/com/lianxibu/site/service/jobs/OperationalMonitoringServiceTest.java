package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.OperationalMonitoringService.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
class OperationalMonitoringServiceTest {
    private final MutableClock clock = new MutableClock();
    private final Stub store = new Stub();
    private OperationalMonitoringService service;
    private static final Actor OWNER = new Actor("fictional-owner", "owner");
    private static final Key CAPACITY = new Key("OPS-04", "database", "fictional-resource");
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:ops-state-"+System.nanoTime()+";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds); jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)"); jdbc.update("INSERT INTO worker_lock VALUES(1)");
        service = new OperationalMonitoringService(store, () -> new Configuration(1, MonitoringFixtures.complete()),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(ds), new DataSourceTransactionManager(ds)), clock);
    }
    @Test void acknowledgeNeverUnlocksCapacityAndResumeNeedsContinuousFreshProof() {
        State state = sample(CAPACITY, 0, new Capacity(new BigDecimal("4")));
        State acknowledged = service.act(OWNER, CAPACITY, state.revision(), Action.ACKNOWLEDGE, "Fictional acknowledgement", null, action("ack"));
        assertTrue(acknowledged.acknowledged()); assertEquals(4, acknowledged.guards().size());
        assertThrows(Rejected.class, () -> service.act(OWNER, CAPACITY, acknowledged.revision(), Action.RESUME_CAPACITY, "Too early", null, action("early")));
        assertThrows(Rejected.class, () -> service.act(OWNER, CAPACITY, acknowledged.revision(), Action.SILENCE, "Maintenance", clock.instant().plusSeconds(60), action("silence")));
        for (int m=1; m<=31; m++) state = sample(CAPACITY, m, new Capacity(new BigDecimal("15")));
        assertTrue(state.recoveryReady()); assertEquals(4, state.guards().size());
        State readyToResume = state;
        State resumed = service.act(OWNER, CAPACITY, readyToResume.revision(), Action.RESUME_CAPACITY, "Fictional recovery proof", null, action("resume"));
        assertTrue(resumed.guards().isEmpty()); assertEquals(Status.RESOLVED, resumed.status());
        int saves = store.saves;
        clock.now = clock.now.plusSeconds(180);
        assertEquals(resumed, service.act(OWNER, CAPACITY, readyToResume.revision(), Action.RESUME_CAPACITY, "Fictional recovery proof", null, action("resume")));
        assertEquals(saves, store.saves);
        assertThrows(IllegalStateException.class, () -> service.act(OWNER, CAPACITY, readyToResume.revision(), Action.RESUME_CAPACITY, "Different reason", null, action("resume")));
    }
    @Test void upgradeBypassesAcknowledgementAndSameLevelReminderIsDeduplicated() {
        Key heartbeat = new Key("OPS-02", "collector", "fictional-node");
        State state = sample(heartbeat, 0, new Heartbeat(301, true, false, false));
        state = sample(heartbeat, 1, new Heartbeat(301, true, false, false));
        service.act(OWNER, heartbeat, state.revision(), Action.ACKNOWLEDGE, "Fictional acknowledgement", null, action("ack-node"));
        int count = store.notices.size();
        state = sample(heartbeat, 2, new Heartbeat(901, true, false, false));
        assertFalse(state.acknowledged()); assertEquals(count+1, store.notices.size());
        assertEquals("upgrade", store.notices.getLast().action());
        var original = state;
        clock.now = clock.now.plusSeconds(120);
        assertEquals(original, service.sample(heartbeat, true, new Heartbeat(901, true, false, false),
                OperationalRuleEngineTest.START.plusSeconds(120), action("sample-OPS-02-2")));
    }
    @Test void ownerRevisionAndStaleRecoveryChecksHaveNoSideEffects() {
        State state = sample(CAPACITY, 0, new Capacity(new BigDecimal("4")));
        State initial = state;
        assertThrows(RuntimeException.class, () -> service.act(new Actor("user", "user"), CAPACITY, initial.revision(), Action.ACKNOWLEDGE, "Reason", null, action("wrong-role")));
        assertThrows(Rejected.class, () -> service.act(OWNER, CAPACITY, initial.revision()+1, Action.ACKNOWLEDGE, "Reason", null, action("wrong-rev")));
        for (int m=1; m<=31; m++) state = sample(CAPACITY, m, new Capacity(new BigDecimal("15")));
        State ready = state; clock.now = clock.now.plusSeconds(61); int saves = store.saves;
        assertThrows(Rejected.class, () -> service.act(OWNER, CAPACITY, ready.revision(), Action.RESUME_CAPACITY, "Stale metrics", null, action("stale")));
        assertEquals(saves, store.saves); assertEquals(4, store.states.get(CAPACITY).guards().size());
    }
    @Test void waitingTimeCountsOnlyReadyUnpausedEligibleIntervals() {
        Instant start=Instant.parse("2026-09-30T16:00:00Z"); // midnight Shanghai
        var spans=List.of(new OperationalWaitingTime.Span(start,start.plusSeconds(3*3600),true,false),
                new OperationalWaitingTime.Span(start.plusSeconds(3*3600),start.plusSeconds(4*3600),true,true),
                new OperationalWaitingTime.Span(start.plusSeconds(4*3600),start.plusSeconds(5*3600),false,false),
                new OperationalWaitingTime.Span(start.plusSeconds(5*3600),start.plusSeconds(8*3600),true,false));
        assertEquals(4*3600,OperationalWaitingTime.eligibleSeconds(spans));
        assertThrows(IllegalArgumentException.class, () -> OperationalWaitingTime.eligibleSeconds(List.of(spans.getFirst(),spans.getFirst())));
    }
    private State sample(Key key,int minute,Reading reading) {
        clock.now=OperationalRuleEngineTest.START.plusSeconds(minute*60L);
        return service.sample(key,true,reading,clock.instant(),action("sample-"+key.ruleId()+"-"+minute));
    }
    private static ActionContext action(String key) { return new ActionContext(key,"fictional-ops",null); }
    private static final class MutableClock extends Clock {
        Instant now=OperationalRuleEngineTest.START;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    private static final class Stub implements Store {
        Map<Key,State> states=new HashMap<>(); Map<String,State> receipts=new HashMap<>();
        Map<String,String> hashes=new HashMap<>(); List<Notice> notices=new ArrayList<>(); int saves;
        @Override public State committed(String key,String hash) {
            if(hashes.containsKey(key)&&!hashes.get(key).equals(hash))throw new IllegalStateException("action conflict");
            return receipts.get(key);
        }
        @Override public State read(Key key){return states.get(key);}
        @Override public void save(State previous,State next,List<Notice> events,String key,String hash,String actor,String reason){
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(previous.revision(),states.containsKey(next.key())?states.get(next.key()).revision():0);
            states.put(next.key(),next);receipts.put(key,next);hashes.put(key,hash);notices.addAll(events);saves++;
        }
    }
}
