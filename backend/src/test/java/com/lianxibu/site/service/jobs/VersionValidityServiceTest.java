package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.VersionValidityService.Action;
import com.lianxibu.site.service.jobs.VersionValidityService.Command;
import com.lianxibu.site.service.jobs.VersionValidityService.Dependent;
import com.lianxibu.site.service.jobs.VersionValidityService.Page;
import com.lianxibu.site.service.jobs.VersionValidityService.Result;
import com.lianxibu.site.service.jobs.VersionValidityService.Store;
import com.lianxibu.site.service.jobs.VersionValidityService.Validity;
import com.lianxibu.site.service.jobs.VersionValidityService.Work;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;

class VersionValidityServiceTest {
    private static final JsonNode EVIDENCE = key("{\"kind\":\"article_revision\",\"id\":\"e-1\"}");
    private static final JsonNode OLD = key("{\"kind\":\"content_version\",\"content_kind\":\"report\","
            + "\"content_id\":\"r-1\",\"revision\":1,\"body_hash\":\"" + "a".repeat(64)
            + "\",\"input_job_id\":\"j-1\"}");
    private static final JsonNode NEW = key("{\"kind\":\"content_version\",\"content_kind\":\"report\","
            + "\"content_id\":\"r-1\",\"revision\":2,\"body_hash\":\"" + "b".repeat(64)
            + "\",\"input_job_id\":\"j-2\"}");
    private static final JsonNode WEEKLY = key("{\"kind\":\"content_version\",\"content_kind\":\"report\","
            + "\"content_id\":\"week\",\"revision\":1,\"body_hash\":\"" + "c".repeat(64)
            + "\",\"input_job_id\":\"j-w\"}");
    private Fixture store;
    private VersionValidityService service;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:validity-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        store = new Fixture();
        service = new VersionValidityService(new SharedWriteExecutor(
                new JdbcSharedWriteEntry(source), new DataSourceTransactionManager(source)), store);
    }

    @Test void oldVersionInvalidationPropagatesThroughFrozenChainNotReplacement() {
        store.edges.put(id(OLD), List.of(dependent(WEEKLY)));
        store.currentVersion = NEW;
        Result result = service.change(command(OLD, 0, Action.INVALIDATE, null), action("a"));
        assertEquals(1, result.validityRevision());
        assertFalse(result.effectiveValid());
        assertEquals(0, store.closedCurrent);
        drain();
        assertTrue(store.blocked.contains(id(WEEKLY)));
        assertFalse(store.blocked.contains(id(NEW)));
        assertEquals(1, store.dispositions.size());
        assertTrue(service.change(command(OLD, 0, Action.INVALIDATE, null), action("a")).replayed());
    }

    @Test void evidenceRecursesAcrossHistoricalVersionEvenWhenCurrentCasLoses() {
        store.edges.put(id(EVIDENCE), List.of(dependent(OLD)));
        store.edges.put(id(OLD), List.of(dependent(WEEKLY)));
        store.stale.add(id(OLD));
        service.change(command(EVIDENCE, 0, Action.INVALIDATE, null), action("a"));
        drain();
        assertFalse(store.blocked.contains(id(OLD)));
        assertTrue(store.blocked.contains(id(WEEKLY)));
        assertEquals(1, store.validity.get(id(EVIDENCE)).revision());
    }

    @Test void reversalIncrementsRevisionButDoesNotReopenOrClearOtherInvalidations() {
        store.currentVersion = OLD;
        Result first = service.change(command(OLD, 0, Action.INVALIDATE, null), action("a"));
        assertEquals(1, store.closedCurrent);
        Result second = service.change(command(OLD, 1, Action.INVALIDATE, null), action("b"));
        Result reversed = service.change(command(OLD, 2, Action.REVERSE_INVALIDATION,
                first.dispositionId()), action("c"));
        assertEquals(3, reversed.validityRevision());
        assertFalse(reversed.effectiveValid());
        assertEquals(Set.of(second.dispositionId()), store.validity.get(id(OLD)).activeDispositionIds());
        assertThrows(VersionValidityService.Rejected.class,
                () -> service.change(command(OLD, 3, Action.REVERSE_INVALIDATION,
                        first.dispositionId()), action("d")));
        Result last = service.change(command(OLD, 3, Action.REVERSE_INVALIDATION,
                second.dispositionId()), action("e"));
        assertTrue(last.effectiveValid());
        assertEquals(4, last.validityRevision());
        assertEquals(1, store.closedCurrent);
    }

    @Test void displayAdjustmentDoesNotCreateDispositionAndRejectsWrongVersion() {
        store.currentVersion = NEW;
        service.displayAdjustment(NEW, action("display"));
        assertEquals(1, store.closedCurrent);
        assertTrue(store.dispositions.isEmpty());
        assertThrows(VersionValidityService.Rejected.class,
                () -> service.change(command(key("{\"kind\":\"article_revision\",\"id\":\"missing\"}"),
                        0, Action.INVALIDATE, null), action("missing")));
        assertThrows(VersionValidityService.Rejected.class,
                () -> service.change(command(OLD, 1, Action.INVALIDATE, null), action("cas")));
    }

    @Test void cyclicFrozenReferencesAreVisitedOncePerDisposition() {
        store.edges.put(id(EVIDENCE), List.of(dependent(OLD)));
        store.edges.put(id(OLD), List.of(dependent(OLD)));
        service.change(command(EVIDENCE, 0, Action.INVALIDATE, null), action("cycle"));
        drain();
        assertEquals(2, store.enqueued.size());
        assertTrue(store.queue.isEmpty());
    }

    private void drain() {
        int steps = 0;
        while (service.propagateNext(action("step-" + steps))) {
            if (++steps > 10) fail("propagation did not terminate");
        }
    }
    private static JsonNode key(String json) { return CanonicalJson.parse(json); }
    private static String id(JsonNode target) { return new String(CanonicalJson.bytes(target)); }
    private static ActionContext action(String key) { return new ActionContext(key, "disposition", null); }
    private static Command command(JsonNode target, long revision, Action action, String reverse) {
        return new Command(target, revision, action, "fixture reason", reverse);
    }
    private static Dependent dependent(JsonNode key) {
        return new Dependent(key, 1, "frozen-job", "review-stamp");
    }

    private static final class Fixture implements Store {
        final Map<String, Validity> validity = new HashMap<>();
        final Map<String, Result> dispositions = new LinkedHashMap<>();
        final Map<String, String> actionHash = new HashMap<>();
        final Map<String, List<Dependent>> edges = new HashMap<>();
        final Map<String, Work> queue = new LinkedHashMap<>();
        final Set<String> enqueued = new HashSet<>();
        final Set<String> blocked = new HashSet<>();
        final Set<String> stale = new HashSet<>();
        JsonNode currentVersion;
        int closedCurrent;
        int sequence;
        @Override public Result committed(String key, String hash) {
            if (actionHash.containsKey(key) && !actionHash.get(key).equals(hash))
                throw new IllegalStateException("action conflict");
            return dispositions.get(key);
        }
        @Override public void lockStableIdentity(JsonNode target) { }
        @Override public boolean exactVersionExists(JsonNode target) {
            return !"missing".equals(target.path("id").asText());
        }
        @Override public Validity validity(JsonNode target) {
            return validity.getOrDefault(id(target), new Validity(0, true, Set.of()));
        }
        @Override public String append(Command command, String hash, long next, String key) {
            String disposition = "d-" + ++sequence;
            Validity old = validity(command.target());
            Set<String> active = new HashSet<>(old.activeDispositionIds());
            if (command.action() == Action.INVALIDATE) active.add(disposition);
            else active.remove(command.reversesDispositionId());
            validity.put(id(command.target()), new Validity(next, active.isEmpty(), Set.copyOf(active)));
            dispositions.put(key, new Result(disposition, id(command.target()), next,
                    active.isEmpty(), false));
            actionHash.put(key, hash);
            return disposition;
        }
        @Override public void closeCurrentVersionIfExact(JsonNode target) {
            if (target.equals(currentVersion) && closedCurrent == 0) closedCurrent++;
        }
        @Override public void withdrawDisplay(JsonNode target) { closedCurrent++; }
        @Override public void enqueue(String disposition, JsonNode target) {
            String key = disposition + id(target);
            if (enqueued.add(key)) queue.put(key, new Work(disposition, target, null));
        }
        @Override public Work nextWork() { return queue.values().stream().findFirst().orElse(null); }
        @Override public Page directDependents(JsonNode target, String cursor, int limit) {
            return new Page(edges.getOrDefault(id(target), List.of()), null);
        }
        @Override public boolean blockIfCurrent(Dependent dependent, String disposition) {
            if (stale.contains(id(dependent.versionKey()))) return false;
            blocked.add(id(dependent.versionKey()));
            return true;
        }
        @Override public void advance(Work work, String cursor) {
            queue.put(work.dispositionId() + id(work.target()), new Work(work.dispositionId(), work.target(), cursor));
        }
        @Override public void complete(Work work) { queue.remove(work.dispositionId() + id(work.target())); }
    }
}
