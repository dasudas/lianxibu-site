package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;

import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.articles.EntityDedupService.Automatic;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import com.lianxibu.site.service.articles.EntityDedupService.Rejected;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class EntityDedupServiceTest {
    private JdbcTemplate jdbc;
    private EntityDedupService service;
    private JdbcEntityDedup store;
    private boolean failLog;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:entity-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,business_date DATE)");
        jdbc.update("INSERT INTO source_runs VALUES('run-one',DATE '2026-09-29')");
        jdbc.update("INSERT INTO source_runs VALUES('run-two',DATE '2026-09-30')");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "source_id VARCHAR(80),source_item_id VARCHAR(128),category VARCHAR(16),"
                + "entity_id VARCHAR(64),terminal_state VARCHAR(16),state VARCHAR(32),"
                + "projection_decision VARCHAR(16),effective_valid INT,revision BIGINT,"
                + "counted_occurrence_key BINARY(32) UNIQUE,created_at TIMESTAMP,validity_revision BIGINT,"
                + "is_new INT,dedup_json CLOB)");
        jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP,"
                + "revision BIGINT,category VARCHAR(16),identity_key CLOB,identity_hash BINARY(32),"
                + "title VARCHAR(512),summary CLOB,first_recorded_at TIMESTAMP,last_seen_at TIMESTAMP,"
                + "first_seen_business_date DATE,last_occurrence_at TIMESTAMP,occurrence_count BIGINT,"
                + "repeat_count BIGINT,analysis_state VARCHAR(32),analysis_generation BIGINT,"
                + "aliases_json CLOB,legacy_flag INT,UNIQUE(category,identity_hash),"
                + "CHECK(repeat_count=CASE WHEN occurrence_count=0 THEN 0 ELSE occurrence_count-1 END))");
        jdbc.execute("CREATE TABLE dispositions(action_key VARCHAR(128) PRIMARY KEY,item_id VARCHAR(64),"
                + "entity_id VARCHAR(64),state VARCHAR(32),fresh INT,occurrence_count BIGINT,"
                + "operation VARCHAR(32),old_entity_id VARCHAR(64),reason VARCHAR(2048),"
                + "request_hash VARCHAR(64))");
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(source),
                new DataSourceTransactionManager(source), () -> 25, ignored -> { });
        store = new JdbcEntityDedup(source);
        EntityDedupService.DispositionLog log = new EntityDedupService.DispositionLog() {
            @Override public Decision committed(String key, String requestHash) {
                var rows = jdbc.query("SELECT item_id,entity_id,state,fresh,occurrence_count,request_hash"
                                + " FROM dispositions WHERE action_key=?",
                        (rs, ignored) -> {
                            if (!requestHash.equals(rs.getString(6))) throw new Rejected();
                            return new Decision(rs.getString(1), rs.getString(2),
                                rs.getString(3), rs.getInt(4) == 1,
                                rs.getObject(5) == null ? null : rs.getLong(5));
                        }, key);
                return rows.isEmpty() ? null : rows.getFirst();
            }
            @Override public void append(String key, String requestHash, Decision result, String operation,
                                          String itemId, String oldId, String newId, String reason) {
                if (failLog) throw new IllegalStateException("disposition failed");
                jdbc.update("INSERT INTO dispositions VALUES(?,?,?,?,?,?,?,?,?,?)", key, itemId,
                        result.entityId(), result.state(), result.newContribution() ? 1 : 0,
                        result.occurrenceCount(), operation, oldId, reason, requestHash);
            }
        };
        service = new EntityDedupService(store, writes, log,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test void independentSourceItemsCountOnceAcrossRunsAndRetries() {
        item("one", "run-one", "source-a", "item-a", "news_tech");
        item("again", "run-two", "source-a", "item-a", "news_tech");
        item("second", "run-two", "source-a", "item-b", "news_tech");
        item("third", "run-two", "source-b", "item-a", "news_tech");
        Decision first = service.applyAutomatic(newDecision("one", "news_tech", "stable-key"), action("one"));
        assertEquals(1L, first.occurrenceCount());
        assertEquals(1, jdbc.queryForObject("SELECT is_new FROM source_run_items WHERE id='one'", Integer.class));
        assertEquals(first, service.applyAutomatic(newDecision("one", "news_tech", "stable-key"), action("one")));
        assertThrows(Rejected.class, () -> service.applyAutomatic(
                new Automatic("one", 0, "new", "news_tech", "other-key", null, List.of(),
                        "Fixture title", null, "verified evidence"), action("one")));
        Decision repeat = service.applyAutomatic(newDecision("again", "news_tech", "stable-key"), action("again"));
        assertFalse(repeat.newContribution());
        assertEquals(0, jdbc.queryForObject("SELECT is_new FROM source_run_items WHERE id='again'", Integer.class));
        assertEquals(1L, repeat.occurrenceCount());
        assertEquals(2L, service.applyAutomatic(newDecision("second", "news_tech", "stable-key"),
                action("second")).occurrenceCount());
        assertEquals(3L, service.applyAutomatic(newDecision("third", "news_tech", "stable-key"),
                action("third")).occurrenceCount());
        var entity = store.byIdentity("news_tech", "stable-key");
        assertEquals(LocalDate.of(2026, 9, 29), entity.firstSeenBusinessDate());
        assertEquals(2L, jdbc.queryForObject("SELECT repeat_count FROM analyzed_entities", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
    }

    @Test void categoryIsolationManualSplitAndExcludeRecomputeCurrentOwnership() {
        item("news-one", "run-one", "source-a", "item-a", "news_tech");
        item("news-two", "run-two", "source-a", "item-b", "news_tech");
        item("agent-one", "run-two", "source-a", "item-c", "agent");
        service.applyAutomatic(newDecision("news-one", "news_tech", "same-key"), action("news-one"));
        service.applyAutomatic(newDecision("news-two", "news_tech", "same-key"), action("news-two"));
        service.applyAutomatic(newDecision("agent-one", "agent", "same-key"), action("agent-one"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
        String newsId = store.byIdentity("news_tech", "same-key").id();
        String agentId = store.byIdentity("agent", "same-key").id();
        assertNotEquals(newsId, agentId);
        assertThrows(Rejected.class, () -> service.associate("news-two", 1, agentId,
                "cross category", action("invalid-associate")));
        Decision split = service.split("news-two", 1, "new-event", "New event", null,
                "manual split", action("split"));
        assertEquals(1L, split.occurrenceCount());
        assertEquals(1L, store.entity(newsId).occurrenceCount());
        assertEquals(1L, store.entity(agentId).occurrenceCount());
        assertEquals(0L, service.exclude("news-one", 1, "false match", action("exclude"))
                .occurrenceCount());
        assertEquals(0L, store.entity(newsId).occurrenceCount());
    }

    @Test void uncertainCandidateStaysReviewAndDispositionFailureRollsBack() {
        item("uncertain", "run-one", "source-a", "item-a", "model");
        var uncertain = new Automatic("uncertain", 0, "needs_review", "model", "official-a",
                null, List.of(), "Model A", null, "uncertain identity");
        assertEquals("needs_review", service.applyAutomatic(uncertain, action("review")).state());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
        item("retry", "run-two", "source-b", "item-b", "model");
        failLog = true;
        assertThrows(IllegalStateException.class, () -> service.applyAutomatic(
                newDecision("retry", "model", "official-b"), action("retry")));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
        assertNull(store.member("retry").entityId());
        failLog = false;
        assertEquals(1L, service.applyAutomatic(newDecision("retry", "model", "official-b"),
                action("retry")).occurrenceCount());
    }

    @Test void excludingOriginalContributionTransfersItToValidRetryEvidence() {
        item("original", "run-one", "source-a", "item-a", "news_tech");
        item("retry", "run-two", "source-a", "item-a", "news_tech");
        service.applyAutomatic(newDecision("original", "news_tech", "one-event"), action("original"));
        service.applyAutomatic(newDecision("retry", "news_tech", "one-event"), action("retry"));
        String entityId = store.byIdentity("news_tech", "one-event").id();
        assertEquals(1L, store.entity(entityId).occurrenceCount());
        assertEquals(1L, service.exclude("original", 1, "bad evidence", action("remove-original"))
                .occurrenceCount());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items"
                + " WHERE counted_occurrence_key IS NOT NULL", Long.class));
        assertEquals(0L, service.exclude("retry", 1, "also invalid", action("remove-retry"))
                .occurrenceCount());
    }

    @Test void parallelFirstDiscoveryUsesOneEntityIdentity() throws Exception {
        item("parallel-a", "run-one", "source-a", "item-a", "model");
        item("parallel-b", "run-two", "source-b", "item-b", "model");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return service.applyAutomatic(newDecision("parallel-a", "model", "model-id"),
                        action("parallel-a"));
            });
            var second = pool.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return service.applyAutomatic(newDecision("parallel-b", "model", "model-id"),
                        action("parallel-b"));
            });
            start.countDown();
            assertEquals(first.get(4, TimeUnit.SECONDS).entityId(),
                    second.get(4, TimeUnit.SECONDS).entityId());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
        assertEquals(2L, store.byIdentity("model", "model-id").occurrenceCount());
    }

    @Test void manualAssociationAcceptsSameCategoryAndRejectsCrossCategory() {
        item("base", "run-one", "source-a", "item-a", "skill");
        item("unmatched", "run-two", "source-b", "item-b", "skill");
        item("different", "run-two", "source-c", "item-c", "model");
        service.applyAutomatic(newDecision("base", "skill", "repository:path"), action("base"));
        String skillId = store.byIdentity("skill", "repository:path").id();
        assertEquals(2L, service.associate("unmatched", 0, skillId,
                "reviewed same skill", action("associate")).occurrenceCount());
        assertThrows(Rejected.class, () -> service.associate("different", 0, skillId,
                "cross category", action("bad-category")));
        assertNull(store.member("different").entityId());
    }

    @Test void manualSplitMovesFirstRecognizedDateWithItsEvidence() {
        item("earliest", "run-one", "source-a", "item-a", "workflow");
        item("later", "run-two", "source-b", "item-b", "workflow");
        service.applyAutomatic(newDecision("earliest", "workflow", "workflow-id"), action("earliest"));
        service.applyAutomatic(newDecision("later", "workflow", "workflow-id"), action("later"));
        String oldId = store.byIdentity("workflow", "workflow-id").id();
        assertEquals(LocalDate.of(2026, 9, 29), store.entity(oldId).firstSeenBusinessDate());
        Decision moved = service.split("earliest", 1, "separate-workflow", "Separate workflow",
                null, "manual evidence split", action("split-earliest"));
        assertEquals(LocalDate.of(2026, 9, 30), store.entity(oldId).firstSeenBusinessDate());
        assertEquals(LocalDate.of(2026, 9, 29),
                store.entity(moved.entityId()).firstSeenBusinessDate());
    }

    @Test void manualCreateRequiresDistinctSameCategoryIdentity() {
        item("manual", "run-one", "source-a", "item-a", "agent");
        Decision created = service.createManual("manual", 0, "agent", "repository-42",
                "Agent fixture", null, "reviewed original evidence", action("manual-create"));
        assertEquals(1L, created.occurrenceCount());
        assertEquals(created, service.createManual("manual", 0, "agent", "repository-42",
                "Agent fixture", null, "reviewed original evidence", action("manual-create")));
        item("other", "run-two", "source-b", "item-b", "agent");
        assertThrows(Rejected.class, () -> service.createManual("other", 0, "agent",
                "repository-42", "Agent fixture", null, "duplicate", action("manual-duplicate")));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities", Long.class));
    }

    @Test void existingRecommendationMustNameFrozenSameCategoryCandidate() {
        item("base", "run-one", "source-a", "item-a", "skill");
        item("candidate", "run-two", "source-b", "item-b", "skill");
        service.applyAutomatic(newDecision("base", "skill", "repo:path"), action("base"));
        String entityId = store.byIdentity("skill", "repo:path").id();
        Automatic invalid = new Automatic("candidate", 0, "existing", "skill", "repo:path",
                entityId, List.of("other-entity"), "Skill fixture", null, "semantic match");
        assertThrows(Rejected.class, () -> service.applyAutomatic(invalid, action("bad-candidate")));
        assertNull(store.member("candidate").entityId());
        Automatic valid = new Automatic("candidate", 0, "existing", "skill", "repo:path",
                entityId, List.of(entityId), "Skill fixture", null, "semantic match");
        assertEquals(2L, service.applyAutomatic(valid, action("good-candidate"))
                .occurrenceCount());
    }

    private void item(String id, String runId, String sourceId, String sourceItemId, String category) {
        jdbc.update("INSERT INTO source_run_items VALUES(?,?,?,?,?,NULL,'accepted','pending',"
                        + "'initialized',1,0,NULL,?,0,NULL,NULL)", id, runId, sourceId, sourceItemId,
                category, Timestamp.from(Instant.parse("2026-09-30T00:00:00Z")));
    }
    private static Automatic newDecision(String id, String category, String identity) {
        return new Automatic(id, 0, "new", category, identity, null, List.of(),
                "Fixture title", null, "verified evidence");
    }
    private static ActionContext action(String id) { return new ActionContext(id, "entity-" + id, null); }
}
